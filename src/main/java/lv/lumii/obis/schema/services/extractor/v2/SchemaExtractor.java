package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v2.*;
import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.common.dto.QueryResponse;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries;
import lv.lumii.obis.schema.services.extractor.dto.*;
import lv.lumii.obis.schema.services.extractor.v2.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.*;

@Slf4j
@Service
public class SchemaExtractor {

    private static Comparator<String> nullSafeStringComparator = Comparator.nullsLast(String::compareToIgnoreCase);

    private enum ImportanceIndexValidationType {DOMAIN, RANGE, PAIR_DOMAIN, PAIR_RANGE}

    @Autowired
    @Setter
    @Getter
    protected SparqlEndpointProcessor sparqlEndpointProcessor;

    @Autowired
    @Setter
    @Getter
    private JsonSchemaService jsonSchemaService;

    @Nonnull
    public Schema extractSchema(@Nonnull SchemaExtractorRequestDto request) {
        Schema schema = initializeSchema(request);
        Map<String, SchemaExtractorClassNodeInfo> graphOfClasses = new HashMap<>();
        buildClasses(request, schema, graphOfClasses);
        buildProperties(request, schema, graphOfClasses);
        buildLabels(request, schema);
        buildNamespaceMap(request, schema);
        return schema;
    }

    protected Schema initializeSchema(@Nonnull SchemaExtractorRequestDto request) {
        Schema schema = new Schema();
        schema.setName((StringUtils.isNotEmpty(request.getGraphName())) ? request.getGraphName() + "_Schema" : "Schema");
        buildExtractionProperties(request, schema);
        return schema;
    }

    protected void buildClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses) {
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");

        // validate whether the request includes the list of classes with instance counts
        boolean requestedClassesWithInstanceCounts = request.getIncludedClasses().stream().anyMatch(c -> SchemaUtil.getLongValueFromString(c.getInstanceCount()) > 0L);

        // if the request does not include the list of classes or classes do not have instance count - read from the SPARQL endpoint
        List<SchemaClass> classes;
        if (isFalse(requestedClassesWithInstanceCounts)) {
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLASSES_WITH_INSTANCE_COUNT);
            classes = processClassesWithEndpointData(queryResults, request);
            queryResults.clear();
            log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        } else {
            classes = processClasses(request.getIncludedClasses(), request);
            log.info(request.getCorrelationId() + String.format(" - found total %d classes from the request input", classes.size()));
        }

        schema.setClasses(classes);

        if (isTrue(request.getCalculateSubClassRelations())) {

            for (SchemaClass c : classes) {
                graphOfClasses.put(c.getFullName(), new SchemaExtractorClassNodeInfo(c.getFullName(), c.getInstanceCount()));
            }

            // find intersection classes
            log.info(request.getCorrelationId() + " - findIntersectionClassesAndUpdateClassNeighbors");
            findIntersectionClassesAndUpdateClassNeighbors(classes, graphOfClasses, request);

            // sort classes by neighbors  size and instances count (ascending)
            log.info(request.getCorrelationId() + " - sortClassesByNeighborsSizeAndInstanceCountAscending");
            graphOfClasses = sortGraphOfClassesByNeighborsSizeAsc(graphOfClasses);

            // find superclasses
            log.info(request.getCorrelationId() + " - calculateSuperclassRelations");
            processSuperclasses(graphOfClasses, classes, request);

            // validate and update classes for multiple inheritance cases
            log.info(request.getCorrelationId() + " - updateMultipleInheritanceSuperclasses");
            updateMultipleInheritanceSuperclasses(graphOfClasses, classes, request);
        }
    }

    protected List<SchemaClass> processClassesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request) {
        List<SchemaClass> classes = new ArrayList<>();
        Set<String> includedClasses = request.getIncludedClasses().stream().map(SchemaExtractorRequestedClassDto::getClassName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (includedClasses.isEmpty() || includedClasses.contains(className)) {
                addClass(className, instancesCountStr, classes, request);
            }
        }
        return classes;
    }

    protected List<SchemaClass> processClasses(@Nonnull List<SchemaExtractorRequestedClassDto> includedClasses, @Nonnull SchemaExtractorRequestDto request) {
        List<SchemaClass> classes = new ArrayList<>();
        includedClasses.forEach(c -> {
            addClass(c.getClassName(), c.getInstanceCount(), classes, request);
        });
        return classes;
    }

    private void addClass(@Nullable String className, @Nullable String instancesCountStr, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
            SchemaClass classEntry = new SchemaClass();
            setLocalNameAndNamespace(className, classEntry);
            classEntry.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));
            if (classEntry.getInstanceCount() < request.getMinimalAnalyzedClassSize()) {
                classEntry.setPropertiesInSchema(Boolean.FALSE);
            } else {
                classEntry.setPropertiesInSchema(Boolean.TRUE);
            }
            classes.add(classEntry);
        }
    }

    protected void buildProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses) {
        // find all properties with triple count
        log.info(request.getCorrelationId() + " - findAllPropertiesWithTripleCount");

        // validate whether the request includes the list of properties with instance counts
        boolean requestedPropertiesWithInstanceCounts = request.getIncludedProperties().stream().anyMatch(p -> SchemaUtil.getLongValueFromString(p.getInstanceCount()) > 0L);

        // if the request does not include the list of properties or properties do not have instance count - read from the SPARQL endpoint
        Map<String, SchemaExtractorPropertyNodeInfo> properties;
        if (isFalse(requestedPropertiesWithInstanceCounts)) {
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_ALL_PROPERTIES);
            properties = processPropertiesWithEndpointData(queryResults, request);
            queryResults.clear();
            log.info(request.getCorrelationId() + String.format(" - found %d properties", properties.size()));
        } else {
            properties = processProperties(request.getIncludedProperties(), request);
            log.info(request.getCorrelationId() + String.format(" - found %d properties from the request input", properties.size()));
        }

        // fill properties with additional data
        enrichProperties(properties, schema, graphOfClasses, request);

        // fill schema object with attributes and roles
        formatProperties(properties, schema);
    }

    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processPropertiesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        Set<String> includedProperties = request.getIncludedProperties().stream().map(SchemaExtractorRequestedPropertyDto::getPropertyName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {

            String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

            // process only required properties
            if (includedProperties.isEmpty() || includedProperties.contains(propertyName)) {
                addProperty(propertyName, instancesCountStr, properties, request);
            }
        }
        return properties;
    }

    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processProperties(@Nonnull List<SchemaExtractorRequestedPropertyDto> includedProperties, @Nonnull SchemaExtractorRequestDto request) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        includedProperties.forEach(p -> {
            addProperty(p.getPropertyName(), p.getInstanceCount(), properties, request);
        });
        return properties;
    }

    private void addProperty(@Nullable String propertyName, @Nullable String instancesCountStr, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                             @Nonnull SchemaExtractorRequestDto request) {
        if (StringUtils.isNotEmpty(propertyName) && isNotExcludedResource(propertyName, request.getExcludedNamespaces())) {
            if (!properties.containsKey(propertyName)) {
                properties.put(propertyName, new SchemaExtractorPropertyNodeInfo());
            }
            SchemaExtractorPropertyNodeInfo property = properties.get(propertyName);
            property.setPropertyName(propertyName);
            property.setTripleCount(SchemaUtil.getLongValueFromString(instancesCountStr));
        }
    }

    protected void enrichProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema,
                                    @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            determinePropertyObjectTripleCount(property, request);
            determinePropertyDataTripleCount(property, request);
            determinePropertyType(property, request);

            determinePropertyDomains(property, schema, request);
            determinePropertyDomainTripleCount(property, request);
            determinePropertyDomainObjectTripleCount(property, request);
            determinePropertyDomainDataTripleCount(property, request);

            if (property.getObjectTripleCount() > 0) {
                determinePropertyRanges(property, schema, request);
                determinePropertyRangeTripleCount(property, request);
                if (isTrue(request.getCalculateDomainAndRangePairs())) {
                    determinePropertyDomainRangePairs(property, schema, request);
                }

            }

            determinePropertyClosedDomains(property, request);
            determinePropertyClosedRanges(property, request);
            determinePropertyClosedRangesOnSourceClassLevel(property, request);
            determinePropertyClosedDomainsOnTargetClassLevel(property, request);

            if (isFalse(property.getIsObjectProperty()) && isTrue(request.getCalculateDataTypes())) {
                determinePropertyDataTypes(property, request);
                determinePropertyDomainsDataTypes(property, request);
            }

            if (isTrue(request.getCalculateSubClassRelations())) {
                determinePrincipalDomainsAndRanges(property, schema.getClasses(), graphOfClasses, request);
            }

            if (isTrue(request.getCalculatePropertyPropertyRelations())) {
                determineFollowers(property, request);
                determineOutgoingProperties(property, request);
                determineIncomingProperties(property, request);
            }

            switch (request.getCalculateCardinalitiesMode()) {
                case propertyLevelOnly:
                    determinePropertyMaxCardinality(property, request);
                    if (property.getObjectTripleCount() > 0) {
                        determinePropertyInverseMaxCardinality(property, request);
                    }
                    break;
                case propertyLevelAndClassContext:
                    determinePropertyMaxCardinality(property, request);
                    determinePropertyDomainsMaxCardinality(property, request);
                    determinePropertyDomainsMinCardinality(property, request);
                    if (property.getObjectTripleCount() > 0) {
                        determinePropertyInverseMaxCardinality(property, request);
                        determinePropertyRangesInverseMinCardinality(property, request);
                        determinePropertyRangesInverseMaxCardinality(property, request);
                    }
                    break;
                case none:
                    // do not calculate cardinalities
                default:
                    break;
            }
        }
    }

    protected void determinePropertyObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyObjectTripleCount [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property
        long objectTripleCount = 0L;
        String queryForUrlCount = COUNT_PROPERTY_URL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponseForUrlCount = sparqlEndpointProcessor.read(request, COUNT_PROPERTY_URL_VALUES.name(), queryForUrlCount, true);
        if (!queryResponseForUrlCount.hasErrors() && !queryResponseForUrlCount.getResults().isEmpty() && queryResponseForUrlCount.getResults().get(0) != null) {
            objectTripleCount = SchemaUtil.getLongValueFromString(queryResponseForUrlCount.getResults().get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        } else {
            String checkQueryForUrlCount = CHECK_PROPERTY_URL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse checkQueryResponseForUrlCount = sparqlEndpointProcessor.read(request, CHECK_PROPERTY_URL_VALUES.name(), checkQueryForUrlCount, false);
            if (!checkQueryResponseForUrlCount.hasErrors() && !checkQueryResponseForUrlCount.getResults().isEmpty() && checkQueryResponseForUrlCount.getResults().get(0) != null) {
                objectTripleCount = -1L;
            }
        }
        property.setObjectTripleCount(objectTripleCount);
    }

    protected void determinePropertyDataTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTripleCount [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property
        long dataTripleCount = 0L;
        String queryForLiteralCount = COUNT_PROPERTY_LITERAL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponseForLiteralCount = sparqlEndpointProcessor.read(request, COUNT_PROPERTY_LITERAL_VALUES.name(), queryForLiteralCount, true);
        if (!queryResponseForLiteralCount.hasErrors() && !queryResponseForLiteralCount.getResults().isEmpty() && queryResponseForLiteralCount.getResults().get(0) != null) {
            dataTripleCount = SchemaUtil.getLongValueFromString(queryResponseForLiteralCount.getResults().get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        } else {
            String checkQueryForLiteralCount = CHECK_PROPERTY_LITERAL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse checkQueryResponseForLiteralCount = sparqlEndpointProcessor.read(request, CHECK_PROPERTY_LITERAL_VALUES.name(), checkQueryForLiteralCount, false);
            if (!checkQueryResponseForLiteralCount.hasErrors() && !checkQueryResponseForLiteralCount.getResults().isEmpty() && checkQueryResponseForLiteralCount.getResults().get(0) != null) {
                dataTripleCount = -1L;
            }
        }
        property.setDataTripleCount(dataTripleCount);
    }

    protected void determinePropertyType(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyType [" + property.getPropertyName() + "]");

        if (property.getTripleCount().equals(property.getObjectTripleCount())) {
            property.setIsObjectProperty(Boolean.TRUE);
        } else {
            property.setIsObjectProperty(Boolean.FALSE);
        }
    }

    protected void determinePropertyDomains(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomains [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT.name(), query, true);

        for (QueryResult queryResult : queryResponse.getResults()) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                SchemaClass schemaClass = findClass(schema.getClasses(), className);
                if (schemaClass != null && isNotFalse(schemaClass.getPropertiesInSchema())) {
                    property.getDomainClasses().add(new SchemaExtractorClassNodeInfo(className));
                }
            }
        }

        if (queryResponse.hasErrors() && property.getDomainClasses().isEmpty()) {
            schema.getClasses().forEach(potentialDomain -> {
                if (isNotFalse(potentialDomain.getPropertiesInSchema())) {
                    String checkDomainQuery = CHECK_CLASS_AS_PROPERTY_DOMAIN.getSparqlQuery()
                            .replace(SPARQL_QUERY_BINDING_NAME_CLASS, potentialDomain.getFullName())
                            .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse checkDomainQueryResponse = sparqlEndpointProcessor.read(request, CHECK_CLASS_AS_PROPERTY_DOMAIN.name(), checkDomainQuery, false);
                    if (!checkDomainQueryResponse.hasErrors() && !checkDomainQueryResponse.getResults().isEmpty()) {
                        property.getDomainClasses().add(new SchemaExtractorClassNodeInfo(potentialDomain.getFullName()));
                    }
                }
            });
        }
    }

    protected void determinePropertyDomainTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertyDomains [" + property.getPropertyName() + "]");
        property.getDomainClasses().forEach(domainClass -> {
            String queryTripleCounts = FIND_PROPERTY_DOMAINS_TRIPLE_COUNT.getSparqlQuery()
                    .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .replace(SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
            List<QueryResult> queryTripleCountsResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS_TRIPLE_COUNT.name(), queryTripleCounts);
            for (QueryResult queryResult : queryTripleCountsResults) {
                if (queryResult != null) {
                    domainClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertyDomainObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineObjectTripleCountForAllPropertyDomains [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property and specific domain
        property.getDomainClasses().forEach(domainClass -> {
            if (property.getObjectTripleCount() == 0) {
                domainClass.setObjectTripleCount(0L);
            } else {
                String query = SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.getSparqlQuery().
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.name(), query, true);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long objectTripleCountForDomain = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    domainClass.setObjectTripleCount(objectTripleCountForDomain);
                } else {
                    String checkQuery = SchemaExtractorQueries.CHECK_PROPERTY_URL_VALUES_FOR_DOMAIN.getSparqlQuery().
                            replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                            replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.CHECK_PROPERTY_URL_VALUES_FOR_DOMAIN.name(), checkQuery, false);
                    if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                        domainClass.setObjectTripleCount(-1L);
                    }
                }
            }
        });
    }

    protected void determinePropertyDomainDataTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDataTripleCountForAllPropertyDomains [" + property.getPropertyName() + "]");

        // get count of literal values for the specific property and specific domain
        property.getDomainClasses().forEach(domainClass -> {
            if (property.getDataTripleCount() == 0) {
                domainClass.setDataTripleCount(0L);
            } else {
                String query = COUNT_PROPERTY_LITERAL_VALUES_FOR_DOMAIN.getSparqlQuery().
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.COUNT_PROPERTY_LITERAL_VALUES_FOR_DOMAIN.name(), query, true);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long dataTripleCountForDomain = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    domainClass.setDataTripleCount(dataTripleCountForDomain);
                } else {
                    String checkQuery = SchemaExtractorQueries.CHECK_PROPERTY_LITERAL_VALUES_FOR_DOMAIN.getSparqlQuery().
                            replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                            replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.CHECK_PROPERTY_LITERAL_VALUES_FOR_DOMAIN.name(), checkQuery, false);
                    if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                        domainClass.setDataTripleCount(-1L);
                    }
                }
            }
        });
    }

    protected void determinePropertyRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyRanges [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT.name(), query, true);

        for (QueryResult queryResult : queryResponse.getResults()) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                SchemaClass schemaClass = findClass(schema.getClasses(), className);
                if (schemaClass != null && isNotFalse(schemaClass.getPropertiesInSchema())) {
                    property.getRangeClasses().add(new SchemaExtractorClassNodeInfo(className));
                }
            }
        }

        if (queryResponse.hasErrors() && property.getRangeClasses().isEmpty()) {
            schema.getClasses().forEach(potentialRange -> {
                if (isNotFalse(potentialRange.getPropertiesInSchema())) {
                    String checkRangeQuery = CHECK_CLASS_AS_PROPERTY_RANGE.getSparqlQuery()
                            .replace(SPARQL_QUERY_BINDING_NAME_CLASS, potentialRange.getFullName())
                            .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse checkRangeQueryResponse = sparqlEndpointProcessor.read(request, CHECK_CLASS_AS_PROPERTY_RANGE.name(), checkRangeQuery, false);
                    if (!checkRangeQueryResponse.hasErrors() && !checkRangeQueryResponse.getResults().isEmpty()) {
                        property.getRangeClasses().add(new SchemaExtractorClassNodeInfo(potentialRange.getFullName()));
                    }
                }
            });
        }
    }

    protected void determinePropertyRangeTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertyRanges [" + property.getPropertyName() + "]");
        property.getRangeClasses().forEach(rangeClass -> {
            String queryTripleCounts = FIND_PROPERTY_RANGES_TRIPLE_COUNT.getSparqlQuery()
                    .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .replace(SPARQL_QUERY_BINDING_NAME_CLASS_RANGE, rangeClass.getClassName());
            List<QueryResult> queryTripleCountsResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_RANGES_TRIPLE_COUNT.name(), queryTripleCounts);
            for (QueryResult queryResult : queryTripleCountsResults) {
                if (queryResult != null) {
                    rangeClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertyDomainRangePairs(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairs [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAIN_RANGE_PAIRS.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS.name(), query);

        for (QueryResult queryResult : queryResults) {
            String domainClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN);
            String rangeClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_RANGE);
            String tripleCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (StringUtils.isNotEmpty(domainClass) && StringUtils.isNotEmpty(rangeClass)
                    && isNotExcludedResource(domainClass, request.getExcludedNamespaces()) && isNotExcludedResource(rangeClass, request.getExcludedNamespaces())) {
                SchemaClass domainSchemaClass = findClass(schema.getClasses(), domainClass);
                SchemaClass rangeSchemaClass = findClass(schema.getClasses(), rangeClass);
                if (domainSchemaClass != null && isNotFalse(domainSchemaClass.getPropertiesInSchema())
                        && rangeSchemaClass != null && isNotFalse(rangeSchemaClass.getPropertiesInSchema())) {
                    property.getDomainRangePairs().add(new SchemaExtractorDomainRangeInfo(domainClass, rangeClass, SchemaUtil.getLongValueFromString(tripleCountStr)));
                }
            }
        }
        // endpoint was not able to return class pairs with direct query, so trying to match each domain and range class combination
        if (property.getDomainRangePairs().isEmpty()) {
            log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairsForEachDomainAndRangeCombination [" + property.getPropertyName() + "]");

            property.getDomainClasses().forEach(domainClass -> property.getRangeClasses().forEach(rangeClass -> {
                String queryForPairMatch = FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES.getSparqlQuery()
                        .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .replace(SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName())
                        .replace(SPARQL_QUERY_BINDING_NAME_CLASS_RANGE, rangeClass.getClassName());
                List<QueryResult> queryResultsForPairMatch = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES.name(), queryForPairMatch);
                if (!queryResultsForPairMatch.isEmpty() && queryResultsForPairMatch.get(0) != null) {
                    Long tripleCountForPair = SchemaUtil.getLongValueFromString(queryResultsForPairMatch.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (tripleCountForPair > 0) {
                        property.getDomainRangePairs().add(new SchemaExtractorDomainRangeInfo(domainClass.getClassName(), rangeClass.getClassName(), tripleCountForPair));
                    }
                }
            }));
        }
    }

    protected void determinePropertyClosedDomains(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomains [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property level
        if (property.getDomainClasses().isEmpty()) {
            property.setIsClosedDomain((property.getTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            String query = FIND_CLOSED_DOMAIN_FOR_PROPERTY.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_DOMAIN_FOR_PROPERTY.name(), query);
            if (!queryResults.isEmpty() && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_X))) {
                property.setIsClosedDomain(Boolean.FALSE);
            } else {
                property.setIsClosedDomain(Boolean.TRUE);
            }
        }
    }

    protected void determinePropertyClosedRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRanges [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class
        if (property.getRangeClasses().isEmpty()) {
            property.setIsClosedRange((property.getObjectTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            String query = FIND_CLOSED_RANGE_FOR_PROPERTY.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_RANGE_FOR_PROPERTY.name(), query);
            if (!queryResults.isEmpty() && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_Y))) {
                property.setIsClosedRange(Boolean.FALSE);
            } else {
                property.setIsClosedRange(Boolean.TRUE);
            }
        }
    }

    protected void determinePropertyClosedRangesOnSourceClassLevel(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRangesOnSourceClassLevel [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property source class level
        if (isTrue(property.getIsClosedRange())) {
            property.getDomainClasses().forEach(domainClass -> domainClass.setIsClosedRange(Boolean.TRUE));
        } else if (isTrue(request.getCalculateDomainAndRangePairs())) {
            property.getDomainClasses().forEach(domainClass -> {
                String query = FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS.getSparqlQuery()
                        .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .replace(SPARQL_QUERY_BINDING_NAME_CLASS, domainClass.getClassName());
                List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS.name(), query);
                if (!queryResults.isEmpty() && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_Y))) {
                    domainClass.setIsClosedRange(Boolean.FALSE);
                } else {
                    domainClass.setIsClosedRange(Boolean.TRUE);
                }
            });
        }
    }

    protected void determinePropertyClosedDomainsOnTargetClassLevel(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRangesOnTargetClassLevel [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class - property target class level
        if (isTrue(property.getIsClosedDomain())) {
            property.getRangeClasses().forEach(rangeClass -> rangeClass.setIsClosedDomain(Boolean.TRUE));
        } else if (isTrue(request.getCalculateDomainAndRangePairs())) {
            property.getRangeClasses().forEach(rangeClass -> {
                String query = FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS.getSparqlQuery()
                        .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .replace(SPARQL_QUERY_BINDING_NAME_CLASS, rangeClass.getClassName());
                List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS.name(), query);
                if (!queryResults.isEmpty() && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_X))) {
                    rangeClass.setIsClosedDomain(Boolean.FALSE);
                } else {
                    rangeClass.setIsClosedDomain(Boolean.TRUE);
                }
            });
        }
    }

    protected void determinePropertyDataTypes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTypes [" + property.getPropertyName() + "]");
        // find data types
        String query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.name(), query);
        for (QueryResult queryResult : queryResults) {
            String resultDataType = queryResult.get(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(resultDataType)) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount));
            }
        }

        // find language tag
        query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING.name(), query);
        if (!queryResults.isEmpty()) {
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (tripleCount > 0L) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount));
            }
        }
    }

    protected void determinePropertyDomainsDataTypes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainsDataTypes [" + property.getPropertyName() + "]");

        property.getDomainClasses().forEach(domainClass -> {
            // find data types
            String query = FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_DOMAIN.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domainClass.getClassName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_DOMAIN.name(), query);
            for (QueryResult queryResult : queryResults) {
                String resultDataType = queryResult.get(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (StringUtils.isNotEmpty(resultDataType)) {
                    domainClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount));
                }
            }
            // find language tag
            query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_DOMAIN.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domainClass.getClassName());
            queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_DOMAIN.name(), query);
            if (!queryResults.isEmpty()) {
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (tripleCount > 0L) {
                    domainClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount));
                }
            }
        });
    }

    protected void determineFollowers(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyFollowers [" + property.getPropertyName() + "]");
        String query = SchemaExtractorQueries.FIND_PROPERTY_FOLLOWERS.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_FOLLOWERS.name(), query);
        for (QueryResult queryResult : queryResults) {
            String otherProperty = queryResult.get(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty)) {
                property.getFollowers().add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount));
            }
        }
    }

    protected void determineOutgoingProperties(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyOutgoingProperties [" + property.getPropertyName() + "]");
        String query = SchemaExtractorQueries.FIND_PROPERTY_OUTGOING_PROPERTIES.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_OUTGOING_PROPERTIES.name(), query);
        for (QueryResult queryResult : queryResults) {
            String otherProperty = queryResult.get(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty)) {
                property.getOutgoingProperties().add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount));
            }
        }
    }

    protected void determineIncomingProperties(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyIncomingProperties [" + property.getPropertyName() + "]");
        String query = SchemaExtractorQueries.FIND_PROPERTY_INCOMING_PROPERTIES.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_INCOMING_PROPERTIES.name(), query);
        for (QueryResult queryResult : queryResults) {
            String otherProperty = queryResult.get(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty)) {
                property.getIncomingProperties().add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount));
            }
        }
    }

    protected void determinePropertyDomainsMinCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainsMinCardinality [" + property.getPropertyName() + "]");
        property.getDomainClasses().forEach(domainClass -> {
            String query = SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domainClass.getClassName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.name(), query);
            if (!queryResults.isEmpty()) {
                domainClass.setMinCardinality(DEFAULT_MIN_CARDINALITY);
            } else {
                domainClass.setMinCardinality(1);
            }
        });
    }

    protected void determinePropertyMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyMaxCardinality [" + property.getPropertyName() + "]");
        String query = SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.name(), query);
        if (queryResults.isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
    }

    protected void determinePropertyDomainsMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainsMaxCardinality [" + property.getPropertyName() + "]");
        property.getDomainClasses().forEach(domainClass -> {
            String query = FIND_PROPERTY_MAX_CARDINALITY_FOR_DOMAIN.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domainClass.getClassName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY_FOR_DOMAIN.name(), query);
            if (queryResults.isEmpty()) {
                domainClass.setMaxCardinality(1);
            } else {
                domainClass.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
            }
        });
    }

    protected void determinePropertyInverseMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyInverseMaxCardinality [" + property.getPropertyName() + "]");
        String query = SchemaExtractorQueries.FIND_INVERSE_PROPERTY_MAX_CARDINALITY.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name(), query);
        if (queryResults.isEmpty()) {
            property.setMaxInverseCardinality(1);
            return;
        }
        property.setMaxInverseCardinality(DEFAULT_MAX_CARDINALITY);
    }

    protected void determinePropertyRangesInverseMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineInverseMaxCardinalityForPropertyRanges [" + property.getPropertyName() + "]");
        property.getRangeClasses().forEach(rangeClass -> {
            String queryInverseCardinalities = FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_RANGE.getSparqlQuery()
                    .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .replace(SPARQL_QUERY_BINDING_NAME_CLASS_RANGE, rangeClass.getClassName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_RANGE.name(), queryInverseCardinalities);
            rangeClass.setMaxInverseCardinality(queryResults.isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
        });
    }

    protected void determinePropertyRangesInverseMinCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyRangesInverseMinCardinality [" + property.getPropertyName() + "]");
        property.getRangeClasses().forEach(rangeClass -> {
            String query = FIND_INVERSE_PROPERTY_MIN_CARDINALITY.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, rangeClass.getClassName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_INVERSE_PROPERTY_MIN_CARDINALITY.name(), query);
            rangeClass.setMinInverseCardinality(!queryResults.isEmpty() ? DEFAULT_MIN_CARDINALITY : 1);
        });
    }

    protected void determinePrincipalDomainsAndRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                                      @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                      @Nonnull SchemaExtractorRequestDto request) {

        log.info(request.getCorrelationId() + " - determinePrincipalDomainClasses [" + property.getPropertyName() + "]");
        List<SchemaExtractorPropertyLinkedClassInfo> principalDomains = determinePrincipalClasses(property,
                buildAndSortPropertyLinkedClasses(property.getDomainClasses(), classes), classes, graphOfClasses, request, ImportanceIndexValidationType.DOMAIN, null, null);
        mapPrincipalClasses(property.getDomainClasses(), principalDomains);

        log.info(request.getCorrelationId() + " - determinePrincipalRangeClasses [" + property.getPropertyName() + "]");
        List<SchemaExtractorPropertyLinkedClassInfo> principalRanges = determinePrincipalClasses(property,
                buildAndSortPropertyLinkedClasses(property.getRangeClasses(), classes), classes, graphOfClasses, request, ImportanceIndexValidationType.RANGE, null, null);
        mapPrincipalClasses(property.getRangeClasses(), principalRanges);

        log.info(request.getCorrelationId() + " - determinePrincipalDomainRangePairs [" + property.getPropertyName() + "]");
        // set pair target important indexes
        property.getDomainClasses().forEach(domainClass -> {
            List<SchemaExtractorDomainRangeInfo> pairsForSpecificDomain = findPairsWithSpecificDomain(property.getDomainRangePairs(), domainClass.getClassName());
            List<SchemaExtractorPropertyLinkedClassInfo> principalPairRanges = determinePrincipalClasses(property,
                    buildAndSortPropertyPairRanges(pairsForSpecificDomain, classes), classes, graphOfClasses, request, ImportanceIndexValidationType.PAIR_DOMAIN, domainClass.getClassName(), null);
            pairsForSpecificDomain.forEach(linkedPair -> {
                SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairRanges.stream()
                        .filter(pairRange -> pairRange.getClassName().equalsIgnoreCase(linkedPair.getRangeClass())).findFirst().orElse(null);
                linkedPair.setTargetImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
            });
        });
        // set pair target important indexes
        property.getRangeClasses().forEach(rangeClass -> {
            List<SchemaExtractorDomainRangeInfo> pairsForSpecificRange = findPairsWithSpecificRange(property.getDomainRangePairs(), rangeClass.getClassName());
            List<SchemaExtractorPropertyLinkedClassInfo> principalPairDomains = determinePrincipalClasses(property,
                    buildAndSortPropertyPairDomains(pairsForSpecificRange, classes), classes, graphOfClasses, request, ImportanceIndexValidationType.PAIR_RANGE, null, rangeClass.getClassName());
            pairsForSpecificRange.forEach(linkedPair -> {
                SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairDomains.stream()
                        .filter(pairRange -> pairRange.getClassName().equalsIgnoreCase(linkedPair.getDomainClass())).findFirst().orElse(null);
                linkedPair.setSourceImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
            });
        });
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> determinePrincipalClasses(@Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                     @Nonnull List<SchemaExtractorPropertyLinkedClassInfo> propertyLinkedClassesSorted,
                                                                                     @Nonnull List<SchemaClass> classes,
                                                                                     @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                                                     @Nonnull SchemaExtractorRequestDto request,
                                                                                     @Nonnull ImportanceIndexValidationType importanceIndexValidationType,
                                                                                     @Nullable String domainClass,
                                                                                     @Nullable String rangeClass) {

        // set important indexes
        Set<String> importantClasses = new HashSet<>();
        int index = 1;
        for (SchemaExtractorPropertyLinkedClassInfo currentClass : propertyLinkedClassesSorted) {

            // skip owl:Thing and rdf:Resource
            if (OWL_RDF_TOP_LEVEL_RESOURCES.contains(currentClass.getClassName())) {
                currentClass.setImportanceIndex(0);
                continue;
            }

            // add the first real class as important
            if (importantClasses.isEmpty()) {
                importantClasses.add(currentClass.getClassName());
                currentClass.setImportanceIndex(index++);
                continue;
            }

            // check whether processed classes include the current class any subclass or superclass
            boolean isIncludedSubclassOrSuperClass = false;
            for (String includedClass : importantClasses) {
                boolean isIncludedSubClass = isClassAccessibleFromSuperclasses(includedClass, currentClass.getClassName(), classes);
                boolean isIncludedSuperClass = isClassAccessibleFromSubclasses(includedClass, currentClass.getClassName(), classes);
                if (isIncludedSubClass || isIncludedSuperClass) {
                    isIncludedSubclassOrSuperClass = true;
                    break;
                }
            }
            if (isIncludedSubclassOrSuperClass) {
                currentClass.setImportanceIndex(0);
                continue;
            }

            // check whether there is partial intersaction and if yes - check case by case
            if (graphOfClasses.get(currentClass.getClassName()) != null) {
                List<String> currentClassNeighbors = graphOfClasses.get(currentClass.getClassName()).getNeighbors();
                List<String> includedClassesToCheckWith = currentClassNeighbors.stream().filter(importantClasses::contains).collect(Collectors.toList());

                if (includedClassesToCheckWith.isEmpty()) {
                    currentClass.setImportanceIndex(index++);
                    importantClasses.add(currentClass.getClassName());
                    continue;
                }

                boolean needToInclude = false;

                switch (importanceIndexValidationType) {
                    case DOMAIN:
                        needToInclude = checkNewPrincipalDomainClass(property.getPropertyName(), currentClass.getClassName(), includedClassesToCheckWith, request);
                        break;
                    case RANGE:
                        needToInclude = checkNewPrincipalRangeClass(property.getPropertyName(), currentClass.getClassName(), includedClassesToCheckWith, request);
                        break;
                    case PAIR_DOMAIN:
                        if (StringUtils.isNotEmpty(domainClass)) {
                            needToInclude = checkNewPrincipalRangeClassForDomain(property.getPropertyName(), domainClass, currentClass.getClassName(), includedClassesToCheckWith, request);
                        }
                        break;
                    case PAIR_RANGE:
                        if (StringUtils.isNotEmpty(rangeClass)) {
                            needToInclude = checkNewPrincipalDomainClassForDomain(property.getPropertyName(), rangeClass, currentClass.getClassName(), includedClassesToCheckWith, request);
                        }
                        break;
                    default:
                        break;
                }

                if (needToInclude) {
                    currentClass.setImportanceIndex(index++);
                    importantClasses.add(currentClass.getClassName());
                } else {
                    currentClass.setImportanceIndex(0);
                }
            }
        }

        return propertyLinkedClassesSorted;
    }

    protected boolean checkNewPrincipalDomainClass(@Nonnull String propertyName, @Nonnull String newDomainClass, @Nonnull List<String> existingClasses, @Nonnull SchemaExtractorRequestDto request) {
        String query = CHECK_PRINCIPAL_DOMAIN.getSparqlQuery().
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, newDomainClass).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses));
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, CHECK_PRINCIPAL_DOMAIN.name(), query, true);
        return !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
    }

    protected boolean checkNewPrincipalRangeClass(@Nonnull String propertyName, @Nonnull String newRangeClass, @Nonnull List<String> existingClasses, @Nonnull SchemaExtractorRequestDto request) {

        String query = CHECK_PRINCIPAL_RANGE.getSparqlQuery().
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, newRangeClass).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses));
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, CHECK_PRINCIPAL_RANGE.name(), query, true);
        return !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
    }

    protected boolean checkNewPrincipalRangeClassForDomain(@Nonnull String propertyName, @Nonnull String domainClass, @Nonnull String newRangeClass, @Nonnull List<String> existingClasses, @Nonnull SchemaExtractorRequestDto request) {

        String query = CHECK_PRINCIPAL_RANGE_FOR_DOMAIN.getSparqlQuery().
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
                replace(SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, newRangeClass).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses));
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, CHECK_PRINCIPAL_RANGE_FOR_DOMAIN.name(), query, true);
        return !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
    }

    protected boolean checkNewPrincipalDomainClassForDomain(@Nonnull String propertyName, @Nonnull String rangeClass, @Nonnull String newDomainClass, @Nonnull List<String> existingClasses, @Nonnull SchemaExtractorRequestDto request) {

        String query = CHECK_PRINCIPAL_DOMAIN_FOR_RANGE.getSparqlQuery().
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
                replace(SPARQL_QUERY_BINDING_NAME_CLASS_RANGE, rangeClass).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, newDomainClass).
                replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses));
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, CHECK_PRINCIPAL_DOMAIN_FOR_RANGE.name(), query, true);
        return !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
    }

    protected String buildCustomFilterToCheckPrincipalClass(@Nonnull List<String> existingClasses) {
        StringBuilder customFilter = new StringBuilder(StringUtils.EMPTY);
        for (int i = 0; i < existingClasses.size(); i++) {
            if (i == 0) {
                customFilter
                        .append("?cc=<")
                        .append(existingClasses.get(i))
                        .append(">");
            } else {
                customFilter.
                        append(" || ?cc=<")
                        .append(existingClasses.get(i))
                        .append(">");
            }
        }
        return customFilter.toString();
    }

    protected void formatProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo propertyData = p.getValue();

            SchemaProperty property = new SchemaProperty();
            setLocalNameAndNamespace(p.getKey(), property);
            property.setMaxCardinality(propertyData.getMaxCardinality());
            property.setMaxInverseCardinality(propertyData.getMaxInverseCardinality());
            property.setTripleCount(propertyData.getTripleCount());
            property.setDataTripleCount(propertyData.getDataTripleCount());
            property.setObjectTripleCount(propertyData.getObjectTripleCount());
            property.setClosedDomain(propertyData.getIsClosedDomain());
            property.setClosedRange(propertyData.getIsClosedRange());
            property.getDataTypes().addAll(convertInternalDataTypesToApiDto(propertyData.getDataTypes()));
            property.getSourceClasses().addAll(convertInternalDtoToApiDto(propertyData.getDomainClasses()));
            property.getTargetClasses().addAll(convertInternalDtoToApiDto(propertyData.getRangeClasses()));
            propertyData.getDomainRangePairs().forEach(pair -> {
                if (isFalse(isDuplicatePair(property.getClassPairs(), pair.getDomainClass(), pair.getRangeClass()))) {
                    property.getClassPairs().add(new ClassPair(pair.getDomainClass(), pair.getRangeClass(),
                            pair.getTripleCount(), pair.getSourceImportanceIndex(), pair.getTargetImportanceIndex()));
                }
            });
            property.getFollowers().addAll(convertInternalLinkedPropertyToApiDto(propertyData.getFollowers()));
            property.getOutgoingProperties().addAll(convertInternalLinkedPropertyToApiDto(propertyData.getOutgoingProperties()));
            property.getIncomingProperties().addAll(convertInternalLinkedPropertyToApiDto(propertyData.getIncomingProperties()));

            schema.getProperties().add(property);
        }
    }

    protected List<SchemaPropertyLinkedClassDetails> convertInternalDtoToApiDto(@Nonnull List<SchemaExtractorClassNodeInfo> internalDtos) {
        return sortPropertyLinkedClassesByImportanceIndexAndTripleCount(
                internalDtos.stream()
                        .map(internalDto -> new SchemaPropertyLinkedClassDetails(
                                internalDto.getClassName(),
                                internalDto.getTripleCount(), internalDto.getDataTripleCount(), internalDto.getObjectTripleCount(),
                                internalDto.getIsClosedDomain(), internalDto.getIsClosedRange(),
                                internalDto.getMinCardinality(), internalDto.getMaxCardinality(),
                                internalDto.getMinInverseCardinality(), internalDto.getMaxInverseCardinality(),
                                internalDto.getImportanceIndex(), internalDto.getDataTypes())).
                        collect(Collectors.toList()));
    }

    protected List<DataType> convertInternalDataTypesToApiDto(@Nonnull List<SchemaExtractorDataTypeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new DataType(
                        internalDto.getDataType(), internalDto.getTripleCount())).
                collect(Collectors.toList());
    }

    protected List<SchemaPropertyLinkedPropertyDetails> convertInternalLinkedPropertyToApiDto(@Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> internalDtos) {
        return sortPropertyLinkedPropertiesByTripleCount(internalDtos).stream()
                .map(internalDto -> new SchemaPropertyLinkedPropertyDetails(
                        internalDto.getPropertyName(), internalDto.getTripleCount())).
                collect(Collectors.toList());
    }

    protected void buildNamespaceMap(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        // 1. apply namespaces defined in the request
        if (request.getPredefinedNamespaces() != null && request.getPredefinedNamespaces().getNamespaceItems() != null) {
            request.getPredefinedNamespaces().getNamespaceItems().forEach(namespaceItem ->
                    schema.getPrefixes().add(new NamespacePrefixEntry(namespaceItem.getPrefix(), namespaceItem.getNamespace())));
            return;
        }

        // 2. apply namespaces from the system config file and use only the ones from the current schema
        Set<String> orderedNamespaces = getAllNamespacesOrderedByUsageCount(schema);
        try {
            FileInputStream inputStream = new FileInputStream(SchemaConstants.GLOBAL_NAMESPACE_PATH);
            SchemaExtractorPredefinedNamespaces predefinedNamespaces = jsonSchemaService.getObjectFromJsonStream(inputStream, SchemaExtractorPredefinedNamespaces.class);
            if (predefinedNamespaces != null && predefinedNamespaces.getNamespaceItems() != null) {
                predefinedNamespaces.getNamespaceItems().forEach(namespaceItem -> {
                    if (orderedNamespaces.contains(namespaceItem.getNamespace())) {
                        schema.getPrefixes().add(new NamespacePrefixEntry(namespaceItem.getPrefix(), namespaceItem.getNamespace()));
                    }
                });
            }
            return;
        } catch (IOException e) {
            log.info("Cannot read namespaces from the system configuration: namespaces.json file was not found or was incorrectly formatted. The namespaces will be auto generated from the given schema");
        }

        // 3. apply namespaces from the analyzed schema with auto generated prefixes
        int index = 0;
        for (String namespace : orderedNamespaces) {
            if (index == 0) {
                schema.setDefaultNamespace(namespace);
                schema.getPrefixes().add(new NamespacePrefixEntry(SchemaConstants.DEFAULT_NAMESPACE_PREFIX, namespace));
            } else {
                schema.getPrefixes().add(new NamespacePrefixEntry(SchemaConstants.DEFAULT_NAMESPACE_PREFIX_AUTO + index, namespace));
            }
            index++;
        }
    }

    @Nonnull
    protected Set<String> getAllNamespacesOrderedByUsageCount(@Nonnull Schema schema) {
        List<String> namespaces = new ArrayList<>();
        for (SchemaClass item : schema.getClasses()) {
            namespaces.add(item.getNamespace());
        }
        for (SchemaProperty item : schema.getProperties()) {
            namespaces.add(item.getNamespace());
        }
        Map<String, Long> namespacesWithCounts = namespaces.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        Set<String> orderedNamespaces = new LinkedHashSet<>();
        namespacesWithCounts.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).forEach(entry -> {
            orderedNamespaces.add(entry.getKey());
        });
        return orderedNamespaces;
    }

    protected void buildExtractionProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_ENDPOINT, request.getEndpointUrl()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_GRAPH_NAME, request.getGraphName()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_CALCULATE_SUBCLASS_RELATIONS, request.getCalculateSubClassRelations().toString()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_CALCULATE_DOMAIN_AND_RANGE_PAIRS, request.getCalculateDomainAndRangePairs().toString()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_CALCULATE_DATA_TYPES, request.getCalculateDataTypes().toString()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_CALCULATE_CARDINALITIES, request.getCalculateCardinalitiesMode().toString()));
        if (request.getMinimalAnalyzedClassSize() != null) {
            schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_MIN_CLASS_SIZE, request.getMinimalAnalyzedClassSize().toString()));
        }
        if (!request.getExcludedNamespaces().isEmpty()) {
            schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDED_NAMESPACES, request.getExcludedNamespaces().toString()));
        }
    }

    protected void setLocalNameAndNamespace(@Nonnull String fullName, @Nonnull SchemaElement entity) {
        String localName = fullName;
        String namespace = "";

        int localNameIndex = fullName.lastIndexOf("#");
        if (localNameIndex == -1) {
            localNameIndex = fullName.lastIndexOf("/");
        }
        if (localNameIndex != -1 && localNameIndex < fullName.length()) {
            localName = fullName.substring(localNameIndex + 1);
            namespace = fullName.substring(0, localNameIndex + 1);
        }

        entity.setLocalName(localName);
        entity.setFullName(fullName);
        entity.setNamespace(namespace);
    }

    protected boolean isDuplicatePair(@Nonnull List<ClassPair> pairs, @Nullable String domain, @Nullable String range) {
        return pairs.stream().anyMatch(p -> p != null
                && ((p.getSourceClass() == null && domain == null) || (p.getSourceClass() != null && p.getSourceClass().equals(domain)))
                && ((p.getTargetClass() == null && range == null) || (p.getTargetClass() != null && p.getTargetClass().equals(range)))
        );
    }

    protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request) {
        QueryResponse queryResponse;
        for (SchemaClass classA : classes) {
            String query = SchemaExtractorQueries.FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS.getSparqlQuery()
                    .replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, classA.getFullName());
            queryResponse = sparqlEndpointProcessor.read(request, FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS.name(), query, true);
            if (!queryResponse.getResults().isEmpty()) {
                updateGraphOfClassesWithNeighbors(classA.getFullName(), queryResponse.getResults(), graphOfClasses, request);
                queryResponse.getResults().clear();
            }
            if (queryResponse.hasErrors()) {
                classes.forEach(classB -> {
                    if (!classA.getFullName().equalsIgnoreCase(classB.getFullName())
                            && isNotExcludedResource(classB.getFullName(), request.getExcludedNamespaces())
                            && (isNotFalse(classA.getPropertiesInSchema()) || isNotFalse(classB.getPropertiesInSchema()))
                    ) {
                        String checkQuery = CHECK_CLASS_INTERSECTION.getSparqlQuery()
                                .replace(SPARQL_QUERY_BINDING_NAME_CLASS_A, classA.getFullName())
                                .replace(SPARQL_QUERY_BINDING_NAME_CLASS_B, classB.getFullName());
                        QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, CHECK_CLASS_INTERSECTION.name(), checkQuery, false);
                        if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                            if (graphOfClasses.containsKey(classA.getFullName())) {
                                graphOfClasses.get(classA.getFullName()).getNeighbors().add(classB.getFullName());
                            }
                        }
                    }
                });
            }
        }
    }

    protected void updateGraphOfClassesWithNeighbors(@Nonnull String domainClass, @Nonnull List<QueryResult> queryResults, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                     @Nonnull SchemaExtractorRequestDto request) {
        Set<String> includedClasses = request.getIncludedClasses().stream().map(SchemaExtractorRequestedClassDto::getClassName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {
            String classB = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B);
            if (StringUtils.isNotEmpty(classB) && isNotExcludedResource(classB, request.getExcludedNamespaces()) && (includedClasses.isEmpty() || includedClasses.contains(classB))) {
                if (graphOfClasses.containsKey(domainClass)) {
                    graphOfClasses.get(domainClass).getNeighbors().add(classB);
                }
            }
        }
    }

    @Nonnull
    protected Map<String, SchemaExtractorClassNodeInfo> sortGraphOfClassesByNeighborsSizeAsc(@Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses) {
        // sort classes by neighbors size (ascending)
        return graphOfClasses.entrySet().stream()
                .sorted((o1, o2) -> {
                    Integer o1Size = o1.getValue().getNeighbors().size();
                    Integer o2Size = o2.getValue().getNeighbors().size();
                    int compareResult = o1Size.compareTo(o2Size);
                    if (compareResult != 0) {
                        return compareResult;
                    }
                    compareResult = o1.getValue().getTripleCount().compareTo(o2.getValue().getTripleCount());
                    if (compareResult != 0) {
                        return compareResult;
                    } else {
                        return nullSafeStringComparator.compare(o1.getValue().getClassName(), o2.getValue().getClassName());
                    }
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    @Nonnull
    protected List<String> sortNeighborsByTripleCountAsc(List<String> neighbors, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
        // sort class neighbors by triple count (ascending)
        return neighbors.stream()
                .sorted((o1, o2) -> {
                    Long neighborInstances1 = classesGraph.get(o1).getTripleCount();
                    Long neighborInstances2 = classesGraph.get(o2).getTripleCount();
                    int compareResult = neighborInstances1.compareTo(neighborInstances2);
                    if (compareResult == 0) {
                        return nullSafeStringComparator.compare(classesGraph.get(o1).getClassName(), classesGraph.get(o2).getClassName());
                    } else {
                        return compareResult;
                    }
                })
                .collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaClass> sortClassesByTripleCountDesc(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
        // sort classes by triple count (descending)
        return classes.stream()
                .sorted((o1, o2) -> {
                    Long neighborInstances1 = classesGraph.get(o1.getFullName()).getTripleCount();
                    Long neighborInstances2 = classesGraph.get(o2.getFullName()).getTripleCount();
                    int compareResult = neighborInstances2.compareTo(neighborInstances1);
                    if (compareResult == 0) {
                        return nullSafeStringComparator.compare(o2.getFullName(), o1.getFullName());
                    } else {
                        return compareResult;
                    }
                })
                .collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> sortPropertyLinkedClassesByTripleCount(@Nonnull List<SchemaExtractorPropertyLinkedClassInfo> propertyLinkedClasses) {
        // sort property classes by triple count (descending) and then by total class triple count (ascending)
        return propertyLinkedClasses.stream()
                .sorted((o1, o2) -> {
                    int compareResult = o2.getPropertyTripleCount().compareTo(o1.getPropertyTripleCount());
                    if (compareResult == 0) {
                        return o1.getClassTotalTripleCount().compareTo(o2.getClassTotalTripleCount());
                    } else {
                        return compareResult;
                    }
                })
                .collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorPropertyRelatedPropertyInfo> sortPropertyLinkedPropertiesByTripleCount(@Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> propertyRelatedProperties) {
        // sort properties by triple count (descending) and then by property name (natural)
        return propertyRelatedProperties.stream()
                .sorted((o1, o2) -> {
                    int compareResult = o2.getTripleCount().compareTo(o1.getTripleCount());
                    if (compareResult == 0) {
                        return o1.getPropertyName().compareTo(o2.getPropertyName());
                    } else {
                        return compareResult;
                    }
                })
                .collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaPropertyLinkedClassDetails> sortPropertyLinkedClassesByImportanceIndexAndTripleCount(@Nonnull List<SchemaPropertyLinkedClassDetails> propertyLinkedClasses) {
        // sort property classes by importance index (ascending) leaving 0 as last and then by triple count (descending)
        return propertyLinkedClasses.stream()
                .sorted((o1, o2) -> {
                    int indexA = Optional.ofNullable(o1.getImportanceIndex()).orElse(0);
                    int indexB = Optional.ofNullable(o2.getImportanceIndex()).orElse(0);
                    if (indexA == indexB)
                        return Optional.ofNullable(o2.getTripleCount()).orElse(0L).compareTo(Optional.ofNullable(o1.getTripleCount()).orElse(0L));
                    if (indexA == 0) return 1;
                    if (indexB == 0) return -1;
                    return Integer.compare(indexA, indexB);
                })
                .collect(Collectors.toList());
    }

    protected void processSuperclasses(@Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
                                       @Nonnull SchemaExtractorRequestDto request) {

        for (Map.Entry<String, SchemaExtractorClassNodeInfo> entry : classesGraph.entrySet()) {

            SchemaClass currentClass = findClass(classes, entry.getKey());
            if (currentClass == null || THING_NAME.equals(currentClass.getLocalName())) {
                continue;
            }

            // sort neighbor list by triple count (ascending)
            List<String> neighbors = sortNeighborsByTripleCountAsc(entry.getValue().getNeighbors(), classesGraph);

            // find the class with the smallest number of instances but including all current instances
            findSuperClass(currentClass, entry.getValue(), neighbors, classesGraph, classes, request);

        }
    }

    protected SchemaClass findClass(@Nonnull List<SchemaClass> classes, @Nullable String className) {
        return classes.stream().filter(
                        schemaClass -> schemaClass.getFullName().equals(className) || schemaClass.getLocalName().equals(className))
                .findFirst().orElse(null);
    }

    protected void findSuperClass(@Nonnull SchemaClass currentClass, @Nonnull SchemaExtractorClassNodeInfo currentClassInfo, @Nonnull List<String> neighbors,
                                  @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, List<SchemaClass> classes,
                                  @Nonnull SchemaExtractorRequestDto request) {

        for (String neighbor : neighbors) {
            Long neighborInstances = classesGraph.get(neighbor).getTripleCount();
            if (neighborInstances < currentClassInfo.getTripleCount() || neighborInstances < request.getMinimalAnalyzedClassSize()) {
                continue;
            }
            String query = SchemaExtractorQueries.CHECK_SUPERCLASS.getSparqlQuery();
            query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, currentClass.getFullName());
            query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B, neighbor);
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.CHECK_SUPERCLASS.name(), query);
            if (!queryResults.isEmpty()) {
                continue;
            }
            SchemaClass superClass = findClass(classes, neighbor);
            if (!hasCyclicDependency(currentClass, superClass, classes)) {
                currentClass.getSuperClasses().add(neighbor);
                if (superClass != null) {
                    superClass.getSubClasses().add(currentClass.getFullName());
                }
                break;
            }
        }
    }

    protected boolean hasCyclicDependency(@Nonnull SchemaClass currentClass, @Nullable SchemaClass newClass, @Nonnull List<SchemaClass> classes) {
        if (newClass == null) {
            return false;
        }
        if (currentClass.getSuperClasses().contains(newClass.getFullName()) || currentClass.getSubClasses().contains(newClass.getFullName())) {
            return true;
        }
        return isClassAccessibleFromSuperclasses(newClass.getFullName(), currentClass.getFullName(), classes);
    }

    protected boolean isClassAccessibleFromSuperclasses(@Nonnull String currentClass, @Nonnull String neighbor, @Nonnull List<SchemaClass> classes) {
        if (currentClass.equals(neighbor)) {
            return true;
        }
        boolean accessible = false;
        SchemaClass current = findClass(classes, currentClass);
        for (String superClass : current.getSuperClasses()) {
            accessible = isClassAccessibleFromSuperclasses(superClass, neighbor, classes);
            if (accessible) {
                break;
            }
        }
        return accessible;
    }

    protected boolean isClassAccessibleFromSubclasses(@Nonnull String currentClass, @Nonnull String neighbor, @Nonnull List<SchemaClass> classes) {
        if (currentClass.equals(neighbor)) {
            return true;
        }
        boolean accessible = false;
        SchemaClass current = findClass(classes, currentClass);
        for (String subClass : current.getSubClasses()) {
            accessible = isClassAccessibleFromSubclasses(subClass, neighbor, classes);
            if (accessible) {
                break;
            }
        }
        return accessible;
    }

    // validate if all links are accessible
    // 1. order classes by instance count (descending)
    // 2. start validation with class X having max instance count
    // 3. check if all links are accessible from X
    // 4. if yes - OK, do nothing
    // 5. if no - add link to the next class which:
    //		a. has less instances
    //		b. includes all instances from X
    //		c. is not accessible from X
    // 6. repeat validation
    protected void updateMultipleInheritanceSuperclasses(@Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
                                                         @Nonnull SchemaExtractorRequestDto request) {

        List<SchemaClass> sortedClasses = sortClassesByTripleCountDesc(classes, classesGraph);

        for (SchemaClass currentClass : sortedClasses) {

            // 1. exclude THING class
            if (THING_NAME.equals(currentClass.getLocalName()) || currentClass.getSuperClasses().isEmpty()) {
                continue;
            }
            if (currentClass.getSuperClasses().size() == 1) {
                SchemaClass superClass = findClass(classes, currentClass.getSuperClasses().stream().findFirst().orElse(null));
                if (THING_NAME.equals(superClass.getLocalName())) {
                    continue;
                }
            }

            // 2. one of the neighbors is THING, so no need to perform additional validation
            // because correct assignment was selected in processSuperclasses method
            SchemaExtractorClassNodeInfo classInfo = classesGraph.get(currentClass.getFullName());
            if (classInfo.getNeighbors().size() <= 2) {
                continue;
            }

            // 3. validate whether all neighbors are accessible
            int maxCounter = classInfo.getNeighbors().size() + 1;
            boolean accessible = validateAllNeighbors(currentClass, classInfo, classes, classesGraph, request);
            while (!accessible && maxCounter != 0) {
                maxCounter--;
                accessible = validateAllNeighbors(currentClass, classInfo, classes, classesGraph, request);
            }
        }
    }

    protected boolean validateAllNeighbors(@Nonnull SchemaClass currentClass, @Nonnull SchemaExtractorClassNodeInfo currentClassInfo, @Nonnull List<SchemaClass> classes,
                                           @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull SchemaExtractorRequestDto request) {
        boolean accessible = true;
        List<String> notAccessibleNeighbors = new ArrayList<>();
        for (String neighbor : currentClassInfo.getNeighbors()) {
            SchemaClass neighborClass = findClass(classes, neighbor);
            if (THING_NAME.equals(neighborClass.getLocalName())) {
                continue;
            }
            boolean isAccessibleSuperClass = isClassAccessibleFromSuperclasses(currentClass.getFullName(), neighbor, classes);
            boolean isAccessibleSubClass = isClassAccessibleFromSubclasses(currentClass.getFullName(), neighbor, classes);
            if (!isAccessibleSuperClass && !isAccessibleSubClass) {
                accessible = false;
                notAccessibleNeighbors.add(neighbor);
            }
        }
        if (!accessible) {
            List<String> sortedNeighbors = sortNeighborsByTripleCountAsc(notAccessibleNeighbors, classesGraph);
            findSuperClass(currentClass, currentClassInfo, sortedNeighbors, classesGraph, classes, request);
        }

        return accessible;
    }

    protected boolean isNotExcludedResource(@Nullable String resourceId, @Nonnull List<String> excludedResources) {
        return StringUtils.isNotEmpty(resourceId) && excludedResources.stream().noneMatch(resourceId::startsWith);
    }

    @Nonnull
    protected List<SchemaExtractorDomainRangeInfo> findPairsWithSpecificDomain(@Nonnull List<SchemaExtractorDomainRangeInfo> allPairs, @Nonnull String domainClass) {
        return allPairs.stream().filter(pair -> domainClass.equalsIgnoreCase(pair.getDomainClass())).collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorDomainRangeInfo> findPairsWithSpecificRange(@Nonnull List<SchemaExtractorDomainRangeInfo> allPairs, @Nonnull String rangeClass) {
        return allPairs.stream().filter(pair -> rangeClass.equalsIgnoreCase(pair.getRangeClass())).collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyLinkedClasses(@Nonnull List<SchemaExtractorClassNodeInfo> propertyLinkedClasses, @Nonnull List<SchemaClass> classes) {
        // prepare data - classes with property triple count and total class instance count
        List<SchemaExtractorPropertyLinkedClassInfo> classesForProcessing = new ArrayList<>();
        propertyLinkedClasses.forEach(linkedClass -> {
            SchemaClass schemaClass = findClass(classes, linkedClass.getClassName());
            if (schemaClass != null) {
                SchemaExtractorPropertyLinkedClassInfo newItem = new SchemaExtractorPropertyLinkedClassInfo();
                newItem.setClassName(linkedClass.getClassName());
                newItem.setClassTotalTripleCount(schemaClass.getInstanceCount());
                newItem.setPropertyTripleCount(linkedClass.getTripleCount());
                classesForProcessing.add(newItem);
            }
        });

        // sort property classes by triple count (descending) and then by total class triple count (ascending)
        return sortPropertyLinkedClassesByTripleCount(classesForProcessing);
    }

    protected void mapPrincipalClasses(@Nonnull List<SchemaExtractorClassNodeInfo> propertyLinkedClasses, @Nonnull List<SchemaExtractorPropertyLinkedClassInfo> principalClasses) {
        propertyLinkedClasses.forEach(linkedClass -> {
            SchemaExtractorPropertyLinkedClassInfo classInfo = principalClasses.stream()
                    .filter(c -> c.getClassName().equalsIgnoreCase(linkedClass.getClassName())).findFirst().orElse(null);
            linkedClass.setImportanceIndex((classInfo != null) ? classInfo.getImportanceIndex() : 0);
        });
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyPairRanges(@Nonnull List<SchemaExtractorDomainRangeInfo> propertyLinkedPairs, @Nonnull List<SchemaClass> classes) {
        List<SchemaExtractorPropertyLinkedClassInfo> classesForProcessing = new ArrayList<>();
        propertyLinkedPairs.forEach(linkedPair -> {
            SchemaClass schemaClass = findClass(classes, linkedPair.getRangeClass());
            if (schemaClass != null) {
                SchemaExtractorPropertyLinkedClassInfo newItem = new SchemaExtractorPropertyLinkedClassInfo();
                newItem.setClassName(linkedPair.getRangeClass());
                newItem.setClassTotalTripleCount(schemaClass.getInstanceCount());
                newItem.setPropertyTripleCount(linkedPair.getTripleCount());
                classesForProcessing.add(newItem);
            }
        });
        // sort property classes by triple count (descending) and then by total class triple count (ascending)
        return sortPropertyLinkedClassesByTripleCount(classesForProcessing);
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyPairDomains(@Nonnull List<SchemaExtractorDomainRangeInfo> propertyLinkedPairs, @Nonnull List<SchemaClass> classes) {
        List<SchemaExtractorPropertyLinkedClassInfo> classesForProcessing = new ArrayList<>();
        propertyLinkedPairs.forEach(linkedPair -> {
            SchemaClass schemaClass = findClass(classes, linkedPair.getDomainClass());
            if (schemaClass != null) {
                SchemaExtractorPropertyLinkedClassInfo newItem = new SchemaExtractorPropertyLinkedClassInfo();
                newItem.setClassName(linkedPair.getDomainClass());
                newItem.setClassTotalTripleCount(schemaClass.getInstanceCount());
                newItem.setPropertyTripleCount(linkedPair.getTripleCount());
                classesForProcessing.add(newItem);
            }
        });
        // sort property classes by triple count (descending) and then by total class triple count (ascending)
        return sortPropertyLinkedClassesByTripleCount(classesForProcessing);
    }

    protected void buildLabels(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        request.getIncludedLabels().forEach(label -> {
            log.info(request.getCorrelationId() + " - findLabelsForClasses");
            buildLabelsForSchemaElements(request, label, schema.getClasses());
            log.info(request.getCorrelationId() + " - findLabelsForProperties");
            buildLabelsForSchemaElements(request, label, schema.getProperties());
        });
    }

    protected void buildLabelsForSchemaElements(@Nonnull SchemaExtractorRequestDto request, @Nonnull SchemaExtractorRequestedLabelDto label,
                                                @Nonnull List<? extends SchemaElement> elements) {
        elements.forEach(element -> {
            String query;
            QueryResponse queryResponse;
            if (label.getLanguages().isEmpty()) {
                query = FIND_LABEL.getSparqlQuery()
                        .replace(SPARQL_QUERY_BINDING_NAME_RESOURCE, element.getFullName())
                        .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, label.getLabelProperty());
                queryResponse = sparqlEndpointProcessor.read(request, FIND_LABEL.name(), query, false);
            } else {
                query = FIND_LABEL_WITH_LANG.getSparqlQuery()
                        .replace(SPARQL_QUERY_BINDING_NAME_RESOURCE, element.getFullName())
                        .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, label.getLabelProperty())
                        .replace(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildFilterWithLanguages(label.getLanguages()));
                queryResponse = sparqlEndpointProcessor.read(request, FIND_LABEL_WITH_LANG.name(), query, false);
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String value = queryResult.get(SPARQL_QUERY_BINDING_NAME_VALUE);
                String language = queryResult.get(SPARQL_QUERY_BINDING_NAME_LANGUAGE);
                if (StringUtils.isNotEmpty(value)) {
                    if (StringUtils.isNotEmpty(language)) {
                        element.getLabels().add(new Label(label.getLabelProperty(), value, language));
                    } else {
                        element.getLabels().add(new Label(label.getLabelProperty(), value));
                    }
                }
            }
        });
    }

    protected String buildFilterWithLanguages(@Nonnull List<String> languages) {
        StringBuilder customFilter = new StringBuilder("lang(?z) = ''");
        languages.forEach(lang -> {
            customFilter.
                    append(" || lang(?z) = '")
                    .append(lang)
                    .append("'");
        });
        return customFilter.toString();
    }

}

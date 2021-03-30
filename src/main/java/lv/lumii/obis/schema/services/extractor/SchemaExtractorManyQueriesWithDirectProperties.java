package lv.lumii.obis.schema.services.extractor;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.extractor.dto.*;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.constants.SchemaConstants.DEFAULT_MIN_CARDINALITY;
import static lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

@Slf4j
@Service
public class SchemaExtractorManyQueriesWithDirectProperties extends SchemaExtractor {

    @Override
    @Nonnull
    public Schema extractSchema(@Nonnull SchemaExtractorRequest request) {
        Schema schema = initializeSchema(request);
        buildClasses(request, schema);
        buildProperties(request, schema);
        buildNamespaceMap(request, schema);
        return schema;
    }

    @Override
    protected void buildClasses(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema) {
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLASSES_WITH_INSTANCE_COUNT);
        List<SchemaClass> classes = processClasses(queryResults, request);
        log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        schema.setClasses(classes);
    }

    protected void buildProperties(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema) {

        // find all properties with instances count
        log.info(request.getCorrelationId() + " - findAllProperties");
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_ALL_PROPERTIES);
        Map<String, SchemaExtractorPropertyNodeInfo> properties = processProperties(queryResults);
        log.info(request.getCorrelationId() + String.format(" - found %d properties", properties.size()));

        // fill properties with additional data
        enrichProperties(properties, request);

        // fill schema object with attributes and roles
        formatProperties(properties, schema);
    }

    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processProperties(@Nonnull List<QueryResult> queryResults) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        for (QueryResult queryResult : queryResults) {

            String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

            if (!properties.containsKey(propertyName)) {
                properties.put(propertyName, new SchemaExtractorPropertyNodeInfo());
            }
            SchemaExtractorPropertyNodeInfo property = properties.get(propertyName);
            property.setPropertyName(propertyName);
            property.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));

        }
        return properties;
    }

    protected void enrichProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequest request) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            // calculate property domains with instance count
            determinePropertyDomains(property, request);

            // determine whether it is a dataType or objectType property
            determinePropertyType(property, request);

            // calculate property ranges with instance count
            if (isTrue(property.getIsObjectProperty())) {
                determinePropertyRanges(property, request);
                determinePropertyDomainRangePairs(property, request);
                determinePropertyClosedDomainsAndRanges(property, request);
            }
        }

        // find data types for attributes
        if (!SchemaExtractorRequest.ExtractionMode.excludeDataTypesAndCardinalities.equals(request.getMode())) {
            // find data types for attributes
            log.info(request.getCorrelationId() + " - findDataTypesForDataTypeProperties");
            processDataTypes(properties, request);
        }
        // find min/max cardinality
        if (SchemaExtractorRequest.ExtractionMode.full.equals(request.getMode())) {
            log.info(request.getCorrelationId() + " - calculateCardinalityForProperties");
            processCardinalities(properties, request);
        }
    }

    protected void determinePropertyType(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyType [" + property.getPropertyName() + "]");

        // get total count of all property values
        String queryForTotalCount = COUNT_PROPERTY_ALL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResultsForTotalCount = sparqlEndpointProcessor.read(request, COUNT_PROPERTY_ALL_VALUES.name(), queryForTotalCount);
        if (queryResultsForTotalCount.isEmpty() || queryResultsForTotalCount.get(0) == null) {
            return;
        }

        // get count of URL values for the specific property
        String queryForUrlCount = COUNT_PROPERTY_URL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResultsForUrlCount = sparqlEndpointProcessor.read(request, COUNT_PROPERTY_URL_VALUES.name(), queryForUrlCount);
        if (queryResultsForUrlCount.isEmpty() || queryResultsForUrlCount.get(0) == null) {
            return;
        }

        Long instancesCountForTotal = SchemaUtil.getLongValueFromString(queryResultsForTotalCount.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        Long instancesCountForUrl = SchemaUtil.getLongValueFromString(queryResultsForUrlCount.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        property.setObjectTripleCount(instancesCountForUrl);
        if (instancesCountForTotal.equals(0L)) {
            return;
        }
        if (instancesCountForTotal.equals(instancesCountForUrl)) {
            property.setIsObjectProperty(Boolean.TRUE);
        } else {
            property.setIsObjectProperty(Boolean.FALSE);
        }

        // get count of URL values for the specific property and specific domain
        if (instancesCountForUrl > 0) {
            property.getDomainClasses().forEach(domainClass -> {
                String query = SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.getSparqlQuery().
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
                List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.name(), query);
                if (queryResults.isEmpty() || queryResults.get(0) == null) {
                    domainClass.setObjectTripleCount(0L);
                } else {
                    Long urlInstancesCountForDomain = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    domainClass.setObjectTripleCount(urlInstancesCountForDomain);
                }
            });
        }
    }

    protected void determinePropertyDomains(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomains [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT.name(), query);

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (StringUtils.isNotEmpty(className)) {
                property.getDomainClasses().add(new SchemaExtractorClassNodeInfo(className));
            }
        }

        log.info(request.getCorrelationId() + " - determinePropertyDomainsInstanceCount [" + property.getPropertyName() + "]");
        property.getDomainClasses().forEach(domainClass -> {
            String queryTripleCounts = FIND_PROPERTY_DOMAINS_TRIPLE_COUNT.getSparqlQuery()
                    .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .replace(SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
            List<QueryResult> queryTripleCountsResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS_TRIPLE_COUNT.name(), queryTripleCounts);
            for (QueryResult queryResult : queryTripleCountsResults) {
                if (queryResult != null) {
                    domainClass.setInstanceCount(SchemaUtil.getLongValueFromString(queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertyRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyRanges [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_RANGES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_RANGES.name(), query);

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (StringUtils.isNotEmpty(className)) {
                property.getRangeClasses().add(new SchemaExtractorClassNodeInfo(className, SchemaUtil.getLongValueFromString(instancesCountStr)));
            }
        }
    }

    protected void determinePropertyDomainRangePairs(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairs [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAIN_RANGE_PAIRS.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS.name(), query);

        for (QueryResult queryResult : queryResults) {
            String domainClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN);
            String rangeClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_RANGE);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (StringUtils.isNotEmpty(domainClass) && StringUtils.isNotEmpty(rangeClass)) {
                property.getDomainRangePairs().add(new SchemaExtractorDomainRangeInfo(domainClass, rangeClass, SchemaUtil.getLongValueFromString(instancesCountStr)));
            }
        }
        // endpoint was not able to return class pairs with direct query, so trying to match each domain and range class combination
        if (property.getDomainRangePairs().isEmpty()) {
            log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairsForEachDomainAndRangeCombination [" + property.getPropertyName() + "]");

            property.getDomainClasses().forEach(domainClass -> {
                property.getRangeClasses().forEach(rangeClass -> {
                    String queryForPairMatch = FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES.getSparqlQuery()
                            .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .replace(SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName())
                            .replace(SPARQL_QUERY_BINDING_NAME_CLASS_RANGE, rangeClass.getClassName());
                    List<QueryResult> queryResultsForPairMatch = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES.name(), queryForPairMatch);
                    if (!queryResultsForPairMatch.isEmpty() && queryResultsForPairMatch.get(0) != null) {
                        Long instancesCountForPair = SchemaUtil.getLongValueFromString(queryResultsForPairMatch.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        if (instancesCountForPair > 0) {
                            property.getDomainRangePairs().add(new SchemaExtractorDomainRangeInfo(domainClass.getClassName(), rangeClass.getClassName(), instancesCountForPair));
                        }
                    }
                });
            });
        }
    }

    protected void determinePropertyClosedDomainsAndRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomains [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class
        String query = FIND_CLOSED_DOMAIN_FOR_PROPERTY.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_DOMAIN_FOR_PROPERTY.name(), query);
        if (!queryResults.isEmpty() && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_X))) {
            property.setIsClosedDomain(Boolean.FALSE);
        } else {
            property.setIsClosedDomain(Boolean.TRUE);
        }

        // check if there is any triple with URI object but without bound class
        query = FIND_CLOSED_RANGE_FOR_PROPERTY.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_RANGE_FOR_PROPERTY.name(), query);
        if (!queryResults.isEmpty()
                && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_X))
                && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_Y))) {
            property.setIsClosedRange(Boolean.FALSE);
        } else {
            property.setIsClosedRange(Boolean.TRUE);
        }

    }

    @Override
    protected void setMinCardinality(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        property.getDomainClasses().forEach(domainClass -> {
            String query = SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domainClass.getClassName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.name(), query);
            if (!queryResults.isEmpty()) {
                domainClass.setMinCardinality(DEFAULT_MIN_CARDINALITY);
            } else {
                domainClass.setMinCardinality(1);
            }
        });
    }

    @Override
    protected void setMaxCardinality(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        String query = SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.name(), query);
        if (queryResults.isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
    }

    @Override
    protected void processDataTypes(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequest request) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            if (BooleanUtils.isTrue(p.getValue().getIsObjectProperty())) {
                continue;
            }
            SchemaExtractorPropertyNodeInfo property = p.getValue();

            // find data types
            String query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, p.getKey());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.name(), query);
            for (QueryResult queryResult : queryResults) {
                String resultDataType = queryResult.get(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (StringUtils.isNotEmpty(resultDataType)) {
                    property.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount));
                }
            }

            // find language tag
            query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, p.getKey());
            queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING.name(), query);
            if (!queryResults.isEmpty()) {
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (tripleCount > 0L) {
                    property.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount));
                }
            }

        }
    }

    protected void formatProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            if (isTrue(p.getValue().getIsObjectProperty())) {
                schema.getAssociations().add(createSchemaRole(p.getKey(), p.getValue()));
            } else {
                schema.getAttributes().add(createSchemaAttribute(p.getKey(), p.getValue()));
            }
        }
    }

    protected SchemaAttribute createSchemaAttribute(@Nonnull String propertyId, @Nonnull SchemaExtractorPropertyNodeInfo property) {
        SchemaAttribute attribute = new SchemaAttribute();
        SchemaUtil.setLocalNameAndNamespace(propertyId, attribute);
        attribute.setMaxCardinality(property.getMaxCardinality());
        attribute.getSourceClasses().addAll(property.getDomainClasses().stream().map(SchemaExtractorClassNodeInfo::getClassName).collect(Collectors.toSet()));
        attribute.getSourceClassesDetailed().addAll(convertInternalDtoToApiDto(property.getDomainClasses()));
        attribute.getDataTypes().addAll(convertInternalDataTypesToApiDto(property.getDataTypes()));
        attribute.setTripleCount(property.getInstanceCount());
        attribute.setObjectTripleCount(property.getObjectTripleCount());
        return attribute;
    }

    protected SchemaRole createSchemaRole(@Nonnull String propertyId, @Nonnull SchemaExtractorPropertyNodeInfo property) {
        SchemaRole role = new SchemaRole();
        SchemaUtil.setLocalNameAndNamespace(propertyId, role);
        role.setMaxCardinality(property.getMaxCardinality());
        role.setTripleCount(property.getInstanceCount());
        role.setObjectTripleCount(property.getObjectTripleCount());
        role.setClosedDomain(property.getIsClosedDomain());
        role.setClosedRange(property.getIsClosedRange());
        role.getSourceClassesDetailed().addAll(convertInternalDtoToApiDto(property.getDomainClasses()));
        role.getTargetClassesDetailed().addAll(convertInternalDtoToApiDto(property.getRangeClasses()));
        property.getDomainRangePairs().forEach(pair -> {
            if (isFalse(isDuplicatePair(role.getClassPairs(), pair.getDomainClass(), pair.getRangeClass()))) {
                role.getClassPairs().add(new ClassPair(pair.getDomainClass(), pair.getRangeClass(), pair.getInstanceCount()));
            }
        });
        return role;
    }

    protected Set<SchemaPropertyLinkedClassDetails> convertInternalDtoToApiDto(@Nonnull List<SchemaExtractorClassNodeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new SchemaPropertyLinkedClassDetails(
                        internalDto.getClassName(), internalDto.getInstanceCount(), internalDto.getObjectTripleCount(), internalDto.getMinCardinality())).
                        collect(Collectors.toSet());
    }

    protected List<SchemaAttributeDataType> convertInternalDataTypesToApiDto(@Nonnull List<SchemaExtractorDataTypeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new SchemaAttributeDataType(
                        internalDto.getDataType(), internalDto.getTripleCount())).
                        collect(Collectors.toList());
    }

    @Override
    protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequest request) {
        // do nothing
    }

    @Override
    protected Map<String, SchemaExtractorPropertyNodeInfo> findAllDataTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request) {
        return null;
    }

    @Override
    protected Map<String, SchemaExtractorPropertyNodeInfo> findAllObjectTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request) {
        return null;
    }
}

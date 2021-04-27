package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v2.*;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries;
import lv.lumii.obis.schema.services.extractor.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
@Service
public class SchemaExtractor {

    @Autowired
    @Setter
    @Getter
    protected SparqlEndpointProcessor sparqlEndpointProcessor;

    @Nonnull
    public Schema extractSchema(@Nonnull SchemaExtractorRequest request) {
        Schema schema = initializeSchema(request);
        buildClasses(request, schema);
        buildProperties(request, schema);
        buildNamespaceMap(schema);
        return schema;
    }

    protected Schema initializeSchema(@Nonnull SchemaExtractorRequest request) {
        Schema schema = new Schema();
        schema.setName((StringUtils.isNotEmpty(request.getGraphName())) ? request.getGraphName() + "_Schema" : "Schema");
        buildExtractionProperties(request, schema);
        return schema;
    }

    protected void buildClasses(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema) {
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLASSES_WITH_INSTANCE_COUNT);
        List<SchemaClass> classes = processClasses(queryResults, request);
        log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        schema.setClasses(classes);
    }

    protected List<SchemaClass> processClasses(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequest request) {
        List<SchemaClass> classes = new ArrayList<>();

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

            if (StringUtils.isNotEmpty(className)) {
                SchemaClass classEntry = new SchemaClass();
                setLocalNameAndNamespace(className, classEntry);
                classEntry.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));
                classes.add(classEntry);
            }
        }
        return classes;
    }

    protected void buildProperties(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema) {

        // find all properties with triple count
        log.info(request.getCorrelationId() + " - findAllPropertiesWithTripleCount");
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
            property.setTripleCount(SchemaUtil.getLongValueFromString(instancesCountStr));
        }
        return properties;
    }

    protected void enrichProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequest request) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            determinePropertyObjectTripleCount(property, request);
            determinePropertyType(property, request);

            determinePropertyDomains(property, request);
            determinePropertyDomainTripleCount(property, request);
            determinePropertyDomainObjectTripleCount(property, request);

            if (property.getObjectTripleCount() > 0) {
                determinePropertyRanges(property, request);
                determinePropertyRangeTripleCount(property, request);
                determinePropertyDomainRangePairs(property, request);
                determinePropertyClosedDomainsAndRanges(property, request);
            }

            if (isFalse(property.getIsObjectProperty())
                    && !SchemaExtractorRequest.ExtractionMode.excludeDataTypesAndCardinalities.equals(request.getMode())) {
                determinePropertyDataTypes(property, request);
            }

            determinePrincipalDomainsAndTargets(property, request);

            if (SchemaExtractorRequest.ExtractionMode.full.equals(request.getMode())) {
                determinePropertyMaxCardinality(property, request);
                determinePropertyDomainsMinCardinality(property, request);
            }
        }
    }

    protected void determinePropertyObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyObjectTripleCount [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property
        Long objectTripleCount = 0L;
        String queryForUrlCount = COUNT_PROPERTY_URL_VALUES.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResultsForUrlCount = sparqlEndpointProcessor.read(request, COUNT_PROPERTY_URL_VALUES.name(), queryForUrlCount);
        if (!queryResultsForUrlCount.isEmpty() && queryResultsForUrlCount.get(0) != null) {
            objectTripleCount = SchemaUtil.getLongValueFromString(queryResultsForUrlCount.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        }
        property.setObjectTripleCount(objectTripleCount);
    }

    protected void determinePropertyType(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyType [" + property.getPropertyName() + "]");

        if (property.getTripleCount().equals(property.getObjectTripleCount())) {
            property.setIsObjectProperty(Boolean.TRUE);
        } else {
            property.setIsObjectProperty(Boolean.FALSE);
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
    }

    protected void determinePropertyDomainTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
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

    protected void determinePropertyDomainObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determineObjectTripleCountForAllPropertyDomains [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property and specific domain
        property.getDomainClasses().forEach(domainClass -> {
            if (property.getObjectTripleCount() <= 0) {
                domainClass.setObjectTripleCount(0L);
            } else {
                String query = SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.getSparqlQuery().
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getClassName());
                List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.name(), query);
                if (queryResults.isEmpty() || queryResults.get(0) == null) {
                    domainClass.setObjectTripleCount(0L);
                } else {
                    Long objectTripleCountForDomain = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    domainClass.setObjectTripleCount(objectTripleCountForDomain);
                }
            }
        });
    }

    protected void determinePropertyRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyRanges [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT.name(), query);

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (StringUtils.isNotEmpty(className)) {
                property.getRangeClasses().add(new SchemaExtractorClassNodeInfo(className));
            }
        }
    }

    protected void determinePropertyRangeTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
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

    protected void determinePropertyDomainRangePairs(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairs [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAIN_RANGE_PAIRS.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS.name(), query);

        for (QueryResult queryResult : queryResults) {
            String domainClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN);
            String rangeClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_RANGE);
            String tripleCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (StringUtils.isNotEmpty(domainClass) && StringUtils.isNotEmpty(rangeClass)) {
                property.getDomainRangePairs().add(new SchemaExtractorDomainRangeInfo(domainClass, rangeClass, SchemaUtil.getLongValueFromString(tripleCountStr)));
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

    protected void determinePropertyClosedDomainsAndRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomainsAndRanges [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class
        if (property.getDomainClasses().isEmpty()) {
            property.setIsClosedDomain(null);
        } else {
            String query = FIND_CLOSED_DOMAIN_FOR_PROPERTY.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_DOMAIN_FOR_PROPERTY.name(), query);
            if (!queryResults.isEmpty() && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_X))) {
                property.setIsClosedDomain(Boolean.FALSE);
            } else {
                property.setIsClosedDomain(Boolean.TRUE);
            }
        }

        // check if there is any triple with URI object but without bound class
        if (property.getRangeClasses().isEmpty()) {
            property.setIsClosedRange(null);
        } else {
            String query = FIND_CLOSED_RANGE_FOR_PROPERTY.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLOSED_RANGE_FOR_PROPERTY.name(), query);
            if (!queryResults.isEmpty()
                    && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_X))
                    && StringUtils.isNotEmpty(queryResults.get(0).get(SPARQL_QUERY_BINDING_NAME_Y))) {
                property.setIsClosedRange(Boolean.FALSE);
            } else {
                property.setIsClosedRange(Boolean.TRUE);
            }
        }

    }

    protected void determinePropertyDataTypes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
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

    protected void determinePropertyDomainsMinCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
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

    protected void determinePropertyMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyMaxCardinality [" + property.getPropertyName() + "]");
        String query = SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.name(), query);
        if (queryResults.isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
    }

    protected void determinePrincipalDomainsAndTargets(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        property.getDomainClasses().forEach(domainClass -> domainClass.setImportanceIndex(Boolean.TRUE));
        property.getRangeClasses().forEach(rangeClass -> rangeClass.setImportanceIndex(Boolean.TRUE));
        property.getDomainRangePairs().forEach(pair -> { pair.setSourceImportanceIndex(Boolean.TRUE); pair.setTargetImportanceIndex(Boolean.TRUE);});
    }

    protected void formatProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo propertyData = p.getValue();

            SchemaProperty property = new SchemaProperty();
            setLocalNameAndNamespace(p.getKey(), property);
            property.setMaxCardinality(propertyData.getMaxCardinality());
            property.setTripleCount(propertyData.getTripleCount());
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

            schema.getProperties().add(property);
        }
    }

    protected Set<SchemaPropertyLinkedClassDetails> convertInternalDtoToApiDto(@Nonnull List<SchemaExtractorClassNodeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new SchemaPropertyLinkedClassDetails(
                        internalDto.getClassName(), internalDto.getTripleCount(), internalDto.getObjectTripleCount(), internalDto.getMinCardinality(), internalDto.getImportanceIndex())).
                        collect(Collectors.toSet());
    }

    protected List<DataType> convertInternalDataTypesToApiDto(@Nonnull List<SchemaExtractorDataTypeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new DataType(
                        internalDto.getDataType(), internalDto.getTripleCount())).
                        collect(Collectors.toList());
    }

    protected void buildNamespaceMap(@Nonnull Schema schema) {
        String mainNamespace = findMainNamespace(schema);
        if (StringUtils.isNotEmpty(mainNamespace)) {
            schema.setDefaultNamespace(mainNamespace);
            schema.getPrefixes().add(new NamespacePrefixEntry(SchemaConstants.DEFAULT_NAMESPACE_PREFIX, mainNamespace));
        }
    }

    @Nullable
    protected String findMainNamespace(@Nonnull Schema schema) {
        List<String> namespaces = new ArrayList<>();
        for (SchemaClass item : schema.getClasses()) {
            namespaces.add(item.getNamespace());
        }
        for (SchemaProperty item : schema.getProperties()) {
            namespaces.add(item.getNamespace());
        }
        if (namespaces.isEmpty()) {
            return null;
        }
        Map<String, Long> namespacesWithCounts = namespaces.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        return namespacesWithCounts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    }

    protected void buildExtractionProperties(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema) {
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_ENDPOINT, request.getEndpointUrl()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_GRAPH_NAME, request.getGraphName()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_MODE, request.getMode().name()));
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

}

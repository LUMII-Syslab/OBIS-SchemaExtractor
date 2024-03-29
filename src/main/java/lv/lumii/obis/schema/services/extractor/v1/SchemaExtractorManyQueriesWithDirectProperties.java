package lv.lumii.obis.schema.services.extractor.v1;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v1.*;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.extractor.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.constants.SchemaConstants.DEFAULT_MIN_CARDINALITY;
import static lv.lumii.obis.schema.services.extractor.v1.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

@Slf4j
@Service
public class SchemaExtractorManyQueriesWithDirectProperties extends SchemaExtractor {

    @Override
    @Nonnull
    public Schema extractSchema(@Nonnull SchemaExtractorRequestDto request) {
        Schema schema = initializeSchema(request);
        buildClasses(request, schema);
        buildProperties(request, schema);
        buildNamespaceMap(schema);
        return schema;
    }

    @Override
    protected void buildClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLASSES_WITH_INSTANCE_COUNT);
        List<SchemaClass> classes = processClasses(queryResults, request);
        log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        schema.setClasses(classes);
    }

    protected void buildProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {

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

    protected void enrichProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequestDto request) {
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
                    && !SchemaExtractorRequestDto.ExtractionMode.excludeDataTypesAndCardinalities.equals(request.getMode())) {
                determinePropertyDataTypes(property, request);
            }

            if (SchemaExtractorRequestDto.ExtractionMode.full.equals(request.getMode())) {
                determinePropertyMaxCardinality(property, request);
                determinePropertyDomainsMinCardinality(property, request);
            }
        }
    }

    protected void determinePropertyObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
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

    protected void determinePropertyType(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyType [" + property.getPropertyName() + "]");

        if (property.getTripleCount().equals(property.getObjectTripleCount())) {
            property.setIsObjectProperty(Boolean.TRUE);
        } else {
            property.setIsObjectProperty(Boolean.FALSE);
        }
    }

    protected void determinePropertyDomains(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomains [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT.name(), query);

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (StringUtils.isNotEmpty(className)) {
                property.getSourceClasses().add(new SchemaExtractorClassNodeInfo(className));
            }
        }
    }

    protected void determinePropertyDomainTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertyDomains [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            String queryTripleCounts = FIND_PROPERTY_DOMAINS_TRIPLE_COUNT.getSparqlQuery()
                    .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .replace(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE, sourceClass.getClassName());
            List<QueryResult> queryTripleCountsResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS_TRIPLE_COUNT.name(), queryTripleCounts);
            for (QueryResult queryResult : queryTripleCountsResults) {
                if (queryResult != null) {
                    sourceClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertyDomainObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineObjectTripleCountForAllPropertyDomains [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property and specific domain
        property.getSourceClasses().forEach(sourceClass -> {
            if (property.getObjectTripleCount() <= 0) {
                sourceClass.setObjectTripleCount(0L);
            } else {
                String query = SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.getSparqlQuery().
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName()).
                        replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE, sourceClass.getClassName());
                List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN.name(), query);
                if (queryResults.isEmpty() || queryResults.get(0) == null) {
                    sourceClass.setObjectTripleCount(0L);
                } else {
                    Long objectTripleCountForDomain = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setObjectTripleCount(objectTripleCountForDomain);
                }
            }
        });
    }

    protected void determinePropertyRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyRanges [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT.name(), query);

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (StringUtils.isNotEmpty(className)) {
                property.getTargetClasses().add(new SchemaExtractorClassNodeInfo(className));
            }
        }
    }

    protected void determinePropertyRangeTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertyRanges [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(rangeClass -> {
            String queryTripleCounts = FIND_PROPERTY_RANGES_TRIPLE_COUNT.getSparqlQuery()
                    .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .replace(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET, rangeClass.getClassName());
            List<QueryResult> queryTripleCountsResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_RANGES_TRIPLE_COUNT.name(), queryTripleCounts);
            for (QueryResult queryResult : queryTripleCountsResults) {
                if (queryResult != null) {
                    rangeClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertyDomainRangePairs(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairs [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAIN_RANGE_PAIRS.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS.name(), query);

        for (QueryResult queryResult : queryResults) {
            String domainClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE);
            String rangeClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_TARGET);
            String tripleCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (StringUtils.isNotEmpty(domainClass) && StringUtils.isNotEmpty(rangeClass)) {
                property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(domainClass, rangeClass, SchemaUtil.getLongValueFromString(tripleCountStr)));
            }
        }
        // endpoint was not able to return class pairs with direct query, so trying to match each domain and range class combination
        if (property.getSourceAndTargetPairs().isEmpty()) {
            log.info(request.getCorrelationId() + " - determinePropertyDomainRangePairsForEachDomainAndRangeCombination [" + property.getPropertyName() + "]");

            property.getSourceClasses().forEach(domainClass -> property.getTargetClasses().forEach(rangeClass -> {
                String queryForPairMatch = FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES.getSparqlQuery()
                        .replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .replace(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE, domainClass.getClassName())
                        .replace(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET, rangeClass.getClassName());
                List<QueryResult> queryResultsForPairMatch = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES.name(), queryForPairMatch);
                if (!queryResultsForPairMatch.isEmpty() && queryResultsForPairMatch.get(0) != null) {
                    Long tripleCountForPair = SchemaUtil.getLongValueFromString(queryResultsForPairMatch.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (tripleCountForPair > 0) {
                        property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(domainClass.getClassName(), rangeClass.getClassName(), tripleCountForPair));
                    }
                }
            }));
        }
    }

    protected void determinePropertyClosedDomainsAndRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomainsAndRanges [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class
        if (property.getSourceClasses().isEmpty()) {
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
        if (property.getTargetClasses().isEmpty()) {
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

    protected void determinePropertyDataTypes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTypes [" + property.getPropertyName() + "]");
        // find data types
        String query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.name(), query);
        for (QueryResult queryResult : queryResults) {
            String resultDataType = queryResult.get(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.get(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(resultDataType)) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount, null));
            }
        }

        // find language tag
        query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE_LANG_STRING.name(), query);
        if (!queryResults.isEmpty()) {
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (tripleCount > 0L) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, null));
            }
        }
    }

    protected void determinePropertyDomainsMinCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomainsMinCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(domainClass -> {
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
        attribute.setTripleCount(property.getTripleCount());
        attribute.setObjectTripleCount(property.getObjectTripleCount());
        attribute.setClosedDomain(property.getIsClosedDomain());
        attribute.setClosedRange(property.getIsClosedRange());
        attribute.getDataTypes().addAll(convertInternalDataTypesToApiDto(property.getDataTypes()));
        attribute.getSourceClasses().addAll(property.getSourceClasses().stream().map(SchemaExtractorClassNodeInfo::getClassName).collect(Collectors.toSet()));
        attribute.getSourceClassesDetailed().addAll(convertInternalDtoToApiDto(property.getSourceClasses()));
        attribute.getTargetClassesDetailed().addAll(convertInternalDtoToApiDto(property.getTargetClasses()));
        property.getSourceAndTargetPairs().forEach(pair -> {
            if (isFalse(isDuplicatePair(attribute.getClassPairs(), pair.getSourceClass(), pair.getTargetClass()))) {
                attribute.getClassPairs().add(new ClassPair(pair.getSourceClass(), pair.getTargetClass(), pair.getTripleCount()));
            }
        });

        return attribute;
    }

    protected SchemaRole createSchemaRole(@Nonnull String propertyId, @Nonnull SchemaExtractorPropertyNodeInfo property) {
        SchemaRole role = new SchemaRole();
        SchemaUtil.setLocalNameAndNamespace(propertyId, role);
        role.setMaxCardinality(property.getMaxCardinality());
        role.setTripleCount(property.getTripleCount());
        role.setObjectTripleCount(property.getObjectTripleCount());
        role.setClosedDomain(property.getIsClosedDomain());
        role.setClosedRange(property.getIsClosedRange());
        role.getSourceClassesDetailed().addAll(convertInternalDtoToApiDto(property.getSourceClasses()));
        role.getTargetClassesDetailed().addAll(convertInternalDtoToApiDto(property.getTargetClasses()));
        property.getSourceAndTargetPairs().forEach(pair -> {
            if (isFalse(isDuplicatePair(role.getClassPairs(), pair.getSourceClass(), pair.getTargetClass()))) {
                role.getClassPairs().add(new ClassPair(pair.getSourceClass(), pair.getTargetClass(), pair.getTripleCount()));
            }
        });
        return role;
    }

    protected Set<SchemaPropertyLinkedClassDetails> convertInternalDtoToApiDto(@Nonnull List<SchemaExtractorClassNodeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new SchemaPropertyLinkedClassDetails(
                        internalDto.getClassName(), internalDto.getTripleCount(), internalDto.getObjectTripleCount(), internalDto.getMinCardinality())).
                        collect(Collectors.toSet());
    }

    protected List<SchemaAttributeDataType> convertInternalDataTypesToApiDto(@Nonnull List<SchemaExtractorDataTypeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new SchemaAttributeDataType(
                        internalDto.getDataType(), internalDto.getTripleCount())).
                        collect(Collectors.toList());
    }

    @Override
    protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request) {
        // do nothing
    }

    @Override
    protected Map<String, SchemaExtractorPropertyNodeInfo> findAllDataTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        return null;
    }

    @Override
    protected Map<String, SchemaExtractorPropertyNodeInfo> findAllObjectTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        return null;
    }
}

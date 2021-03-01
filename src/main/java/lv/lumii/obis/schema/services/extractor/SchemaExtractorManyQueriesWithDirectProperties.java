package lv.lumii.obis.schema.services.extractor;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorClassNodeInfo;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorDomainRangeInfo;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorPropertyNodeInfo;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.DATA_TYPE_XSD_DEFAULT;
import static lv.lumii.obis.schema.constants.SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY;
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

            // determine whether it is a dataType or objectType property
            determinePropertyType(property, request);

            // calculate property domains with instance count
            determinePropertyDomains(property, request);

            // calculate property ranges with instance count
            if (isTrue(property.getIsObjectProperty())) {
                determinePropertyRanges(property, request);
                determinePropertyDomainRangePairs(property, request);
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
        log.info(request.getCorrelationId() + " - determinePropertyType [" + property.getPropertyName() + "] " + instancesCountForTotal + " : " + instancesCountForUrl);
        if (instancesCountForTotal.equals(0L)) {
            return;
        }
        if (instancesCountForTotal.equals(instancesCountForUrl)) {
            property.setIsObjectProperty(Boolean.TRUE);
        } else {
            property.setIsObjectProperty(Boolean.FALSE);
        }
    }

    protected void determinePropertyDomains(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request) {
        log.info(request.getCorrelationId() + " - determinePropertyDomains [" + property.getPropertyName() + "]");

        String query = FIND_PROPERTY_DOMAINS.getSparqlQuery().replace(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_PROPERTY_DOMAINS.name(), query);

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (StringUtils.isNotEmpty(className)) {
                property.getDomainClasses().add(new SchemaExtractorClassNodeInfo(className, SchemaUtil.getLongValueFromString(instancesCountStr)));
            }
        }
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
        attribute.setMinCardinality(property.getMinCardinality());
        attribute.setMaxCardinality(property.getMaxCardinality());
        attribute.getSourceClasses().addAll(property.getDomainClasses().stream().map(SchemaExtractorClassNodeInfo::getClassName).collect(Collectors.toSet()));
        attribute.getSourceClassesDetailed().addAll(convertInternalDtoToApiDto(property.getDomainClasses()));
        attribute.setType(property.getDataType());
        attribute.setInstanceCount(property.getInstanceCount());
        if (attribute.getType() == null || attribute.getType().trim().equals("")) {
            attribute.setType(DATA_TYPE_XSD_DEFAULT);
        }
        return attribute;
    }

    protected SchemaRole createSchemaRole(@Nonnull String propertyId, @Nonnull SchemaExtractorPropertyNodeInfo property) {
        SchemaRole role = new SchemaRole();
        SchemaUtil.setLocalNameAndNamespace(propertyId, role);
        role.setMinCardinality(property.getMinCardinality());
        role.setMaxCardinality(property.getMaxCardinality());
        role.setInstanceCount(property.getInstanceCount());
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
                .map(internalDto -> new SchemaPropertyLinkedClassDetails(internalDto.getClassName(), internalDto.getInstanceCount())).
                        collect(Collectors.toSet());
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

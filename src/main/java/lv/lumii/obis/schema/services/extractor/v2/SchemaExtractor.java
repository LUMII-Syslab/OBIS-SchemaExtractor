package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v2.*;
import lv.lumii.obis.schema.services.ObjectConversionService;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.common.SparqlQueryBuilder;
import lv.lumii.obis.schema.services.common.dto.QueryResponse;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.common.dto.QueryResultObject;
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
import static lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.*;

@Slf4j
@Service
public class SchemaExtractor {

    private static final Comparator<String> nullSafeStringComparator = Comparator.nullsLast(String::compareToIgnoreCase);

    private enum LinkedClassType {SOURCE, TARGET, PAIR_SOURCE, PAIR_TARGET}

    @Autowired
    @Setter
    @Getter
    protected SparqlEndpointProcessor sparqlEndpointProcessor;

    @Autowired
    @Setter
    @Getter
    private ObjectConversionService objectConversionService;

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
        schema.setExecutionParameters(request);
        return schema;
    }

    protected void buildClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses) {
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");

        // validate whether the request includes the list of classes with instance counts
        boolean requestedClassesWithInstanceCounts = request.getIncludedClasses().stream().anyMatch(c -> SchemaUtil.getLongValueFromString(c.getInstanceCount()) > 0L);

        // if the request does not include the list of classes or classes do not have instance count - read from the SPARQL endpoint
        List<SchemaClass> classes = new ArrayList<>();
        if (isFalse(requestedClassesWithInstanceCounts)) {
            for (String classificationProperty : request.getClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLASSES_WITH_INSTANCE_COUNT.name()), FIND_CLASSES_WITH_INSTANCE_COUNT)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                List<SchemaClass> resultClasses = processClassesWithEndpointData(queryResponse.getResults(), request, classificationProperty);
                classes.addAll(resultClasses);
                log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", resultClasses.size(), classificationProperty));
            }
            log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        } else {
            classes = processClasses(request.getIncludedClasses(), request);
            log.info(request.getCorrelationId() + String.format(" - found total %d classes from the request input", classes.size()));
        }

        schema.setClasses(classes);

        if (isTrue(request.getCalculateSubClassRelations())) {

            for (SchemaClass c : classes) {
                graphOfClasses.put(c.getFullName(), new SchemaExtractorClassNodeInfo(c.getFullName(), c.getInstanceCount(), c.getClassificationProperty(), c.getIsLiteral()));
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

            // add intersection classes to the result schema
            if (SchemaExtractorRequestDto.ShowIntersectionClassesMode.yes.equals(request.getAddIntersectionClasses())
                    || SchemaExtractorRequestDto.ShowIntersectionClassesMode.auto.equals(request.getAddIntersectionClasses())) {
                for (SchemaClass clazz : classes) {
                    List<String> intersectionClasses = graphOfClasses.get(clazz.getFullName()).getNeighbors();
                    if (SchemaExtractorRequestDto.ShowIntersectionClassesMode.yes.equals(request.getAddIntersectionClasses())
                            || intersectionClasses.size() <= 200)
                        clazz.getIntersectionClasses().addAll(intersectionClasses);
                }
            }
        }
    }

    protected List<SchemaClass> processClassesWithEndpointData(@Nonnull List<QueryResult> queryResults,
                                                               @Nonnull SchemaExtractorRequestDto request, @Nonnull String classificationProperty) {
        List<SchemaClass> classes = new ArrayList<>();
        Set<String> includedClasses = request.getIncludedClasses().stream().map(SchemaExtractorRequestedClassDto::getClassName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {
            QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (classNameObject == null) {
                continue;
            }
            String instancesCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (includedClasses.isEmpty() || includedClasses.contains(classNameObject.getValue())) {
                addClass(classNameObject, classNameObject.getValue(), instancesCountStr, classificationProperty, classes, request);
            }
        }
        return classes;
    }

    protected List<SchemaClass> processClasses(@Nonnull List<SchemaExtractorRequestedClassDto> includedClasses, @Nonnull SchemaExtractorRequestDto request) {
        List<SchemaClass> classes = new ArrayList<>();
        includedClasses.forEach(c -> addClass(null, c.getClassName(), c.getInstanceCount(), null, classes, request));
        return classes;
    }

    private void addClass(@Nullable QueryResultObject classNameObject, @Nullable String className, @Nullable String instancesCountStr, @Nullable String classificationProperty,
                          @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
            SchemaClass classEntry = new SchemaClass();
            if (classNameObject != null) {
                classEntry.setLocalName(classNameObject.getLocalName());
                classEntry.setFullName(classNameObject.getFullName());
                classEntry.setNamespace(classNameObject.getNamespace());
                classEntry.setDataType(classNameObject.getDataType());
                classEntry.setIsLiteral(classNameObject.getIsLiteral());
            } else {
                setLocalNameAndNamespace(className, classEntry);
            }
            classEntry.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));
            if (classEntry.getInstanceCount() < request.getMinimalAnalyzedClassSize()) {
                classEntry.setPropertiesInSchema(Boolean.FALSE);
            } else {
                classEntry.setPropertiesInSchema(Boolean.TRUE);
            }
            classEntry.setClassificationProperty(classificationProperty);
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
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_ALL_PROPERTIES.name()), FIND_ALL_PROPERTIES);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            properties = processPropertiesWithEndpointData(queryResponse.getResults(), request);
            log.info(request.getCorrelationId() + String.format(" - found %d properties", properties.size()));
        } else {
            properties = processProperties(request.getIncludedProperties(), request);
            log.info(request.getCorrelationId() + String.format(" - found %d properties from the request input", properties.size()));
        }

        // fill properties with additional data
        enrichProperties(properties, schema, graphOfClasses, request);

        // update classes with incoming triple count
        updateClassesWithIncomingTripleCount(properties, schema);

        // fill schema object with attributes and roles
        formatProperties(properties, schema);
    }

    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processPropertiesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        Set<String> includedProperties = request.getIncludedProperties().stream().map(SchemaExtractorRequestedPropertyDto::getPropertyName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {

            String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            String instancesCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

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
        includedProperties.forEach(p -> addProperty(p.getPropertyName(), p.getInstanceCount(), properties, request));
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

            determinePropertySource(property, schema, request);
            determinePropertySourceTripleCount(property, request);
            determinePropertySourceObjectTripleCount(property, request);
            determinePropertySourceDataTripleCount(property, request);

            if (property.getObjectTripleCount() > 0) {
                determinePropertyTarget(property, schema, request);
                determinePropertyTargetTripleCount(property, request);
                if (isTrue(request.getCalculateSourceAndTargetPairs())) {
                    determinePropertySourceTargetPairs(property, schema, request);
                }

            }

            if (isTrue(request.getCalculateClosedClassSets())) {
                determinePropertyClosedDomains(property, request);
                determinePropertyClosedRanges(property, request);
                determinePropertyClosedRangesOnSourceClassLevel(property, request);
                determinePropertyClosedDomainsOnTargetClassLevel(property, request);
            }

            if (isFalse(property.getIsObjectProperty()) && isTrue(request.getCalculateDataTypes())) {
                determinePropertyDataTypes(property, request);
                determinePropertySourceDataTypes(property, request);
            }

            if (isTrue(request.getCalculateDomainsAndRanges())) {
                determineDomainsAndRanges(property, schema.getClasses(), request);
            }

            if (isTrue(request.getCalculateImportanceIndexes())) {
                determineImportanceIndexes(property, schema.getClasses(), graphOfClasses, request);
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
                    determinePropertySourceMaxCardinality(property, request);
                    determinePropertySourceMinCardinality(property, request);
                    if (property.getObjectTripleCount() > 0) {
                        determinePropertyInverseMaxCardinality(property, request);
                        determinePropertyTargetsInverseMinCardinality(property, request);
                        determinePropertyTargetsInverseMaxCardinality(property, request);
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
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_URL_VALUES.name()), COUNT_PROPERTY_URL_VALUES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponseForUrlCount = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponseForUrlCount.hasErrors() && !queryResponseForUrlCount.getResults().isEmpty() && queryResponseForUrlCount.getResults().get(0) != null) {
            objectTripleCount = SchemaUtil.getLongValueFromString(queryResponseForUrlCount.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_URL_VALUES.name()), CHECK_PROPERTY_URL_VALUES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse checkQueryResponseForUrlCount = sparqlEndpointProcessor.read(request, queryBuilder, false);
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
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_LITERAL_VALUES.name()), COUNT_PROPERTY_LITERAL_VALUES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponseForLiteralCount = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponseForLiteralCount.hasErrors() && !queryResponseForLiteralCount.getResults().isEmpty() && queryResponseForLiteralCount.getResults().get(0) != null) {
            dataTripleCount = SchemaUtil.getLongValueFromString(queryResponseForLiteralCount.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_LITERAL_VALUES.name()), CHECK_PROPERTY_LITERAL_VALUES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse checkQueryResponseForLiteralCount = sparqlEndpointProcessor.read(request, queryBuilder, false);
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

    protected void determinePropertySource(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertySource [" + property.getPropertyName() + "]");

        boolean hasErrors = false;
        for (String classificationProperty : request.getClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT.name()), FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (isFalse(hasErrors)) {
                hasErrors = queryResponse.hasErrors();
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaClass schemaClass = findClass(schema.getClasses(), className);
                    if (schemaClass != null && isNotFalse(schemaClass.getPropertiesInSchema())) {
                        property.getSourceClasses().add(new SchemaExtractorClassNodeInfo(className, classificationProperty, schemaClass.getIsLiteral()));
                    }
                }
            }
        }

        if (hasErrors && property.getSourceClasses().isEmpty()) {
            schema.getClasses().forEach(potentialSource -> {
                if (isNotFalse(potentialSource.getPropertiesInSchema())) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_CLASS_AS_PROPERTY_SOURCE.name()), CHECK_CLASS_AS_PROPERTY_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialSource.getFullName(), potentialSource.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialSource.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse checkSourceQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkSourceQueryResponse.hasErrors() && !checkSourceQueryResponse.getResults().isEmpty()) {
                        property.getSourceClasses().add(new SchemaExtractorClassNodeInfo(potentialSource.getFullName(), potentialSource.getClassificationProperty(), potentialSource.getIsLiteral()));
                    }
                }
            });
        }
    }

    protected void determinePropertySourceTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertySources[" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCE_TRIPLE_COUNT.name()), FIND_PROPERTY_SOURCE_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            for (QueryResult queryResult : queryResponse.getResults()) {
                if (queryResult != null) {
                    sourceClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertySourceObjectTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineObjectTripleCountForAllPropertySources [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property and specific source
        property.getSourceClasses().forEach(sourceClass -> {
            if (property.getObjectTripleCount() == 0) {
                sourceClass.setObjectTripleCount(0L);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_URL_VALUES_FOR_SOURCE.name()), COUNT_PROPERTY_URL_VALUES_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long objectTripleCountForSource = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setObjectTripleCount(objectTripleCountForSource);
                } else {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_URL_VALUES_FOR_SOURCE.name()), CHECK_PROPERTY_URL_VALUES_FOR_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                        sourceClass.setObjectTripleCount(-1L);
                    }
                }
            }
        });
    }

    protected void determinePropertySourceDataTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDataTripleCountForAllPropertySources [" + property.getPropertyName() + "]");

        // get count of literal values for the specific property and specific source
        property.getSourceClasses().forEach(sourceClass -> {
            if (property.getDataTripleCount() == 0) {
                sourceClass.setDataTripleCount(0L);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name()), COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long dataTripleCountForSource = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setDataTripleCount(dataTripleCountForSource);
                } else {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name()), CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                        sourceClass.setDataTripleCount(-1L);
                    }
                }
            }
        });
    }

    protected void determinePropertyTarget(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyTargets [" + property.getPropertyName() + "]");

        boolean hasErrors = false;
        for (String classificationProperty : request.getClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT.name()), FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (isFalse(hasErrors)) {
                hasErrors = queryResponse.hasErrors();
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaClass schemaClass = findClass(schema.getClasses(), className);
                    if (schemaClass != null && isNotFalse(schemaClass.getPropertiesInSchema())) {
                        property.getTargetClasses().add(new SchemaExtractorClassNodeInfo(className, classificationProperty, schemaClass.getIsLiteral()));
                    }
                }
            }
        }

        if (hasErrors && property.getTargetClasses().isEmpty()) {
            schema.getClasses().forEach(potentialTarget -> {
                if (isNotFalse(potentialTarget.getPropertiesInSchema())) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_CLASS_AS_PROPERTY_TARGET.name()), CHECK_CLASS_AS_PROPERTY_TARGET)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialTarget.getFullName(), potentialTarget.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialTarget.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse checkTargetQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkTargetQueryResponse.hasErrors() && !checkTargetQueryResponse.getResults().isEmpty()) {
                        property.getTargetClasses().add(new SchemaExtractorClassNodeInfo(potentialTarget.getFullName(), potentialTarget.getClassificationProperty(), potentialTarget.getIsLiteral()));
                    }
                }
            });
        }
    }

    protected void determinePropertyTargetTripleCount(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertyTargets [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_TARGET_TRIPLE_COUNT.name()), FIND_PROPERTY_TARGET_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            for (QueryResult queryResult : queryResponse.getResults()) {
                if (queryResult != null) {
                    targetClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                }
            }
        });
    }

    protected void determinePropertySourceTargetPairs(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertySourceTargetPairs [" + property.getPropertyName() + "]");

        for (String classificationPropertySource : request.getClassificationProperties()) {
            for (String classificationPropertyTarget : request.getClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCE_TARGET_PAIRS.name()), FIND_PROPERTY_SOURCE_TARGET_PAIRS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationPropertySource)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationPropertyTarget)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                for (QueryResult queryResult : queryResponse.getResults()) {
                    String sourceClass = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE);
                    String targetClass = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_TARGET);
                    String tripleCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                    if (StringUtils.isNotEmpty(sourceClass) && StringUtils.isNotEmpty(targetClass)
                            && isNotExcludedResource(sourceClass, request.getExcludedNamespaces()) && isNotExcludedResource(targetClass, request.getExcludedNamespaces())) {
                        SchemaClass sourceSchemaClass = findClass(schema.getClasses(), sourceClass);
                        SchemaClass targetSchemaClass = findClass(schema.getClasses(), targetClass);
                        if (sourceSchemaClass != null && isNotFalse(sourceSchemaClass.getPropertiesInSchema())
                                && targetSchemaClass != null && isNotFalse(targetSchemaClass.getPropertiesInSchema())) {
                            property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                    sourceClass, targetClass, SchemaUtil.getLongValueFromString(tripleCountStr), classificationPropertySource, classificationPropertyTarget));
                        }
                    }
                }
            }
        }

        // endpoint was not able to return class pairs with direct query, so trying to match each source and target class combination
        if (property.getSourceAndTargetPairs().isEmpty()) {
            log.info(request.getCorrelationId() + " - determinePropertySourceTargetPairsForEachSourceAndTargetCombination [" + property.getPropertyName() + "]");

            property.getSourceClasses().forEach(sourceClass -> property.getTargetClasses().forEach(targetClass -> {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES.name()), FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long tripleCountForPair = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (tripleCountForPair > 0) {
                        property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                sourceClass.getClassName(), targetClass.getClassName(), tripleCountForPair, sourceClass.getClassificationProperty(), targetClass.getClassificationProperty()));
                    }
                }
            }));
        }
    }

    protected void determinePropertyClosedDomains(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomains [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property level
        if (property.getSourceClasses().isEmpty()) {
            property.setIsClosedDomain((property.getTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedDomain(Boolean.TRUE);
            for (String classificationProperty : request.getClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_DOMAIN_FOR_PROPERTY.name()), FIND_CLOSED_DOMAIN_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_X))) {
                    property.setIsClosedDomain(Boolean.FALSE);
                    break;
                }
            }
        }
    }

    protected void determinePropertyClosedRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRanges [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class
        if (property.getTargetClasses().isEmpty()) {
            property.setIsClosedRange((property.getObjectTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedRange(Boolean.TRUE);
            for (String classificationProperty : request.getClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_RANGE_FOR_PROPERTY.name()), FIND_CLOSED_RANGE_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_Y))) {
                    property.setIsClosedRange(Boolean.FALSE);
                    break;
                }
            }
        }
    }

    protected void determinePropertyClosedRangesOnSourceClassLevel(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRangesOnSourceClassLevel [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property source class level
        if (isTrue(property.getIsClosedRange())) {
            property.getSourceClasses().forEach(sourceClass -> sourceClass.setIsClosedRange(Boolean.TRUE));
        } else if (isTrue(request.getCalculateSourceAndTargetPairs())) {
            property.getSourceClasses().forEach(sourceClass -> {
                sourceClass.setIsClosedRange(Boolean.TRUE);
                for (String classificationProperty : request.getClassificationProperties()) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS.name()), FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_Y))) {
                        sourceClass.setIsClosedRange(Boolean.FALSE);
                        break;
                    }
                }
            });
        }
    }

    protected void determinePropertyClosedDomainsOnTargetClassLevel(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomainsOnTargetClassLevel [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class - property target class level
        if (isTrue(property.getIsClosedDomain())) {
            property.getTargetClasses().forEach(targetClass -> targetClass.setIsClosedDomain(Boolean.TRUE));
        } else if (isTrue(request.getCalculateSourceAndTargetPairs())) {
            property.getTargetClasses().forEach(targetClass -> {
                targetClass.setIsClosedDomain(Boolean.TRUE);
                for (String classificationProperty : request.getClassificationProperties()) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS.name()), FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_X))) {
                        targetClass.setIsClosedDomain(Boolean.FALSE);
                        break;
                    }
                }
            });
        }
    }

    protected void determinePropertyDataTypes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTypes [" + property.getPropertyName() + "]");
        Long tripleCountBase = null;
        if (request.getDataTypeSampleLimit() != null && request.getDataTypeSampleLimit() > 0) {
            tripleCountBase = request.getDataTypeSampleLimit();
            if (property.getDataTripleCount() != null && property.getDataTripleCount() < request.getDataTypeSampleLimit()) {
                tripleCountBase = property.getDataTripleCount();
            }
        }
        // find data types
        SparqlQueryBuilder queryBuilder;
        if (tripleCountBase == null) {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString());
        }
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        for (QueryResult queryResult : queryResponse.getResults()) {
            String resultDataType = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(resultDataType)) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount, tripleCountBase));
            }
        }

        // find language tag
        if (tripleCountBase == null) {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_LANG_STRING.name()), FIND_PROPERTY_DATA_TYPE_LANG_STRING)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString());
        }
        queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.getResults().isEmpty()) {
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (tripleCount > 0L) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, tripleCountBase));
            }
        }
    }

    protected void determinePropertySourceDataTypes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertySourceDataTypes [" + property.getPropertyName() + "]");

        Long tripleCountBase = null;
        if (request.getDataTypeSampleLimit() != null && request.getDataTypeSampleLimit() > 0) {
            tripleCountBase = request.getDataTypeSampleLimit();
            if (property.getDataTripleCount() != null && property.getDataTripleCount() < request.getDataTypeSampleLimit()) {
                tripleCountBase = property.getDataTripleCount();
            }
        }

        Long finalTripleCountBase = tripleCountBase;
        property.getSourceClasses().forEach(sourceClass -> {
            // find data types
            SparqlQueryBuilder queryBuilder;
            if (finalTripleCountBase == null) {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            } else {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, finalTripleCountBase.toString());
            }
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            for (QueryResult queryResult : queryResponse.getResults()) {
                String resultDataType = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (StringUtils.isNotEmpty(resultDataType)) {
                    sourceClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount, finalTripleCountBase));
                }
            }
            // find language tag
            if (finalTripleCountBase == null) {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE.name()), FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            } else {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, finalTripleCountBase.toString());
            }
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.getResults().isEmpty()) {
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (tripleCount > 0L) {
                    sourceClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, finalTripleCountBase));
                }
            }
        });
    }

    protected void determineFollowers(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyFollowers [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_FOLLOWERS.name()), FIND_PROPERTY_FOLLOWERS)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        for (QueryResult queryResult : queryResponse.getResults()) {
            String otherProperty = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty)) {
                property.getFollowers().add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount));
            }
        }
    }

    protected void determineOutgoingProperties(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyOutgoingProperties [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_OUTGOING_PROPERTIES.name()), FIND_PROPERTY_OUTGOING_PROPERTIES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        for (QueryResult queryResult : queryResponse.getResults()) {
            String otherProperty = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty)) {
                property.getOutgoingProperties().add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount));
            }
        }
    }

    protected void determineIncomingProperties(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyIncomingProperties [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_INCOMING_PROPERTIES.name()), FIND_PROPERTY_INCOMING_PROPERTIES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        for (QueryResult queryResult : queryResponse.getResults()) {
            String otherProperty = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty)) {
                property.getIncomingProperties().add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount));
            }
        }
    }

    protected void determinePropertySourceMinCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMinCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MIN_CARDINALITY.name()), FIND_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.getResults().isEmpty()) {
                sourceClass.setMinCardinality(DEFAULT_MIN_CARDINALITY);
            } else {
                sourceClass.setMinCardinality(1);
            }
        });
    }

    protected void determinePropertyMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY.name()), FIND_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.getResults().isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
    }

    protected void determinePropertySourceMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMaxCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE.name()), FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.getResults().isEmpty()) {
                sourceClass.setMaxCardinality(1);
            } else {
                sourceClass.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
            }
        });
    }

    protected void determinePropertyInverseMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyInverseMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.getResults().isEmpty()) {
            property.setMaxInverseCardinality(1);
            return;
        }
        property.setMaxInverseCardinality(DEFAULT_MAX_CARDINALITY);
    }

    protected void determinePropertyTargetsInverseMaxCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsInverseMaxCardinality [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            targetClass.setMaxInverseCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
        });
    }

    protected void determinePropertyTargetsInverseMinCardinality(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsInverseMinCardinality [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MIN_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            targetClass.setMinInverseCardinality(!queryResponse.getResults().isEmpty() ? DEFAULT_MIN_CARDINALITY : 1);
        });
    }

    protected void determineDomainsAndRanges(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                             @Nonnull SchemaExtractorRequestDto request) {

        log.info(request.getCorrelationId() + " - determineDomainForProperty [" + property.getPropertyName() + "]");
        SchemaExtractorPropertyLinkedClassInfo domainClass = determinePropertyDomainOrRange(property,
                getTheFirstMainClass(buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes)), request, LinkedClassType.SOURCE, null, null);
        if (domainClass != null) {
            property.setHasDomain(true);
            mapDomainOrRangeClass(property.getSourceClasses(), domainClass);
        } else {
            property.setHasDomain(false);
            property.getSourceClasses().forEach(linkedClass -> linkedClass.setIsPrincipal(false));
        }

        log.info(request.getCorrelationId() + " - determineRangeForProperty [" + property.getPropertyName() + "]");
        SchemaExtractorPropertyLinkedClassInfo rangeClass = determinePropertyDomainOrRange(property,
                getTheFirstMainClass(buildAndSortPropertyLinkedClasses(property.getTargetClasses(), classes)), request, LinkedClassType.TARGET, null, null);
        if (rangeClass != null) {
            property.setHasRange(true);
            mapDomainOrRangeClass(property.getTargetClasses(), rangeClass);
        } else {
            property.setHasRange(false);
            property.getTargetClasses().forEach(linkedClass -> linkedClass.setIsPrincipal(false));
        }

        log.info(request.getCorrelationId() + " - determineRangeForClassPairSource [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            List<SchemaExtractorSourceTargetInfo> pairsForSpecificSource = findPairsWithSpecificSource(property.getSourceAndTargetPairs(), sourceClass.getClassName());
            SchemaExtractorPropertyLinkedClassInfo pairRange = determinePropertyDomainOrRange(property,
                    getTheFirstMainClass(buildAndSortPropertyPairTargets(pairsForSpecificSource, classes)), request, LinkedClassType.PAIR_SOURCE, sourceClass, null);
            if (pairRange != null) {
                sourceClass.setHasRangeInClassPair(true);
                pairsForSpecificSource.forEach(linkedPair -> {
                    if (pairRange.getClassName().equalsIgnoreCase(linkedPair.getTargetClass())) {
                        linkedPair.setIsPrincipalTarget(true);
                        linkedPair.setTargetImportanceIndex(1);
                    } else {
                        linkedPair.setIsPrincipalTarget(false);
                        linkedPair.setTargetImportanceIndex(0);
                    }
                });
            } else {
                sourceClass.setHasRangeInClassPair(false);
                pairsForSpecificSource.forEach(linkedPair -> linkedPair.setIsPrincipalTarget(false));
            }
        });

        log.info(request.getCorrelationId() + " - determineDomainForClassPairTarget [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            List<SchemaExtractorSourceTargetInfo> pairsForSpecificTarget = findPairsWithSpecificTarget(property.getSourceAndTargetPairs(), targetClass.getClassName());
            SchemaExtractorPropertyLinkedClassInfo pairDomain = determinePropertyDomainOrRange(property,
                    getTheFirstMainClass(buildAndSortPropertyPairSources(pairsForSpecificTarget, classes)), request, LinkedClassType.PAIR_TARGET, null, targetClass);
            if (pairDomain != null) {
                targetClass.setHasDomainInClassPair(true);
                pairsForSpecificTarget.forEach(linkedPair -> {
                    if (pairDomain.getClassName().equalsIgnoreCase(linkedPair.getSourceClass())) {
                        linkedPair.setIsPrincipalSource(true);
                        linkedPair.setSourceImportanceIndex(1);
                    } else {
                        linkedPair.setIsPrincipalSource(false);
                        linkedPair.setSourceImportanceIndex(0);
                    }
                });
            } else {
                targetClass.setHasDomainInClassPair(false);
                pairsForSpecificTarget.forEach(linkedPair -> linkedPair.setIsPrincipalSource(false));
            }
        });
    }

    @Nullable
    protected SchemaExtractorPropertyLinkedClassInfo getTheFirstMainClass(List<SchemaExtractorPropertyLinkedClassInfo> classes) {
        return classes.stream()
                .filter(currentClass -> !OWL_RDF_TOP_LEVEL_RESOURCES.contains(currentClass.getClassName()))
                .findFirst().orElse(null);
    }

    protected void mapDomainOrRangeClass(@Nonnull List<SchemaExtractorClassNodeInfo> propertyLinkedClasses, @Nonnull SchemaExtractorPropertyLinkedClassInfo clazz) {
        propertyLinkedClasses.forEach(linkedClass -> {
            if (linkedClass.getClassName().equalsIgnoreCase(clazz.getClassName())) {
                linkedClass.setIsPrincipal(true);
                linkedClass.setImportanceIndex(1);
            } else {
                linkedClass.setIsPrincipal(false);
                linkedClass.setImportanceIndex(0);
            }
        });
    }


    @Nullable
    protected SchemaExtractorPropertyLinkedClassInfo determinePropertyDomainOrRange(@Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                    @Nullable SchemaExtractorPropertyLinkedClassInfo currentClass,
                                                                                    @Nonnull SchemaExtractorRequestDto request,
                                                                                    @Nonnull LinkedClassType linkedClassType,
                                                                                    @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                    @Nullable SchemaExtractorClassNodeInfo targetClass) {
        if (currentClass == null) {
            return null;
        }
        boolean isApplicable = false;

        switch (linkedClassType) {
            case SOURCE:
                isApplicable = checkDomainClass(property.getPropertyName(), currentClass, request);
                break;
            case TARGET:
                isApplicable = checkRangeClass(property.getPropertyName(), currentClass, request);
                break;
            case PAIR_SOURCE:
                if (sourceClass != null && StringUtils.isNotEmpty(sourceClass.getClassName())) {
                    isApplicable = checkRangeForSource(property.getPropertyName(), sourceClass, currentClass, request);
                }
                break;
            case PAIR_TARGET:
                if (targetClass != null && StringUtils.isNotEmpty(targetClass.getClassName())) {
                    isApplicable = checkDomainForTarget(property.getPropertyName(), targetClass, currentClass, request);
                }
                break;
            default:
                break;
        }
        if (isApplicable) {
            return currentClass;
        }
        return null;
    }

    protected boolean checkDomainClass(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain,
                                       @Nonnull SchemaExtractorRequestDto request) {
        boolean isDomainClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PROPERTY.name()), CHECK_DOMAIN_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        isDomainClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isDomainClass;
    }

    protected boolean checkRangeClass(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange,
                                      @Nonnull SchemaExtractorRequestDto request) {
        boolean isRangeClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PROPERTY.name()), CHECK_RANGE_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        isRangeClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isRangeClass;
    }

    protected boolean checkRangeForSource(@Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange, @Nonnull SchemaExtractorRequestDto request) {
        boolean isRangeClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PAIR_SOURCE.name()), CHECK_RANGE_FOR_PAIR_SOURCE)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        isRangeClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isRangeClass;
    }

    protected boolean checkDomainForTarget(@Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain, @Nonnull SchemaExtractorRequestDto request) {
        boolean isDomainClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PAIR_TARGET.name()), CHECK_DOMAIN_FOR_PAIR_TARGET)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        isDomainClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isDomainClass;
    }

    protected void determineImportanceIndexes(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                              @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                              @Nonnull SchemaExtractorRequestDto request) {

        if (isNotTrue(property.getHasDomain())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForSourceClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalSourceClasses = determinePrincipalClasses(property,
                    buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes), classes, graphOfClasses, request, LinkedClassType.SOURCE, null, null);
            mapPrincipalClasses(property.getSourceClasses(), principalSourceClasses);
        }

        if (isNotTrue(property.getHasRange())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForTargetClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalTargetClasses = determinePrincipalClasses(property,
                    buildAndSortPropertyLinkedClasses(property.getTargetClasses(), classes), classes, graphOfClasses, request, LinkedClassType.TARGET, null, null);
            mapPrincipalClasses(property.getTargetClasses(), principalTargetClasses);
        }

        log.info(request.getCorrelationId() + " - determineImportanceIndexesForClassPairSource [" + property.getPropertyName() + "]");
        // set pair target important indexes
        property.getSourceClasses().forEach(sourceClass -> {
            if (isNotTrue(sourceClass.getHasRangeInClassPair())) {
                List<SchemaExtractorSourceTargetInfo> pairsForSpecificSource = findPairsWithSpecificSource(property.getSourceAndTargetPairs(), sourceClass.getClassName());
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairTargets = determinePrincipalClasses(property,
                        buildAndSortPropertyPairTargets(pairsForSpecificSource, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_SOURCE, sourceClass, null);
                pairsForSpecificSource.forEach(linkedPair -> {
                    SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairTargets.stream()
                            .filter(pairTarget -> pairTarget.getClassName().equalsIgnoreCase(linkedPair.getTargetClass())).findFirst().orElse(null);
                    linkedPair.setTargetImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
                });
            }

        });
        log.info(request.getCorrelationId() + " - determineImportanceIndexesForClassPairTarget [" + property.getPropertyName() + "]");
        // set pair source important indexes
        property.getTargetClasses().forEach(targetClass -> {
            if (isNotTrue(targetClass.getHasDomainInClassPair())) {
                List<SchemaExtractorSourceTargetInfo> pairsForSpecificTarget = findPairsWithSpecificTarget(property.getSourceAndTargetPairs(), targetClass.getClassName());
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairSources = determinePrincipalClasses(property,
                        buildAndSortPropertyPairSources(pairsForSpecificTarget, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_TARGET, null, targetClass);
                pairsForSpecificTarget.forEach(linkedPair -> {
                    SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairSources.stream()
                            .filter(pairTarget -> pairTarget.getClassName().equalsIgnoreCase(linkedPair.getSourceClass())).findFirst().orElse(null);
                    linkedPair.setSourceImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
                });
            }
        });
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> determinePrincipalClasses(@Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                     @Nonnull List<SchemaExtractorPropertyLinkedClassInfo> propertyLinkedClassesSorted,
                                                                                     @Nonnull List<SchemaClass> classes,
                                                                                     @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                                                     @Nonnull SchemaExtractorRequestDto request,
                                                                                     @Nonnull LinkedClassType linkedClassType,
                                                                                     @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                     @Nullable SchemaExtractorClassNodeInfo targetClass) {

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
                if (isTrue(request.getCalculateImportanceIndexes())) {
                    continue;
                } else {
                    break;
                }
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

                switch (linkedClassType) {
                    case SOURCE:
                        needToInclude = checkNewPrincipalSourceClass(property.getPropertyName(), currentClass, includedClassesToCheckWith, classes, request);
                        break;
                    case TARGET:
                        needToInclude = checkNewPrincipalTargetClass(property.getPropertyName(), currentClass, includedClassesToCheckWith, classes, request);
                        break;
                    case PAIR_SOURCE:
                        if (sourceClass != null && StringUtils.isNotEmpty(sourceClass.getClassName())) {
                            needToInclude = checkNewPrincipalTargetClassForSource(property.getPropertyName(), sourceClass, currentClass, includedClassesToCheckWith, classes, request);
                        }
                        break;
                    case PAIR_TARGET:
                        if (targetClass != null && StringUtils.isNotEmpty(targetClass.getClassName())) {
                            needToInclude = checkNewPrincipalSourceClassForTarget(property.getPropertyName(), targetClass, currentClass, includedClassesToCheckWith, classes, request);
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

    protected boolean checkNewPrincipalSourceClass(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass, @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        boolean isPrincipalSource = false;
        for (String classificationProperty : request.getClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            isPrincipalSource = !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalSource)) break;
        }
        return isPrincipalSource;
    }

    protected boolean checkNewPrincipalTargetClass(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass, @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        boolean isPrincipalTarget = false;
        for (String classificationProperty : request.getClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            isPrincipalTarget = !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    protected boolean checkNewPrincipalTargetClassForSource(@Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass,
                                                            @Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        boolean isPrincipalTarget = false;
        for (String classificationProperty : request.getClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name()), CHECK_PRINCIPAL_TARGET_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            isPrincipalTarget = !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    protected boolean checkNewPrincipalSourceClassForTarget(@Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass,
                                                            @Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request) {
        boolean isPrincipalSource = false;
        for (String classificationProperty : request.getClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name()), CHECK_PRINCIPAL_SOURCE_FOR_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(existingClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            isPrincipalSource = !queryResponse.hasErrors() && !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalSource)) break;
        }
        return isPrincipalSource;
    }

    protected String buildCustomFilterToCheckPrincipalClass(@Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes) {
        StringBuilder customFilter = new StringBuilder(StringUtils.EMPTY);
        for (int i = 0; i < existingClasses.size(); i++) {
            SchemaClass clazz = findClass(classes, existingClasses.get(i));
            if (clazz == null) {
                continue;
            }
            if (i > 0) {
                customFilter.append(" || ");

            }
            if (clazz.getIsLiteral()) {
                customFilter.append("str(?cc)=\"").append(clazz.getLocalName()).append("\"");
            } else {
                customFilter.append("?cc=<").append(clazz.getFullName()).append(">");
            }
        }
        return customFilter.toString();
    }

    protected void updateClassesWithIncomingTripleCount(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        Map<String, Long> targetClassTripleCounts = new HashMap<>();
        properties.values().forEach(property -> {
            property.getTargetClasses().forEach(targetClass -> {
                Long targetClassTripleCount = 0L;
                if (targetClassTripleCounts.containsKey(targetClass.getClassName())) {
                    targetClassTripleCount = targetClassTripleCounts.get(targetClass.getClassName());
                }
                targetClassTripleCounts.put(targetClass.getClassName(), targetClassTripleCount + targetClass.getTripleCount());
            });
        });
        schema.getClasses().forEach(clazz -> {
            if (targetClassTripleCounts.containsKey(clazz.getFullName())) {
                clazz.setIncomingTripleCount(targetClassTripleCounts.get(clazz.getFullName()));
            }
        });
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
            property.getSourceClasses().addAll(convertInternalDtoToApiDto(propertyData.getSourceClasses()));
            property.getTargetClasses().addAll(convertInternalDtoToApiDto(propertyData.getTargetClasses()));
            propertyData.getSourceAndTargetPairs().forEach(pair -> {
                if (isFalse(isDuplicatePair(property.getClassPairs(), pair.getSourceClass(), pair.getTargetClass()))) {
                    property.getClassPairs().add(new ClassPair(pair.getSourceClass(), pair.getTargetClass(),
                            pair.getTripleCount(), pair.getIsPrincipalSource(), pair.getSourceImportanceIndex(),
                            pair.getIsPrincipalTarget(), pair.getTargetImportanceIndex()));
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
                                internalDto.getIsClosedDomain(), internalDto.getIsClosedRange(), internalDto.getIsPrincipal(),
                                internalDto.getMinCardinality(), internalDto.getMaxCardinality(),
                                internalDto.getMinInverseCardinality(), internalDto.getMaxInverseCardinality(),
                                internalDto.getImportanceIndex(), internalDto.getDataTypes())).
                        collect(Collectors.toList()));
    }

    protected List<DataType> convertInternalDataTypesToApiDto(@Nonnull List<SchemaExtractorDataTypeInfo> internalDtos) {
        return internalDtos.stream()
                .map(internalDto -> new DataType(
                        internalDto.getDataType(), internalDto.getTripleCount(), internalDto.getTripleCountBase())).
                collect(Collectors.toList());
    }

    protected List<SchemaPropertyLinkedPropertyDetails> convertInternalLinkedPropertyToApiDto(@Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> internalDtos) {
        return sortPropertyLinkedPropertiesByTripleCount(internalDtos).stream()
                .map(internalDto -> new SchemaPropertyLinkedPropertyDetails(
                        internalDto.getPropertyName(), internalDto.getTripleCount())).
                collect(Collectors.toList());
    }

    protected void buildNamespaceMap(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        // 1. collect all namespaces used in the schema
        Set<String> orderedSchemaNamespaces = getAllSchemaNamespacesOrderedByUsageCount(request, schema);

        // 2. collect namespace-prefix defined in the request and in the global config file; prefixes from the request override the system file
        Map<String, String> prefixMap = new HashMap<>();
        if (request.getPredefinedNamespaces() != null && request.getPredefinedNamespaces().getNamespaceItems() != null) {
            request.getPredefinedNamespaces().getNamespaceItems().forEach(namespaceItem -> prefixMap.put(namespaceItem.getNamespace(), namespaceItem.getPrefix()));
        }
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(SchemaConstants.GLOBAL_NAMESPACE_PATH);
            SchemaExtractorPredefinedNamespaces globalNamespaces = objectConversionService.getObjectFromJsonStream(inputStream, SchemaExtractorPredefinedNamespaces.class);
            if (globalNamespaces != null && globalNamespaces.getNamespaceItems() != null) {
                globalNamespaces.getNamespaceItems().forEach(namespaceItem -> {
                    if (!prefixMap.containsKey(namespaceItem.getNamespace())) {
                        prefixMap.put(namespaceItem.getNamespace(), namespaceItem.getPrefix());
                    }
                });
            }
        } catch (IOException e) {
            log.error("Cannot read namespaces from the system configuration: namespaces.json file was not found or was incorrectly formatted. The namespaces will be auto generated from the given schema");
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                log.error("Cannot close input stream when reading from " + GLOBAL_NAMESPACE_PATH);
            }
        }

        // 3. print all used namespaces in the schema using prefixes from the request or global file
        int defaultNamespaceIndex = 0;
        int autoGeneratedIndex = 1;
        for (String schemaNamespace : orderedSchemaNamespaces) {
            if (defaultNamespaceIndex == 0) {
                schema.setDefaultNamespace(schemaNamespace);
                schema.getPrefixes().add(new NamespacePrefixEntry(SchemaConstants.DEFAULT_NAMESPACE_PREFIX, schemaNamespace));
                defaultNamespaceIndex++;
            } else {
                if (prefixMap.containsKey(schemaNamespace)) {
                    schema.getPrefixes().add(new NamespacePrefixEntry(prefixMap.get(schemaNamespace), schemaNamespace));
                } else {
                    schema.getPrefixes().add(new NamespacePrefixEntry(SchemaConstants.DEFAULT_NAMESPACE_PREFIX_AUTO + autoGeneratedIndex, schemaNamespace));
                    autoGeneratedIndex++;
                }
            }
        }
    }

    @Nonnull
    protected Set<String> getAllSchemaNamespacesOrderedByUsageCount(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        List<String> namespaces = new ArrayList<>();

        // get all schema class and property namespaces
        for (SchemaClass item : schema.getClasses()) {
            if (StringUtils.isNotEmpty(item.getNamespace())) {
                namespaces.add(item.getNamespace());
            }
        }
        for (SchemaProperty item : schema.getProperties()) {
            if (StringUtils.isNotEmpty(item.getNamespace())) {
                namespaces.add(item.getNamespace());
            }
        }

        // order all class and property namespaces by usage count
        Map<String, Long> namespacesWithCounts = namespaces.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        Set<String> orderedNamespaces = new LinkedHashSet<>();
        namespacesWithCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> orderedNamespaces.add(entry.getKey()));

        // add all instance namespaces
        if (request.getCheckInstanceNamespaces()) {
            for (SchemaClass clazz : schema.getClasses()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES.name()), FIND_INSTANCE_NAMESPACES)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, clazz.getFullName(), clazz.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                    queryResponse.getResults().forEach(queryResult -> {
                        String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_X);
                        if (StringUtils.isNotEmpty(instanceNamespace)) {
                            int namespaceIndex = instanceNamespace.lastIndexOf("#");
                            if (namespaceIndex == -1) {
                                namespaceIndex = instanceNamespace.lastIndexOf("/");
                            }
                            if (namespaceIndex != -1) {
                                orderedNamespaces.add(instanceNamespace.substring(0, namespaceIndex + 1));
                            }
                        }
                    });
                }
            }
        }

        return orderedNamespaces;
    }

    protected void setLocalNameAndNamespace(@Nonnull String fullName, @Nonnull SchemaElement entity) {
        String localName = fullName;
        String namespace = "";

        int localNameIndex = fullName.lastIndexOf("#");
        if (localNameIndex == -1) {
            localNameIndex = fullName.lastIndexOf("/");
        }
        if (localNameIndex != -1) {
            localName = fullName.substring(localNameIndex + 1);
            namespace = fullName.substring(0, localNameIndex + 1);
        }

        entity.setLocalName(localName);
        entity.setFullName(fullName);
        entity.setNamespace(namespace);
    }

    protected boolean isDuplicatePair(@Nonnull List<ClassPair> pairs, @Nullable String source, @Nullable String target) {
        return pairs.stream().anyMatch(p -> p != null
                && ((p.getSourceClass() == null && source == null) || (p.getSourceClass() != null && p.getSourceClass().equals(source)))
                && ((p.getTargetClass() == null && target == null) || (p.getTargetClass() != null && p.getTargetClass().equals(target)))
        );
    }

    protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request) {
        QueryResponse queryResponse;
        for (SchemaClass classA : classes) {
            boolean hasErrors = false;
            for (String classificationProperty : request.getClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS.name()), FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, classA.getFullName(), classA.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, classA.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classificationProperty);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty()) {
                    updateGraphOfClassesWithNeighbors(classA.getFullName(), queryResponse.getResults(), graphOfClasses, request);
                    queryResponse.getResults().clear();
                }
                if (isFalse(hasErrors)) {
                    hasErrors = queryResponse.hasErrors();
                }
            }

            if (hasErrors) {
                classes.forEach(classB -> {
                    if (!classA.getFullName().equalsIgnoreCase(classB.getFullName())
                            && isNotExcludedResource(classB.getFullName(), request.getExcludedNamespaces())
                            && (isNotFalse(classA.getPropertiesInSchema()) || isNotFalse(classB.getPropertiesInSchema()))
                    ) {
                        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_CLASS_INTERSECTION.name()), CHECK_CLASS_INTERSECTION)
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, classA.getFullName(), classA.getIsLiteral())
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL, classB.getFullName(), classB.getIsLiteral())
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, classA.getClassificationProperty())
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classB.getClassificationProperty());
                        QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
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

    protected void updateGraphOfClassesWithNeighbors(@Nonnull String sourceClass, @Nonnull List<QueryResult> queryResults, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                     @Nonnull SchemaExtractorRequestDto request) {
        Set<String> includedClasses = request.getIncludedClasses().stream().map(SchemaExtractorRequestedClassDto::getClassName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {
            String classB = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B);
            if (StringUtils.isNotEmpty(classB) && isNotExcludedResource(classB, request.getExcludedNamespaces()) && (includedClasses.isEmpty() || includedClasses.contains(classB))) {
                if (graphOfClasses.containsKey(sourceClass)) {
                    graphOfClasses.get(sourceClass).getNeighbors().add(classB);
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
            SchemaExtractorClassNodeInfo neighborClassInfo = classesGraph.get(neighbor);
            Long neighborInstances = neighborClassInfo.getTripleCount();
            if (neighborInstances < currentClassInfo.getTripleCount() || neighborInstances < request.getMinimalAnalyzedClassSize()) {
                continue;
            }
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_SUPERCLASS.name()), CHECK_SUPERCLASS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, currentClass.getFullName(), currentClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL, neighborClassInfo.getClassName(), neighborClassInfo.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, currentClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, neighborClassInfo.getClassificationProperty());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.getResults().isEmpty()) {
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
    //		a. has fewer instances
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
    protected List<SchemaExtractorSourceTargetInfo> findPairsWithSpecificSource(@Nonnull List<SchemaExtractorSourceTargetInfo> allPairs, @Nonnull String sourceClass) {
        return allPairs.stream().filter(pair -> sourceClass.equalsIgnoreCase(pair.getSourceClass())).collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorSourceTargetInfo> findPairsWithSpecificTarget(@Nonnull List<SchemaExtractorSourceTargetInfo> allPairs, @Nonnull String targetClass) {
        return allPairs.stream().filter(pair -> targetClass.equalsIgnoreCase(pair.getTargetClass())).collect(Collectors.toList());
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
                newItem.setClassificationProperty(linkedClass.getClassificationProperty());
                newItem.setIsLiteral(schemaClass.getIsLiteral());
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
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyPairTargets(@Nonnull List<SchemaExtractorSourceTargetInfo> propertyLinkedPairs, @Nonnull List<SchemaClass> classes) {
        List<SchemaExtractorPropertyLinkedClassInfo> classesForProcessing = new ArrayList<>();
        propertyLinkedPairs.forEach(linkedPair -> {
            SchemaClass schemaClass = findClass(classes, linkedPair.getTargetClass());
            if (schemaClass != null) {
                SchemaExtractorPropertyLinkedClassInfo newItem = new SchemaExtractorPropertyLinkedClassInfo();
                newItem.setClassName(linkedPair.getTargetClass());
                newItem.setClassTotalTripleCount(schemaClass.getInstanceCount());
                newItem.setPropertyTripleCount(linkedPair.getTripleCount());
                newItem.setClassificationProperty(linkedPair.getClassificationPropertyForTarget());
                newItem.setIsLiteral(schemaClass.getIsLiteral());
                classesForProcessing.add(newItem);
            }
        });
        // sort property classes by triple count (descending) and then by total class triple count (ascending)
        return sortPropertyLinkedClassesByTripleCount(classesForProcessing);
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyPairSources(@Nonnull List<SchemaExtractorSourceTargetInfo> propertyLinkedPairs, @Nonnull List<SchemaClass> classes) {
        List<SchemaExtractorPropertyLinkedClassInfo> classesForProcessing = new ArrayList<>();
        propertyLinkedPairs.forEach(linkedPair -> {
            SchemaClass schemaClass = findClass(classes, linkedPair.getSourceClass());
            if (schemaClass != null) {
                SchemaExtractorPropertyLinkedClassInfo newItem = new SchemaExtractorPropertyLinkedClassInfo();
                newItem.setClassName(linkedPair.getSourceClass());
                newItem.setClassTotalTripleCount(schemaClass.getInstanceCount());
                newItem.setPropertyTripleCount(linkedPair.getTripleCount());
                newItem.setClassificationProperty(linkedPair.getClassificationPropertyForSource());
                newItem.setIsLiteral(schemaClass.getIsLiteral());
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
            QueryResponse queryResponse;
            if (label.getLanguages().isEmpty()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL.name()), FIND_LABEL)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, label.getLabelProperty());
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL_WITH_LANG.name()), FIND_LABEL_WITH_LANG)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, label.getLabelProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildFilterWithLanguages(label.getLanguages()));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String value = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_VALUE);
                String language = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_LANGUAGE);
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
        languages.forEach(lang -> customFilter.append(" || lang(?z) = '").append(lang).append("'"));
        return customFilter.toString();
    }

}

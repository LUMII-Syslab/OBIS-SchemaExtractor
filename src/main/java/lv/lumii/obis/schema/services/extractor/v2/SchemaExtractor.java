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
import static lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorError.ErrorLevel.*;

@Slf4j
@Service
public class SchemaExtractor {

    private static final Comparator<String> nullSafeStringComparator = Comparator.nullsLast(String::compareToIgnoreCase);

    private enum LinkedClassType {SOURCE, TARGET, PAIR_SOURCE, PAIR_TARGET}

    private static final List<Long> sampleLimits = Collections.unmodifiableList(Arrays.asList(1000000L, 100000L, 10000L, 1000L));

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
        Map<String, String> prefixMap = new HashMap<>();
        Map<String, SchemaExtractorClassNodeInfo> graphOfClasses = new HashMap<>();

        buildClasses(request, schema, graphOfClasses);
        buildProperties(request, schema, graphOfClasses);
        buildPrefixMap(request, prefixMap);
        buildNamespaceMap(request, schema, prefixMap);
        buildLabels(request, schema, prefixMap);

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

        // if the request does not include the list of classes or classes do not have instance count - read from the SPARQL endpoint
        List<SchemaClass> classes = new ArrayList<>();
        if (isTrue(request.getIncludedClasses().isEmpty())) {
            for (String classificationProperty : request.getAllClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLASSES_WITH_INSTANCE_COUNT.name()), FIND_CLASSES_WITH_INSTANCE_COUNT)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), null);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                    schema.getErrors().add(new SchemaExtractorError(ERROR, "allClasses", FIND_CLASSES_WITH_INSTANCE_COUNT.name(), queryBuilder.getQueryString()));
                }
                List<SchemaClass> resultClasses = processClassesWithEndpointData(queryResponse.getResults(), request, classificationProperty, schema);
                classes.addAll(resultClasses);
                log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", resultClasses.size(), classificationProperty));
            }
            log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        } else {
            for (SchemaExtractorRequestedClassDto includedClass : request.getIncludedClasses()) {
                if (!SchemaUtil.isValidURI(includedClass.getClassName())) {
                    log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + includedClass.getClassName());
                    schema.getErrors().add(new SchemaExtractorError(ERROR, "invalidURI", includedClass.getClassName(), ""));
                    continue;
                }
                if (SchemaUtil.getLongValueFromString(includedClass.getInstanceCount()) > 0L) {
                    addClass(null, includedClass.getClassName(), includedClass.getInstanceCount(), null, classes, request);
                } else {
                    for (String classificationProperty : request.getAllClassificationProperties()) {
                        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_COUNT_FOR_CLASS.name()), FIND_INSTANCE_COUNT_FOR_CLASS)
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, includedClass.getClassName(), false)
                                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), null);
                        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                            String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                            if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                                addClass(null, includedClass.getClassName(), instancesCountStr, classificationProperty, classes, request);
                                break;
                            }
                        } else {
                            schema.getErrors().add(new SchemaExtractorError(ERROR, "findInstanceCountForClass", FIND_INSTANCE_COUNT_FOR_CLASS.name(), queryBuilder.getQueryString()));
                        }
                    }
                }
            }
            log.info(request.getCorrelationId() + String.format(" - processed %d classes from the request input", classes.size()));
        }

        schema.setClasses(classes);

        if (isTrue(request.getCalculateSubClassRelations())) {

            for (SchemaClass c : classes) {
                graphOfClasses.put(c.getFullName(), new SchemaExtractorClassNodeInfo(c.getFullName(), c.getInstanceCount(), c.getClassificationProperty(), c.getIsLiteral()));
            }

            // find intersection classes
            log.info(request.getCorrelationId() + " - findIntersectionClassesAndUpdateClassNeighbors");
            findIntersectionClassesAndUpdateClassNeighbors(schema, classes, graphOfClasses, request);

            // sort classes by neighbors  size and instances count (ascending)
            log.info(request.getCorrelationId() + " - sortClassesByNeighborsSizeAndInstanceCountAscending");
            graphOfClasses = sortGraphOfClassesByNeighborsSizeAsc(graphOfClasses);

            // find superclasses
            log.info(request.getCorrelationId() + " - calculateSuperclassRelations");
            processSuperclasses(schema, graphOfClasses, classes, request);

            // validate and update classes for multiple inheritance cases
            if (isTrue(request.getCalculateMultipleInheritanceSuperclasses())) {
                log.info(request.getCorrelationId() + " - updateMultipleInheritanceSuperclasses");
                updateMultipleInheritanceSuperclasses(schema, graphOfClasses, classes, request);
            }

            // add intersection classes to the result schema
            if (SchemaExtractorRequestDto.ShowIntersectionClassesMode.yes.equals(request.getAddIntersectionClasses())
                    || SchemaExtractorRequestDto.ShowIntersectionClassesMode.auto.equals(request.getAddIntersectionClasses())) {
                for (SchemaClass clazz : classes) {
                    List<SchemaExtractorIntersectionClassDto> intersectionClasses = graphOfClasses.get(clazz.getFullName()).getNeighbors();
                    if (SchemaExtractorRequestDto.ShowIntersectionClassesMode.yes.equals(request.getAddIntersectionClasses())
                            || intersectionClasses.size() <= 200)
                        clazz.getIntersectionClasses().addAll(intersectionClasses.stream().map(SchemaExtractorIntersectionClassDto::getClassName).collect(Collectors.toSet()));
                }
            }
        }
    }

    protected List<SchemaClass> processClassesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request,
                                                               @Nonnull String classificationProperty, @Nonnull Schema schema) {
        List<SchemaClass> classes = new ArrayList<>();
        for (QueryResult queryResult : queryResults) {
            QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (classNameObject == null) {
                continue;
            }
            if (!SchemaUtil.isValidURI(classNameObject.getValue())) {
                log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + classNameObject.getValue());
                schema.getErrors().add(new SchemaExtractorError(ERROR, "invalidURI", classNameObject.getValue(), ""));
                continue;
            }
            String instancesCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            addClass(classNameObject, classNameObject.getValue(), instancesCountStr, classificationProperty, classes, request);
        }
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
                classEntry.setIsLiteral(false);
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

        // if the request does not include the list of properties - read from the SPARQL endpoint
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        if (isTrue(request.getIncludedProperties().isEmpty())) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_ALL_PROPERTIES.name()), FIND_ALL_PROPERTIES)
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), null);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                schema.getErrors().add(new SchemaExtractorError(ERROR, "allProperties", FIND_ALL_PROPERTIES.name(), queryBuilder.getQueryString()));
            }
            properties = processPropertiesWithEndpointData(queryResponse.getResults(), request, schema);
            log.info(request.getCorrelationId() + String.format(" - found %d properties", properties.size()));
        } else {
            for (SchemaExtractorRequestedPropertyDto includedProperty : request.getIncludedProperties()) {
                if (!SchemaUtil.isValidURI(includedProperty.getPropertyName())) {
                    log.error(request.getCorrelationId() + " - invalid property URI will not be processed - " + includedProperty.getPropertyName());
                    schema.getErrors().add(new SchemaExtractorError(ERROR, "invalidURI", includedProperty.getPropertyName(), ""));
                    continue;
                }
                if (SchemaUtil.getLongValueFromString(includedProperty.getInstanceCount()) > 0L) {
                    addProperty(includedProperty.getPropertyName(), includedProperty.getInstanceCount(), properties, request);
                } else {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_COUNT_FOR_PROPERTY.name()), FIND_INSTANCE_COUNT_FOR_PROPERTY)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, includedProperty.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), null);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                        String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                        if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                            addProperty(includedProperty.getPropertyName(), instancesCountStr, properties, request);
                        }
                    } else {
                        schema.getErrors().add(new SchemaExtractorError(ERROR, "findInstanceCountForProperty", FIND_INSTANCE_COUNT_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
                    }
                }
            }
            log.info(request.getCorrelationId() + String.format(" - processed %d properties from the request input", properties.size()));
        }

        // fill properties with additional data
        enrichProperties(properties, schema, graphOfClasses, request);

        // update classes with incoming triple count
        updateClassesWithIncomingTripleCount(properties, schema);

        // fill schema object with attributes and roles
        formatProperties(properties, schema);
    }

    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processPropertiesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request,
                                                                                             @Nonnull Schema schema) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            if (!SchemaUtil.isValidURI(propertyName)) {
                log.error(request.getCorrelationId() + " - invalid property URI will not be processed - " + propertyName);
                schema.getErrors().add(new SchemaExtractorError(ERROR, "invalidURI", propertyName, ""));
                continue;
            }
            String instancesCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            addProperty(propertyName, instancesCountStr, properties, request);
        }
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
        int totalCountOfProperties = properties.size();
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            determinePropertyObjectTripleCount(schema, property, request, totalCountOfProperties);
            determinePropertyDataTripleCount(schema, property, request, totalCountOfProperties);
            determinePropertyType(property, request);

            boolean foundSources = determinePropertySourcesWithTripleCount(schema, property, request, totalCountOfProperties);
            if (!foundSources) {
                determinePropertySource(schema, property, request, totalCountOfProperties);
                determinePropertySourceTripleCount(schema, property, request, totalCountOfProperties);
            }
            determinePropertySourceObjectTripleCount(schema, property, request, totalCountOfProperties);
            determinePropertySourceDataTripleCount(schema, property, request, totalCountOfProperties);

            if (property.getObjectTripleCount() > 0) {
                boolean foundTargets = determinePropertyTargetsWithTripleCount(schema, property, request, totalCountOfProperties);
                if (!foundTargets) {
                    determinePropertyTarget(schema, property, request, totalCountOfProperties);
                    determinePropertyTargetTripleCount(schema, property, request, totalCountOfProperties);
                }
                if (isTrue(request.getCalculateSourceAndTargetPairs())) {
                    determinePropertySourceTargetPairs(schema, property, request, totalCountOfProperties);
                }

            }

            if (isTrue(request.getCalculateClosedClassSets())) {
                determinePropertyClosedDomains(schema, property, request, totalCountOfProperties);
                determinePropertyClosedRanges(schema, property, request, totalCountOfProperties);
                determinePropertyClosedRangesOnSourceClassLevel(schema, property, request, totalCountOfProperties);
                determinePropertyClosedDomainsOnTargetClassLevel(schema, property, request, totalCountOfProperties);
            }

            if (isFalse(property.getIsObjectProperty())) {
                switch (request.getCalculateDataTypes()) {
                    case propertyLevelOnly:
                        determinePropertyDataTypes(schema, property, request, totalCountOfProperties);
                        break;
                    case propertyLevelAndClassContext:
                        determinePropertyDataTypes(schema, property, request, totalCountOfProperties);
                        determinePropertySourceDataTypes(schema, property, request, totalCountOfProperties);
                        break;
                    case none:
                        // do not calculate data types
                    default:
                        break;
                }

            }

            if (isTrue(request.getCalculateDomainsAndRanges())) {
                determineDomainsAndRanges(schema, property, schema.getClasses(), request, totalCountOfProperties);
            }

            if (isFalse(SchemaExtractorRequestDto.ImportantIndexesMode.no.equals(request.getCalculateImportanceIndexes()))) {
                determineImportanceIndexes(schema, property, schema.getClasses(), graphOfClasses, request, totalCountOfProperties);
            }


            if (isTrue(request.getCalculatePropertyPropertyRelations())) {
                determineFollowers(schema, property, request, totalCountOfProperties);
                determineOutgoingProperties(schema, property, request, totalCountOfProperties);
                determineIncomingProperties(schema, property, request, totalCountOfProperties);
            }

            switch (request.getCalculateCardinalitiesMode()) {
                case propertyLevelOnly:
                    determinePropertyMaxCardinality(schema, property, request, totalCountOfProperties);
                    if (property.getObjectTripleCount() > 0) {
                        determinePropertyInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                    }
                    break;
                case propertyLevelAndClassContext:
                    determinePropertyMaxCardinality(schema, property, request, totalCountOfProperties);
                    determinePropertySourceMaxCardinality(schema, property, request, totalCountOfProperties);
                    determinePropertySourceMinCardinality(schema, property, request, totalCountOfProperties);
                    if (property.getObjectTripleCount() > 0) {
                        determinePropertyInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                        determinePropertyTargetsInverseMinCardinality(schema, property, request, totalCountOfProperties);
                        determinePropertyTargetsInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                    }
                    break;
                case none:
                    // do not calculate cardinalities
                default:
                    break;
            }
        }
    }

    protected void determinePropertyObjectTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyObjectTripleCount [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property
        long objectTripleCount = 0L;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_URL_VALUES.name()), COUNT_PROPERTY_URL_VALUES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponseForUrlCount = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponseForUrlCount.hasErrors() && !queryResponseForUrlCount.getResults().isEmpty() && queryResponseForUrlCount.getResults().get(0) != null) {
            objectTripleCount = SchemaUtil.getLongValueFromString(queryResponseForUrlCount.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_URL_VALUES.name()), CHECK_PROPERTY_URL_VALUES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse checkQueryResponseForUrlCount = sparqlEndpointProcessor.read(request, queryBuilder, false);
            if (!checkQueryResponseForUrlCount.hasErrors() && !checkQueryResponseForUrlCount.getResults().isEmpty() && checkQueryResponseForUrlCount.getResults().get(0) != null) {
                objectTripleCount = -1L;
            } else if (checkQueryResponseForUrlCount.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(ERROR, property.getPropertyName(), CHECK_PROPERTY_URL_VALUES.name(), queryBuilder.getQueryString()));
            }
        }
        property.setObjectTripleCount(objectTripleCount);
    }

    protected void determinePropertyDataTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTripleCount [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property
        long dataTripleCount = 0L;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_LITERAL_VALUES.name()), COUNT_PROPERTY_LITERAL_VALUES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponseForLiteralCount = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponseForLiteralCount.hasErrors() && !queryResponseForLiteralCount.getResults().isEmpty() && queryResponseForLiteralCount.getResults().get(0) != null) {
            dataTripleCount = SchemaUtil.getLongValueFromString(queryResponseForLiteralCount.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_LITERAL_VALUES.name()), CHECK_PROPERTY_LITERAL_VALUES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse checkQueryResponseForLiteralCount = sparqlEndpointProcessor.read(request, queryBuilder, false);
            if (!checkQueryResponseForLiteralCount.hasErrors() && !checkQueryResponseForLiteralCount.getResults().isEmpty() && checkQueryResponseForLiteralCount.getResults().get(0) != null) {
                dataTripleCount = -1L;
            }
            if (checkQueryResponseForLiteralCount.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(ERROR, property.getPropertyName(), CHECK_PROPERTY_LITERAL_VALUES.name(), queryBuilder.getQueryString()));
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

    protected boolean determinePropertySourcesWithTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourcesWithTripleCount [" + property.getPropertyName() + "]");

        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT.name()), FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT.name(), queryBuilder.getQueryString()));
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaClass schemaClass = findClass(schema.getClasses(), className);
                    if (schemaClass != null && isNotFalse(schemaClass.getPropertiesInSchema())) {
                        SchemaExtractorClassNodeInfo sourceClass = new SchemaExtractorClassNodeInfo(className, classificationProperty, schemaClass.getIsLiteral());
                        sourceClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                        sourceClass.setTripleCountBase(null);
                        property.getSourceClasses().add(sourceClass);
                    }
                }
            }
        }
        return found;
    }

    protected void determinePropertySource(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySource [" + property.getPropertyName() + "]");

        boolean hasErrors = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT.name()), FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
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
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                    QueryResponse checkSourceQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkSourceQueryResponse.hasErrors() && !checkSourceQueryResponse.getResults().isEmpty()) {
                        property.getSourceClasses().add(new SchemaExtractorClassNodeInfo(potentialSource.getFullName(), potentialSource.getClassificationProperty(), potentialSource.getIsLiteral()));
                    } else if (checkSourceQueryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(ERROR, property.getPropertyName(), CHECK_CLASS_AS_PROPERTY_SOURCE.name(), queryBuilder.getQueryString()));
                    }
                }
            });
        }
    }

    protected void determinePropertySourceTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertySources[" + property.getPropertyName() + "]");

        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCE_TRIPLE_COUNT.name()), FIND_PROPERTY_SOURCE_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.hasErrors()) {
                for (QueryResult queryResult : queryResponse.getResults()) {
                    if (queryResult != null) {
                        sourceClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                        sourceClass.setTripleCountBase(null);
                    }
                }
            } else {
                schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_SOURCE_TRIPLE_COUNT.name(), queryBuilder.getQueryString()));
                for (Long limit : sampleLimits) {
                    if (limit <= property.getTripleCount()) {
                        boolean found = determinePropertySourceTripleCountWithLimits(schema, property, sourceClass, request, totalCountOfProperties, limit);
                        if (found) break;
                    }
                }
            }
        });
    }

    protected boolean determinePropertySourceTripleCountWithLimits(@Nonnull Schema schema,
                                                                   @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorClassNodeInfo sourceClass,
                                                                   @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties, Long limit) {
        log.info(request.getCorrelationId() + " - determinePropertySourceTripleCountWithLimits[" + property.getPropertyName() + "]");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCE_TRIPLE_COUNT_WITH_LIMITS.name()), FIND_PROPERTY_SOURCE_TRIPLE_COUNT_WITH_LIMITS)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString())
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
        if (queryResponse.hasErrors() || queryResponse.getResults().isEmpty() || queryResponse.getResults().get(0) == null) {
            return false;
        }
        QueryResult queryResult = queryResponse.getResults().get(0);
        sourceClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
        sourceClass.setTripleCountBase(limit);
        schema.getErrors().add(new SchemaExtractorError(OK, property.getPropertyName(), FIND_PROPERTY_SOURCE_TRIPLE_COUNT_WITH_LIMITS.name(), queryBuilder.getQueryString()));
        return true;
    }

    protected boolean determineObjectTripleCountForAllPropertySources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_URL_VALUES_FOR_SOURCES.name()), FIND_PROPERTY_URL_VALUES_FOR_SOURCES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_URL_VALUES_FOR_SOURCES.name(), queryBuilder.getQueryString()));
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaExtractorClassNodeInfo sourceClass = property.getSourceClasses().stream()
                            .filter(c -> className.equalsIgnoreCase(c.getClassName())).findFirst().orElse(null);
                    if (sourceClass != null) {
                        Long objectTripleCountForSource = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        sourceClass.setObjectTripleCount(objectTripleCountForSource);
                    }
                }
            }
        }
        return found;
    }

    protected void determinePropertySourceObjectTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determineObjectTripleCountForAllPropertySources [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property and for all source classes
        boolean found = determineObjectTripleCountForAllPropertySources(schema, property, request, totalCountOfProperties);
        if (found) return;

        // get count of URL values for the specific property and specific source
        property.getSourceClasses().forEach(sourceClass -> {
            if (property.getObjectTripleCount() == 0) {
                sourceClass.setObjectTripleCount(0L);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_URL_VALUES_FOR_SOURCE.name()), COUNT_PROPERTY_URL_VALUES_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long objectTripleCountForSource = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setObjectTripleCount(objectTripleCountForSource);
                } else {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_URL_VALUES_FOR_SOURCE.name()), CHECK_PROPERTY_URL_VALUES_FOR_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                        sourceClass.setObjectTripleCount(-1L);
                    } else if (checkQueryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), CHECK_PROPERTY_URL_VALUES_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                    }
                }
            }
        });
    }

    protected boolean determineDataTripleCountForAllPropertySources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES.name()), FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES.name(), queryBuilder.getQueryString()));
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaExtractorClassNodeInfo sourceClass = property.getSourceClasses().stream()
                            .filter(c -> className.equalsIgnoreCase(c.getClassName())).findFirst().orElse(null);
                    if (sourceClass != null) {
                        Long dataTripleCountForSource = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        sourceClass.setDataTripleCount(dataTripleCountForSource);
                    }
                }
            }
        }
        return found;
    }

    protected void determinePropertySourceDataTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determineDataTripleCountForAllPropertySources [" + property.getPropertyName() + "]");

        // get count of literal values for the specific property and for all source classes
        boolean found = determineDataTripleCountForAllPropertySources(schema, property, request, totalCountOfProperties);
        if (found) return;

        // get count of literal values for the specific property and specific source
        property.getSourceClasses().forEach(sourceClass -> {
            if (property.getDataTripleCount() == 0) {
                sourceClass.setDataTripleCount(0L);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name()), COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long dataTripleCountForSource = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setDataTripleCount(dataTripleCountForSource);
                } else {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name()), CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                        sourceClass.setDataTripleCount(-1L);
                    } else if (checkQueryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                    }
                }
            }
        });
    }

    protected boolean determinePropertyTargetsWithTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsWithTripleCount [" + property.getPropertyName() + "]");

        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT.name()), FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT.name(), queryBuilder.getQueryString()));
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaClass schemaClass = findClass(schema.getClasses(), className);
                    if (schemaClass != null && isNotFalse(schemaClass.getPropertiesInSchema())) {
                        SchemaExtractorClassNodeInfo targetClass = new SchemaExtractorClassNodeInfo(className, classificationProperty, schemaClass.getIsLiteral());
                        targetClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                        targetClass.setTripleCountBase(null);
                        property.getTargetClasses().add(targetClass);
                    }
                }
            }
        }
        return found;
    }

    protected void determinePropertyTarget(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyTargets [" + property.getPropertyName() + "]");

        boolean hasErrors = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT.name()), FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
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
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                    QueryResponse checkTargetQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                    if (!checkTargetQueryResponse.hasErrors() && !checkTargetQueryResponse.getResults().isEmpty()) {
                        property.getTargetClasses().add(new SchemaExtractorClassNodeInfo(potentialTarget.getFullName(), potentialTarget.getClassificationProperty(), potentialTarget.getIsLiteral()));
                    } else if (checkTargetQueryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(ERROR, property.getPropertyName(), CHECK_CLASS_AS_PROPERTY_TARGET.name(), queryBuilder.getQueryString()));
                    }
                }
            });
        }
    }

    protected void determinePropertyTargetTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determineTripleCountForAllPropertyTargets [" + property.getPropertyName() + "]");

        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_TARGET_TRIPLE_COUNT.name()), FIND_PROPERTY_TARGET_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.hasErrors()) {
                for (QueryResult queryResult : queryResponse.getResults()) {
                    if (queryResult != null) {
                        targetClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
                        targetClass.setTripleCountBase(null);
                    }
                }
            } else {
                schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_TARGET_TRIPLE_COUNT.name(), queryBuilder.getQueryString()));
                for (Long limit : sampleLimits) {
                    if (limit <= property.getTripleCount()) {
                        boolean found = determinePropertyTargetTripleCountWithLimits(schema, property, targetClass, request, totalCountOfProperties, limit);
                        if (found) break;
                    }
                }
            }
        });
    }

    protected boolean determinePropertyTargetTripleCountWithLimits(@Nonnull Schema schema,
                                                                   @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorClassNodeInfo targetClass,
                                                                   @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties, Long limit) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetTripleCountWithLimits[" + property.getPropertyName() + "]");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_TARGET_TRIPLE_COUNT_WITH_LIMITS.name()), FIND_PROPERTY_TARGET_TRIPLE_COUNT_WITH_LIMITS)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString())
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
        if (queryResponse.hasErrors() || queryResponse.getResults().isEmpty() || queryResponse.getResults().get(0) == null) {
            return false;
        }
        QueryResult queryResult = queryResponse.getResults().get(0);
        targetClass.setTripleCount(SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)));
        targetClass.setTripleCountBase(limit);
        schema.getErrors().add(new SchemaExtractorError(OK, property.getPropertyName(), FIND_PROPERTY_TARGET_TRIPLE_COUNT_WITH_LIMITS.name(), queryBuilder.getQueryString()));
        return true;
    }

    protected void determinePropertySourceTargetPairs(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceTargetPairs [" + property.getPropertyName() + "]");

        for (String classificationPropertySource : request.getMainClassificationProperties()) {
            for (String classificationPropertyTarget : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_SOURCE_TARGET_PAIRS.name()), FIND_PROPERTY_SOURCE_TARGET_PAIRS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationPropertySource)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationPropertyTarget)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
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
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long tripleCountForPair = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (tripleCountForPair > 0) {
                        property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                sourceClass.getClassName(), targetClass.getClassName(), tripleCountForPair, sourceClass.getClassificationProperty(), targetClass.getClassificationProperty()));
                    }
                } else if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES.name(), queryBuilder.getQueryString()));
                }
            }));
        }
    }

    protected void determinePropertyClosedDomains(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomains [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property level
        if (property.getSourceClasses().isEmpty()) {
            property.setIsClosedDomain((property.getTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedDomain(Boolean.TRUE);
            for (String classificationProperty : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_DOMAIN_FOR_PROPERTY.name()), FIND_CLOSED_DOMAIN_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_X))) {
                    property.setIsClosedDomain(Boolean.FALSE);
                    break;
                } else if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_CLOSED_DOMAIN_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
                }
            }
        }
    }

    protected void determinePropertyClosedRanges(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRanges [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class
        if (property.getTargetClasses().isEmpty()) {
            property.setIsClosedRange((property.getObjectTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedRange(Boolean.TRUE);
            for (String classificationProperty : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_RANGE_FOR_PROPERTY.name()), FIND_CLOSED_RANGE_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_Y))) {
                    property.setIsClosedRange(Boolean.FALSE);
                    break;
                } else if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_CLOSED_RANGE_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
                }
            }
        }
    }

    protected void determinePropertyClosedRangesOnSourceClassLevel(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRangesOnSourceClassLevel [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property source class level
        if (isTrue(property.getIsClosedRange())) {
            property.getSourceClasses().forEach(sourceClass -> sourceClass.setIsClosedRange(Boolean.TRUE));
        } else if (isTrue(request.getCalculateSourceAndTargetPairs())) {
            property.getSourceClasses().forEach(sourceClass -> {
                sourceClass.setIsClosedRange(Boolean.TRUE);
                for (String classificationProperty : request.getMainClassificationProperties()) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS.name()), FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_Y))) {
                        sourceClass.setIsClosedRange(Boolean.FALSE);
                        break;
                    } else if (queryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS.name(), queryBuilder.getQueryString()));
                    }
                }
            });
        }
    }

    protected void determinePropertyClosedDomainsOnTargetClassLevel(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomainsOnTargetClassLevel [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class - property target class level
        if (isTrue(property.getIsClosedDomain())) {
            property.getTargetClasses().forEach(targetClass -> targetClass.setIsClosedDomain(Boolean.TRUE));
        } else if (isTrue(request.getCalculateSourceAndTargetPairs())) {
            property.getTargetClasses().forEach(targetClass -> {
                targetClass.setIsClosedDomain(Boolean.TRUE);
                for (String classificationProperty : request.getMainClassificationProperties()) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS.name()), FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_X))) {
                        targetClass.setIsClosedDomain(Boolean.FALSE);
                        break;
                    } else if (queryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS.name(), queryBuilder.getQueryString()));
                    }
                }
            });
        }
    }

    protected void determinePropertyDataTypes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTypes [" + property.getPropertyName() + "]");
        Long tripleCountBase = null;
        if (request.getSampleLimitForDataTypeCalculation() != null && request.getSampleLimitForDataTypeCalculation() > 0) {
            tripleCountBase = request.getSampleLimitForDataTypeCalculation();
            if (property.getDataTripleCount() != null && property.getDataTripleCount() < request.getSampleLimitForDataTypeCalculation()) {
                tripleCountBase = property.getDataTripleCount();
            }
        }
        // find data types
        SparqlQueryBuilder queryBuilder;
        if (tripleCountBase == null) {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        }
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT.name(), queryBuilder.getQueryString()));
        }
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
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        }
        queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_DATA_TYPE_LANG_STRING.name(), queryBuilder.getQueryString()));
        }
        if (!queryResponse.getResults().isEmpty()) {
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (tripleCount > 0L) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, tripleCountBase));
            }
        }
    }

    protected void determinePropertySourceDataTypes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceDataTypes [" + property.getPropertyName() + "]");

        Long tripleCountBase = null;
        if (request.getSampleLimitForDataTypeCalculation() != null && request.getSampleLimitForDataTypeCalculation() > 0) {
            tripleCountBase = request.getSampleLimitForDataTypeCalculation();
            if (property.getDataTripleCount() != null && property.getDataTripleCount() < request.getSampleLimitForDataTypeCalculation()) {
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
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            } else {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, finalTripleCountBase.toString())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            }
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE.name(), queryBuilder.getQueryString()));
            }
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
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            } else {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS.name()), FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, finalTripleCountBase.toString())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            }
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.getResults().isEmpty()) {
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (tripleCount > 0L) {
                    sourceClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, finalTripleCountBase));
                }
            } else if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE.name(), queryBuilder.getQueryString()));
            }
        });
    }

    protected void determineFollowers(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyFollowers [" + property.getPropertyName() + "]");
        boolean hasFollowersOK = executePropertyRelationsQueries(schema, property, request, FIND_PROPERTY_FOLLOWERS, FIND_PROPERTY_FOLLOWERS_WITH_LIMITS, property.getFollowers(), totalCountOfProperties);
        property.setHasFollowersOK(hasFollowersOK);
    }

    protected void determineOutgoingProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyOutgoingProperties [" + property.getPropertyName() + "]");
        boolean hasOutgoingPropertiesOK = executePropertyRelationsQueries(schema, property, request, FIND_PROPERTY_OUTGOING_PROPERTIES, FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS, property.getOutgoingProperties(), totalCountOfProperties);
        property.setHasOutgoingPropertiesOK(hasOutgoingPropertiesOK);
    }

    protected void determineIncomingProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyIncomingProperties [" + property.getPropertyName() + "]");
        boolean hasIncomingPropertiesOK = executePropertyRelationsQueries(schema, property, request, FIND_PROPERTY_INCOMING_PROPERTIES, FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS, property.getIncomingProperties(), totalCountOfProperties);
        property.setHasIncomingPropertiesOK(hasIncomingPropertiesOK);
    }

    protected boolean executePropertyRelationsQueries(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                      @Nonnull SchemaExtractorQueries queryWithoutLimit, @Nonnull SchemaExtractorQueries queryWithLimit,
                                                      @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties, int totalCountOfProperties) {
        boolean isOK = true;
        Long tripleCountBase = null;
        if (request.getSampleLimitForPropertyToPropertyRelationCalculation() != null && request.getSampleLimitForPropertyToPropertyRelationCalculation() > 0) {
            tripleCountBase = request.getSampleLimitForPropertyToPropertyRelationCalculation();
            if (property.getTripleCount() != null && property.getTripleCount() < request.getSampleLimitForPropertyToPropertyRelationCalculation()) {
                tripleCountBase = property.getTripleCount();
            }
        }
        SparqlQueryBuilder queryBuilder;
        if (tripleCountBase == null) {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryWithoutLimit.name()), queryWithoutLimit)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        } else {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryWithLimit.name()), queryWithLimit)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        }
        boolean retry = true;
        QueryResponse queryResponse;
        while (isTrue(retry)) {
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (isTrue(queryResponse.hasErrors())) {
                retry = true;
                Long finalTripleCountBase = tripleCountBase;
                Long newLimit = sampleLimits.stream().filter(limit -> finalTripleCountBase == null || limit < finalTripleCountBase).findFirst().orElse(null);
                if (newLimit != null) {
                    tripleCountBase = newLimit;
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryWithLimit.name()), queryWithLimit)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString())
                            .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                } else {
                    retry = false;
                    if (queryResponse.hasErrors()) {
                        schema.getErrors().add(new SchemaExtractorError(WARNING, property.getPropertyName(), queryWithoutLimit.name(), queryBuilder.getQueryString()));
                        isOK = false;
                    }
                }
            } else {
                retry = false;
                for (QueryResult queryResult : queryResponse.getResults()) {
                    String otherProperty = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
                    Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (StringUtils.isNotEmpty(otherProperty)) {
                        relatedProperties.add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount, tripleCountBase));
                    }
                }
            }
        }
        return isOK;
    }

    protected void determinePropertySourceMinCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMinCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MIN_CARDINALITY.name()), FIND_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.getResults().isEmpty()) {
                sourceClass.setMinCardinality(DEFAULT_MIN_CARDINALITY);
            } else {
                sourceClass.setMinCardinality(1);
            }
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_MIN_CARDINALITY.name(), queryBuilder.getQueryString()));
            }
        });
    }

    protected void determinePropertyMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY.name()), FIND_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.getResults().isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_MAX_CARDINALITY.name(), queryBuilder.getQueryString()));
        }
    }

    protected void determinePropertySourceMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMaxCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE.name()), FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.getResults().isEmpty()) {
                sourceClass.setMaxCardinality(1);
            } else {
                sourceClass.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
                if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                }
            }
        });
    }

    protected void determinePropertyInverseMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyInverseMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.getResults().isEmpty()) {
            property.setMaxInverseCardinality(1);
            return;
        }
        property.setMaxInverseCardinality(DEFAULT_MAX_CARDINALITY);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name(), queryBuilder.getQueryString()));
        }
    }

    protected void determinePropertyTargetsInverseMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsInverseMaxCardinality [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET.name(), queryBuilder.getQueryString()));
            }
            targetClass.setMaxInverseCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
        });
    }

    protected void determinePropertyTargetsInverseMinCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsInverseMinCardinality [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MIN_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, property.getPropertyName())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(INFO, property.getPropertyName(), FIND_INVERSE_PROPERTY_MIN_CARDINALITY.name(), queryBuilder.getQueryString()));
            }
            targetClass.setMinInverseCardinality(!queryResponse.getResults().isEmpty() ? DEFAULT_MIN_CARDINALITY : 1);
        });
    }

    protected void determineDomainsAndRanges(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                             @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {

        log.info(request.getCorrelationId() + " - determineDomainForProperty [" + property.getPropertyName() + "]");
        SchemaExtractorPropertyLinkedClassInfo domainClass = determinePropertyDomainOrRange(schema, property,
                getTheFirstMainClass(buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes)), request, LinkedClassType.SOURCE, null, null,
                totalCountOfProperties);
        if (domainClass != null) {
            property.setHasDomain(true);
            mapDomainOrRangeClass(property.getSourceClasses(), domainClass);
        } else {
            property.setHasDomain(false);
            property.getSourceClasses().forEach(linkedClass -> linkedClass.setIsPrincipal(false));
        }

        log.info(request.getCorrelationId() + " - determineRangeForProperty [" + property.getPropertyName() + "]");
        SchemaExtractorPropertyLinkedClassInfo rangeClass = determinePropertyDomainOrRange(schema, property,
                getTheFirstMainClass(buildAndSortPropertyLinkedClasses(property.getTargetClasses(), classes)), request, LinkedClassType.TARGET, null, null,
                totalCountOfProperties);
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
            SchemaExtractorPropertyLinkedClassInfo pairRange = determinePropertyDomainOrRange(schema, property,
                    getTheFirstMainClass(buildAndSortPropertyPairTargets(pairsForSpecificSource, classes)), request, LinkedClassType.PAIR_SOURCE, sourceClass, null,
                    totalCountOfProperties);
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
            SchemaExtractorPropertyLinkedClassInfo pairDomain = determinePropertyDomainOrRange(schema, property,
                    getTheFirstMainClass(buildAndSortPropertyPairSources(pairsForSpecificTarget, classes)), request, LinkedClassType.PAIR_TARGET, null, targetClass,
                    totalCountOfProperties);
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
    protected SchemaExtractorPropertyLinkedClassInfo determinePropertyDomainOrRange(@Nonnull Schema schema,
                                                                                    @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                    @Nullable SchemaExtractorPropertyLinkedClassInfo currentClass,
                                                                                    @Nonnull SchemaExtractorRequestDto request,
                                                                                    @Nonnull LinkedClassType linkedClassType,
                                                                                    @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                    @Nullable SchemaExtractorClassNodeInfo targetClass,
                                                                                    int totalCountOfProperties) {
        if (currentClass == null) {
            return null;
        }
        boolean isApplicable = false;

        switch (linkedClassType) {
            case SOURCE:
                isApplicable = checkDomainClass(schema, property.getPropertyName(), currentClass, request, totalCountOfProperties);
                break;
            case TARGET:
                isApplicable = checkRangeClass(schema, property.getPropertyName(), currentClass, request, totalCountOfProperties);
                break;
            case PAIR_SOURCE:
                if (sourceClass != null && StringUtils.isNotEmpty(sourceClass.getClassName())) {
                    isApplicable = checkRangeForSource(schema, property.getPropertyName(), sourceClass, currentClass, request, totalCountOfProperties);
                }
                break;
            case PAIR_TARGET:
                if (targetClass != null && StringUtils.isNotEmpty(targetClass.getClassName())) {
                    isApplicable = checkDomainForTarget(schema, property.getPropertyName(), targetClass, currentClass, request, totalCountOfProperties);
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

    protected boolean checkDomainClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain,
                                       @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isDomainClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PROPERTY.name()), CHECK_DOMAIN_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_DOMAIN_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
        }
        isDomainClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isDomainClass;
    }

    protected boolean checkRangeClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange,
                                      @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isRangeClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PROPERTY.name()), CHECK_RANGE_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_RANGE_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
        }
        isRangeClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isRangeClass;
    }

    protected boolean checkRangeForSource(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange,
                                          @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isRangeClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PAIR_SOURCE.name()), CHECK_RANGE_FOR_PAIR_SOURCE)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_RANGE_FOR_PAIR_SOURCE.name(), queryBuilder.getQueryString()));
        }
        isRangeClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isRangeClass;
    }

    protected boolean checkDomainForTarget(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain,
                                           @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isDomainClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PAIR_TARGET.name()), CHECK_DOMAIN_FOR_PAIR_TARGET)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_DOMAIN_FOR_PAIR_TARGET.name(), queryBuilder.getQueryString()));
        }
        isDomainClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isDomainClass;
    }

    protected void determineImportanceIndexes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                              @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                              @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {

        if (isNotTrue(property.getHasDomain())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForSourceClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalSourceClasses = determinePrincipalClasses(schema, property,
                    buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes), classes, graphOfClasses, request, LinkedClassType.SOURCE, null, null,
                    totalCountOfProperties);
            mapPrincipalClasses(property.getSourceClasses(), principalSourceClasses);
        }

        if (isNotTrue(property.getHasRange())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForTargetClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalTargetClasses = determinePrincipalClasses(schema, property,
                    buildAndSortPropertyLinkedClasses(property.getTargetClasses(), classes), classes, graphOfClasses, request, LinkedClassType.TARGET, null, null,
                    totalCountOfProperties);
            mapPrincipalClasses(property.getTargetClasses(), principalTargetClasses);
        }

        log.info(request.getCorrelationId() + " - determineImportanceIndexesForClassPairSource [" + property.getPropertyName() + "]");
        // set pair target important indexes
        property.getSourceClasses().forEach(sourceClass -> {
            if (isNotTrue(sourceClass.getHasRangeInClassPair())) {
                List<SchemaExtractorSourceTargetInfo> pairsForSpecificSource = findPairsWithSpecificSource(property.getSourceAndTargetPairs(), sourceClass.getClassName());
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairTargets = determinePrincipalClasses(schema, property,
                        buildAndSortPropertyPairTargets(pairsForSpecificSource, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_SOURCE, sourceClass, null,
                        totalCountOfProperties);
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
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairSources = determinePrincipalClasses(schema, property,
                        buildAndSortPropertyPairSources(pairsForSpecificTarget, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_TARGET, null, targetClass,
                        totalCountOfProperties);
                pairsForSpecificTarget.forEach(linkedPair -> {
                    SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairSources.stream()
                            .filter(pairTarget -> pairTarget.getClassName().equalsIgnoreCase(linkedPair.getSourceClass())).findFirst().orElse(null);
                    linkedPair.setSourceImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
                });
            }
        });
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> determinePrincipalClasses(@Nonnull Schema schema,
                                                                                     @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                     @Nonnull List<SchemaExtractorPropertyLinkedClassInfo> propertyLinkedClassesSorted,
                                                                                     @Nonnull List<SchemaClass> classes,
                                                                                     @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                                                     @Nonnull SchemaExtractorRequestDto request,
                                                                                     @Nonnull LinkedClassType linkedClassType,
                                                                                     @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                     @Nullable SchemaExtractorClassNodeInfo targetClass,
                                                                                     int totalCountOfProperties) {

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
                List<SchemaExtractorIntersectionClassDto> currentClassNeighbors = graphOfClasses.get(currentClass.getClassName()).getNeighbors();
                List<String> includedClassesToCheckWith = sortNeighborsByTripleCountDsc(currentClassNeighbors, graphOfClasses).stream()
                        .map(SchemaExtractorIntersectionClassDto::getClassName).filter(importantClasses::contains).collect(Collectors.toList());

                if (includedClassesToCheckWith.isEmpty()) {
                    currentClass.setImportanceIndex(index++);
                    importantClasses.add(currentClass.getClassName());
                    continue;
                }

                // if the selected important indexes mode is Base then do not check detailed combinations
                if (SchemaExtractorRequestDto.ImportantIndexesMode.base.equals(request.getCalculateImportanceIndexes())) {
                    continue;
                }

                boolean needToInclude = false;

                switch (linkedClassType) {
                    case SOURCE:
                        needToInclude = checkNewPrincipalSourceClass(schema, property.getPropertyName(), currentClass, includedClassesToCheckWith, classes, request, totalCountOfProperties);
                        break;
                    case TARGET:
                        needToInclude = checkNewPrincipalTargetClass(schema, property.getPropertyName(), currentClass, includedClassesToCheckWith, classes, request, totalCountOfProperties);
                        break;
                    case PAIR_SOURCE:
                        if (sourceClass != null && StringUtils.isNotEmpty(sourceClass.getClassName())) {
                            needToInclude = checkNewPrincipalTargetClassForSource(schema, property.getPropertyName(), sourceClass, currentClass, includedClassesToCheckWith, classes, request, totalCountOfProperties);
                        }
                        break;
                    case PAIR_TARGET:
                        if (targetClass != null && StringUtils.isNotEmpty(targetClass.getClassName())) {
                            needToInclude = checkNewPrincipalSourceClassForTarget(schema, property.getPropertyName(), targetClass, currentClass, includedClassesToCheckWith, classes, request, totalCountOfProperties);
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

    protected boolean checkNewPrincipalSourceClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass, @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isPrincipalSource = false;

        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes))
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes))
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalSource = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalSource)) break;
        }
        return isPrincipalSource;
    }

    protected boolean checkNewPrincipalTargetClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass, @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isPrincipalTarget = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes))
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_TARGET.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes))
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_TARGET.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalTarget = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    protected boolean checkNewPrincipalTargetClassForSource(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass,
                                                            @Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                            int totalCountOfProperties) {
        boolean isPrincipalTarget = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name()), CHECK_PRINCIPAL_TARGET_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes))
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name()), CHECK_PRINCIPAL_TARGET_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes))
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalTarget = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    protected boolean checkNewPrincipalSourceClassForTarget(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass, @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass,
                                                            @Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                            int totalCountOfProperties) {
        boolean isPrincipalSource = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name()), CHECK_PRINCIPAL_SOURCE_FOR_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes))
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name()), CHECK_PRINCIPAL_SOURCE_FOR_TARGET)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes))
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), totalCountOfProperties);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    schema.getErrors().add(new SchemaExtractorError(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalSource = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
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
            property.setHasFollowersOK(propertyData.getHasFollowersOK());
            property.setHasOutgoingPropertiesOK(propertyData.getHasOutgoingPropertiesOK());
            property.setHasIncomingPropertiesOK(propertyData.getHasIncomingPropertiesOK());
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
                                internalDto.getTripleCount(), internalDto.getTripleCountBase(), internalDto.getDataTripleCount(), internalDto.getObjectTripleCount(),
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
                        internalDto.getPropertyName(), internalDto.getTripleCount(), internalDto.getTripleCountBase())).
                collect(Collectors.toList());
    }

    protected void buildPrefixMap(@Nonnull SchemaExtractorRequestDto request, @Nonnull Map<String, String> prefixMap) {
        // collect namespace-prefix defined in the request and in the global config file; prefixes from the request override the system file
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
    }

    protected void buildNamespaceMap(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, String> prefixMap) {
        // 1. collect all namespaces used in the schema
        Set<String> orderedSchemaNamespaces = getAllSchemaNamespacesOrderedByUsageCount(request, schema);

        // 2. print all used namespaces in the schema using prefixes from the request or global file
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

    protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull Schema schema, @Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request) {
        QueryResponse queryResponse;
        for (SchemaClass classA : classes) {
            boolean hasErrors = false;
            for (String classificationProperty : request.getPrincipalClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS.name()), FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, classA.getFullName(), classA.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, classA.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classificationProperty)
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), classes.size());
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
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classB.getClassificationProperty())
                                .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), classes.size());
                        QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
                        if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                            if (graphOfClasses.containsKey(classA.getFullName())) {
                                Long intersectionCount = SchemaUtil.getLongValueFromString(checkQueryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                                graphOfClasses.get(classA.getFullName()).getNeighbors().add(new SchemaExtractorIntersectionClassDto(classB.getFullName(), intersectionCount));
                            }
                        } else if (checkQueryResponse.hasErrors()) {
                            schema.getErrors().add(new SchemaExtractorError(ERROR, classA.getFullName(), CHECK_CLASS_INTERSECTION.name(), queryBuilder.getQueryString()));
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
            Long intersectionCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(classB) && isNotExcludedResource(classB, request.getExcludedNamespaces()) && (includedClasses.isEmpty() || includedClasses.contains(classB))) {
                if (graphOfClasses.containsKey(sourceClass)) {
                    graphOfClasses.get(sourceClass).getNeighbors().add(new SchemaExtractorIntersectionClassDto(classB, intersectionCount));
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
    protected List<SchemaExtractorIntersectionClassDto> sortNeighborsByTripleCountAsc(List<SchemaExtractorIntersectionClassDto> neighbors, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
        // sort class neighbors by triple count (ascending)
        return neighbors.stream()
                .sorted((o1, o2) -> {
                    SchemaExtractorClassNodeInfo class1 = classesGraph.get(o1.getClassName());
                    if (class1 == null || class1.getTripleCount() == null) return -1;
                    SchemaExtractorClassNodeInfo class2 = classesGraph.get(o2.getClassName());
                    if (class2 == null || class2.getTripleCount() == null) return 1;
                    Long neighborInstances1 = class1.getTripleCount();
                    Long neighborInstances2 = class2.getTripleCount();
                    int compareResult = neighborInstances1.compareTo(neighborInstances2);
                    if (compareResult == 0) {
                        return nullSafeStringComparator.compare(class1.getClassName(), class2.getClassName());
                    } else {
                        return compareResult;
                    }
                })
                .collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorIntersectionClassDto> sortNeighborsByTripleCountDsc(List<SchemaExtractorIntersectionClassDto> neighbors, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
        // sort class neighbors by triple count (descending)
        return neighbors.stream()
                .sorted((o1, o2) -> {
                    SchemaExtractorClassNodeInfo class1 = classesGraph.get(o1.getClassName());
                    if (class1 == null || class1.getTripleCount() == null) return -1;
                    SchemaExtractorClassNodeInfo class2 = classesGraph.get(o2.getClassName());
                    if (class2 == null || class2.getTripleCount() == null) return 1;
                    Long neighborInstances1 = class1.getTripleCount();
                    Long neighborInstances2 = class2.getTripleCount();
                    int compareResult = neighborInstances2.compareTo(neighborInstances1);
                    if (compareResult == 0) {
                        return nullSafeStringComparator.compare(class1.getClassName(), class2.getClassName());
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
                    SchemaExtractorClassNodeInfo class1 = classesGraph.get(o1.getFullName());
                    if (class1 == null || class1.getTripleCount() == null) return -1;
                    SchemaExtractorClassNodeInfo class2 = classesGraph.get(o2.getFullName());
                    if (class2 == null || class2.getTripleCount() == null) return 1;
                    Long neighborInstances1 = class1.getTripleCount();
                    Long neighborInstances2 = class2.getTripleCount();
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

    protected void processSuperclasses(@Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
                                       @Nonnull SchemaExtractorRequestDto request) {

        for (Map.Entry<String, SchemaExtractorClassNodeInfo> entry : classesGraph.entrySet()) {

            SchemaClass currentClass = findClass(classes, entry.getKey());
            if (currentClass == null || THING_NAME.equals(currentClass.getLocalName())) {
                continue;
            }

            // sort neighbor list by triple count (ascending)
            List<SchemaExtractorIntersectionClassDto> neighbors = sortNeighborsByTripleCountAsc(entry.getValue().getNeighbors(), classesGraph);

            // find the class with the smallest number of instances but including all current instances
            findSuperClass(schema, currentClass, entry.getValue(), neighbors, classesGraph, classes, request);

        }
    }

    protected SchemaClass findClass(@Nonnull List<SchemaClass> classes, @Nullable String className) {
        return classes.stream().filter(
                        schemaClass -> schemaClass.getFullName().equals(className) || schemaClass.getLocalName().equals(className))
                .findFirst().orElse(null);
    }

    protected void findSuperClass(@Nonnull Schema schema, @Nonnull SchemaClass currentClass, @Nonnull SchemaExtractorClassNodeInfo currentClassInfo, @Nonnull List<SchemaExtractorIntersectionClassDto> neighbors,
                                  @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, List<SchemaClass> classes,
                                  @Nonnull SchemaExtractorRequestDto request) {

        for (SchemaExtractorIntersectionClassDto neighbor : neighbors) {
            SchemaExtractorClassNodeInfo neighborClassInfo = classesGraph.get(neighbor.getClassName());
            if (neighborClassInfo == null) {
                log.error("The Neighbor [" + neighbor + "] of the class [ " + currentClassInfo.getClassName() + " ] cannot be found in the classes list.");
                continue;
            }
            Long neighborClassTotalInstances = neighborClassInfo.getTripleCount();
            if (neighborClassTotalInstances < currentClassInfo.getTripleCount() || neighbor.getInstanceCount() < currentClassInfo.getTripleCount()
                    || neighborClassTotalInstances < request.getMinimalAnalyzedClassSize()) {
                continue;
            }
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_SUPERCLASS.name()), CHECK_SUPERCLASS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, currentClass.getFullName(), currentClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL, neighborClassInfo.getClassName(), neighborClassInfo.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, currentClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, neighborClassInfo.getClassificationProperty())
                    .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), classes.size());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getErrors().add(new SchemaExtractorError(WARNING, currentClass.getFullName(), CHECK_SUPERCLASS.name(), queryBuilder.getQueryString()));
            }
            if (!queryResponse.getResults().isEmpty()) {
                continue;
            }
            SchemaClass superClass = findClass(classes, neighbor.getClassName());
            if (!hasCyclicDependency(currentClass, superClass, classes)) {
                currentClass.getSuperClasses().add(neighbor.getClassName());
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
    protected void updateMultipleInheritanceSuperclasses(@Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
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
            if (classInfo == null || classInfo.getNeighbors().size() <= 2) {
                continue;
            }

            // 3. validate whether all neighbors are accessible
            int maxCounter = classInfo.getNeighbors().size() + 1;
            boolean accessible = validateAllNeighbors(schema, currentClass, classInfo, classes, classesGraph, request);
            while (!accessible && maxCounter != 0) {
                maxCounter--;
                accessible = validateAllNeighbors(schema, currentClass, classInfo, classes, classesGraph, request);
            }
        }
    }

    protected boolean validateAllNeighbors(@Nonnull Schema schema, @Nonnull SchemaClass currentClass, @Nonnull SchemaExtractorClassNodeInfo currentClassInfo, @Nonnull List<SchemaClass> classes,
                                           @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull SchemaExtractorRequestDto request) {
        boolean accessible = true;
        List<SchemaExtractorIntersectionClassDto> notAccessibleNeighbors = new ArrayList<>();
        for (SchemaExtractorIntersectionClassDto neighbor : currentClassInfo.getNeighbors()) {
            SchemaClass neighborClass = findClass(classes, neighbor.getClassName());
            if (THING_NAME.equals(neighborClass.getLocalName())) {
                continue;
            }
            boolean isAccessibleSuperClass = isClassAccessibleFromSuperclasses(currentClass.getFullName(), neighbor.getClassName(), classes);
            boolean isAccessibleSubClass = isClassAccessibleFromSubclasses(currentClass.getFullName(), neighbor.getClassName(), classes);
            if (!isAccessibleSuperClass && !isAccessibleSubClass) {
                accessible = false;
                notAccessibleNeighbors.add(neighbor);
            }
        }
        if (!accessible) {
            List<SchemaExtractorIntersectionClassDto> sortedNeighbors = sortNeighborsByTripleCountAsc(notAccessibleNeighbors, classesGraph);
            findSuperClass(schema, currentClass, currentClassInfo, sortedNeighbors, classesGraph, classes, request);
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

    protected void buildLabels(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, String> prefixMap) {
        request.getIncludedLabels().forEach(label -> {
            findLabelProperty(label, prefixMap);
            if (label.getLabelProperty() != null) {
                log.info(request.getCorrelationId() + " - findLabelsForClasses" + label);
                buildLabelsForSchemaElements(request, label, schema.getClasses());
                log.info(request.getCorrelationId() + " - findLabelsForProperties " + label);
                buildLabelsForSchemaElements(request, label, schema.getProperties());
            }
        });
    }

    protected void findLabelProperty(@Nonnull SchemaExtractorRequestedLabelDto label, @Nonnull Map<String, String> prefixMap) {
        // request contains full property name
        if (label.getLabelPropertyFullOrPrefix().contains("://")) {
            label.setLabelProperty(label.getLabelPropertyFullOrPrefix());
            return;
        }
        // request does not contain correctly formatted requested label information
        if (!label.getLabelPropertyFullOrPrefix().contains(":")) {
            log.error("Cannot convert requested label prefix [" + label.getLabelPropertyFullOrPrefix() + "]. Label information will not be extracted");
            return;
        }
        // request contains property in prefix:name format
        String prefix = label.getLabelPropertyFullOrPrefix().split(":")[0];
        String name = label.getLabelPropertyFullOrPrefix().split(":")[1];

        String namespace = prefixMap.entrySet().stream()
                .filter(entry -> prefix.equals(entry.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (namespace == null) {
            log.error("Cannot convert requested label prefix [" + label.getLabelPropertyFullOrPrefix() + "]. Label information will not be extracted");
            label.setLabelProperty(null);
        } else {
            label.setLabelProperty(namespace + name);
        }
    }

    protected void buildLabelsForSchemaElements(@Nonnull SchemaExtractorRequestDto request, @Nonnull SchemaExtractorRequestedLabelDto label,
                                                @Nonnull List<? extends SchemaElement> elements) {
        elements.forEach(element -> {
            QueryResponse queryResponse;
            if (label.getLanguages().isEmpty()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL.name()), FIND_LABEL)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, label.getLabelProperty())
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), elements.size());
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL_WITH_LANG.name()), FIND_LABEL_WITH_LANG)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY, label.getLabelProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildFilterWithLanguages(label.getLanguages()))
                        .withDistinct(request.getExactCountCalculations(), request.getMaxInstanceLimitForExactCount(), elements.size());
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder, false);
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String value = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_VALUE);
                String language = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_LANGUAGE);
                if (StringUtils.isNotEmpty(value)) {
                    if (StringUtils.isNotEmpty(language)) {
                        element.getLabels().add(new Label(label.getLabelPropertyFullOrPrefix(), value, language));
                    } else {
                        element.getLabels().add(new Label(label.getLabelPropertyFullOrPrefix(), value));
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

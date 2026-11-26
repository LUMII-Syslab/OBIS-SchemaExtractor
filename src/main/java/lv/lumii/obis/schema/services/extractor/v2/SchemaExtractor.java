package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v2.*;
import lv.lumii.obis.schema.services.ObjectConversionService;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.SparqlEndpointException;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.common.SparqlQueryBuilder;
import lv.lumii.obis.schema.services.common.dto.QueryResponse;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.common.dto.QueryResultObject;
import lv.lumii.obis.schema.services.extractor.dto.*;
import lv.lumii.obis.schema.services.extractor.v2.dto.*;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.*;
import static lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorMessage.MessageLevel.*;

@Slf4j
@Service
public class SchemaExtractor {

    private static final Comparator<String> nullSafeStringComparator = Comparator.nullsLast(String::compareTo);
    private static final Comparator<Long> nullSafeLongComparator = Comparator.nullsLast(Long::compareTo);

    private enum LinkedClassType {SOURCE, TARGET, PAIR_SOURCE, PAIR_TARGET}

    // Retain only property-local inference metadata and references to the existing output marker and failure NOTE.
    private record DomainRangeEstimate(boolean domain, String className, String classificationProperty,
                                       String contextClass, String contextClassificationProperty,
                                       SchemaExtractorMessage message, Runnable clearMarker) {}

    private static final List<Long> sampleLimits = List.of(1000000L, 100000L, 10000L, 1000L);
    private static final List<Long> CLASS_PROPERTY_DISCOVERY_LIMITS = List.of(1000000L, 100000L, 10000L, 3000L, 1000L);

    private static final String PROPERTY_TYPE_PROCESSING = " - calculating property type %d/%d [%s]";
    private static final String PROPERTY_PROCESSING = " - processing property %d/%d [%s]";

    private static final String INVALID_URI = "invalidURI";

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
        validateEndpointHealth(request);

        Schema schema = initializeSchema(request);
        Map<String, String> prefixMap = new HashMap<>();
        Map<String, SchemaExtractorClassNodeInfo> graphOfClasses = new HashMap<>();

        buildClasses(request, schema, graphOfClasses);
        buildProperties(request, schema, graphOfClasses);
        buildInstanceNamespaces(request, schema);
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

    // Probe both methods independently and start regular queries with GET whenever its health check succeeds.
    protected void validateEndpointHealth(@Nonnull SchemaExtractorRequestDto request) {
        request.setGetMethodAvailable(null);
        request.setPostMethodAvailable(null);
        request.setPostMethod(Boolean.FALSE);
        boolean getAvailable = sparqlEndpointProcessor.checkEndpointHealthQuery(request);
        request.setPostMethod(Boolean.TRUE);
        boolean postAvailable = sparqlEndpointProcessor.checkEndpointHealthQuery(request);
        request.setGetMethodAvailable(getAvailable);
        request.setPostMethodAvailable(postAvailable);
        if (getAvailable || postAvailable) {
            request.setPostMethod(!getAvailable);
            log.info(String.format("The endpoint [ %s ] is available - schema extractor execution [ %s ] starts with %s requests (GET available: %s; POST available: %s)",
                    SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId(),
                    request.getPostMethod() ? "POST" : "GET", getAvailable, postAvailable));
            return;
        }

        String stoppingError = String.format("The endpoint [ %s ] is not available for GET and POST requests, stopping the schema extraction for the request [ %s ]",
                SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId());
        log.error(stoppingError);
        throw new SparqlEndpointException(stoppingError);
    }

    protected void buildClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses) {
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");

        List<SchemaClass> classes = new ArrayList<>();

        // if the request does not include the list of classes - read from the SPARQL endpoint
        if (isTrue(request.getIncludedClasses().isEmpty())) {
            readClassesFromEndpoint(request, schema, classes);
            if (SchemaExtractorRequestDto.DistinctQueryMode.no.equals(request.getExactCountCalculations()) && request.getCalculateDistinctTriples()) {
                calculateDistinctInstancesForClasses(request, schema, classes);
            }
        } else {
            // else read classes from the request
            for (SchemaExtractorRequestedClassDto includedClass : request.getIncludedClasses()) {
                if (!SchemaUtil.isValidURI(includedClass.getClassName())) {
                    log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + includedClass.getClassName());
                    addInvalidUriMessage(schema, includedClass.getClassName());
                    continue;
                }
                if (!isNotExcludedResource(includedClass.getClassName(), request.getExcludedNamespaces())) {
                    continue;
                }
                if (SchemaUtil.getLongValueFromString(includedClass.getInstanceCount()) > 0L) {
                    addClass(null, includedClass.getClassName(), includedClass.getInstanceCount(), null, classes, request);
                } else {
                    for (String classificationProperty : request.getAllClassificationProperties()) {
                        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_INSTANCE_COUNT_FOR_CLASS, FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT);
                        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, includedClass.getClassName(), false);
                        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                            String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                            if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                                addClass(null, includedClass.getClassName(), instancesCountStr, classificationProperty, classes, request);
                                break;
                            }
                        } else {
                            addExtractionMessage(schema, MESSAGE, query == FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT ? SchemaExtractorMessage.FunctionalityType.DISTINCT : SchemaExtractorMessage.FunctionalityType.COUNT,
                                    includedClass.getClassName(), null, queryBuilder);
                        }
                    }

                    if (SchemaExtractorRequestDto.DistinctQueryMode.no.equals(request.getExactCountCalculations()) && request.getCalculateDistinctTriples()) {
                        for (SchemaClass clazz : classes) {
                            if (clazz.getDistinctInstances() == null) {
                                readDistinctInstanceCountForClass(request, schema, clazz);
                            }
                        }
                    }
                }
            }
            log.info(request.getCorrelationId() + String.format(" - processed %d classes from the request input", classes.size()));
        }

        if (classes.isEmpty() && request.getRequireClasses()) {
            log.error(request.getCorrelationId() + " - no classes were found, stopping the schema extractor");
            throw new SparqlEndpointException("No classes were found from the endpoint, stopping the schema extractor. If you want to proceed with the schema extraction without classes, please set [requireClasses=false]");
        }

        schema.setClasses(classes);

        // check if for all classes distinct triple counts were found
        boolean allClassesHaveDistinctCounts = false;
        if (SchemaExtractorRequestDto.DistinctQueryMode.no.equals(request.getExactCountCalculations()) && request.getCalculateDistinctTriples()) {
            allClassesHaveDistinctCounts = true;
            for (SchemaClass clazz : classes) {
                if (clazz.getDistinctInstances() == null) {
                    allClassesHaveDistinctCounts = false;
                    break;
                }
            }
        }

        if (isTrue(request.getCalculateSubClassRelations())) {

            for (SchemaClass c : classes) {
                Long classInstanceCount = c.getInstanceCount();
                if (allClassesHaveDistinctCounts) {
                    classInstanceCount = c.getDistinctInstances();
                }
                graphOfClasses.put(c.getFullName(), new SchemaExtractorClassNodeInfo(c.getFullName(), classInstanceCount, c.getClassificationProperty(), c.getIsLiteral()));
            }

            // find intersection classes
            log.info(request.getCorrelationId() + " - findIntersectionClassesAndUpdateClassNeighbors");
            findIntersectionClassesAndUpdateClassNeighbors(schema, classes, graphOfClasses, request, allClassesHaveDistinctCounts);

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
            if (SchemaExtractorRequestDto.ShowIntersectionClassesMode.yes.equals(request.getAddIntersectionClasses())) {
                for (SchemaClass clazz : classes) {
                    List<SchemaExtractorIntersectionClassDto> intersectionClasses = graphOfClasses.get(clazz.getFullName()).getNeighbors();
                    clazz.getIntersectionClasses().addAll(intersectionClasses.stream().map(
                            intersectionClass -> new SchemaIntersactionClass(intersectionClass.getClassName(), intersectionClass.getInstanceCount())
                    ).collect(Collectors.toList()));
                }
            }
        }

        if (SchemaExtractorRequestDto.BlankNodeMode.full.equals(request.getComputeBlankNodeStatistics())) {
            validateBlankNodesForClasses(schema, request);
            if (BooleanUtils.isNotFalse(schema.getHasBlankNodeSubjects())) {
                determineBlankValuesForClasses(schema, request);
            }
        }
    }

    protected void readClassesFromEndpoint(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull List<SchemaClass> classes) {
        log.info(request.getCorrelationId() + " - readClassesFromEndpoint");

        for (String classificationProperty : request.getAllClassificationProperties()) {

            // read all classes with instance counts
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_CLASSES_WITH_INSTANCE_COUNT, FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            boolean foundClassWithInstanceCounts = true;
            if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                addExtractionMessage(schema, query == FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT ? NOTE : WARNING, query == FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT ? SchemaExtractorMessage.FunctionalityType.DISTINCT : SchemaExtractorMessage.FunctionalityType.STRUCT,
                        null, null, queryBuilder,
                        query == FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT ? "Reading classes with distinct instance counts not successful" : "Reading classes with instance counts not successful");
                foundClassWithInstanceCounts = false;
            } else {
                List<SchemaClass> resultClasses = processClassesWithEndpointData(queryResponse.getResults(), request, classificationProperty, schema);
                classes.addAll(resultClasses);
                // validate whether statistics query returned the full list of classes
                if (request.getValidateClassesList()) {
                    validateClassesFromEndpoint(request, schema, classes, classificationProperty, resultClasses.size());
                } else {
                    log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", resultClasses.size(), classificationProperty));
                }
            }
            // if statistics query returns error then read all classes and read instance count separately for each class
            if (!foundClassWithInstanceCounts) {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLASSES.name()), FIND_CLASSES)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                    addExtractionMessage(schema, ERROR, SchemaExtractorMessage.FunctionalityType.STRUCT,
                            null, null, queryBuilder,
                            "Reading classes without instance counts not successful");
                } else {
                    int totalClasses = 0;
                    for (QueryResult queryResult : queryResponse.getResults()) {
                        QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                        if (classNameObject == null) {
                            continue;
                        }
                        if (!classNameObject.getIsLiteral() && !SchemaUtil.isValidURI(classNameObject.getValue())) {
                            log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + classNameObject.getValue());
                            addInvalidUriMessage(schema, classNameObject.getValue());
                            continue;
                        }
                        if (!isNotExcludedResource(classNameObject.getValue(), request.getExcludedNamespaces())) {
                            continue;
                        }
                        boolean addedClass = readInstanceCountForClass(request, schema, classNameObject, classificationProperty, classes);
                        if (addedClass) totalClasses++;
                    }
                    log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", totalClasses, classificationProperty));
                }
            }
        }
        if (!classes.isEmpty()) {
            log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        }
    }

    protected void calculateDistinctInstancesForClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull List<SchemaClass> classes) {
        log.info(request.getCorrelationId() + " - calculateDistinctInstancesForClasses");

        boolean foundClassWithInstanceCounts = true;
        for (String classificationProperty : request.getAllClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT.name()), FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                        null, null, queryBuilder);
                foundClassWithInstanceCounts = false;
            } else {
                for (QueryResult queryResult : queryResponse.getResults()) {
                    QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                    if (classNameObject == null) {
                        continue;
                    }
                    SchemaClass clazz = findClass(classes, classNameObject.getValue());
                    if (clazz != null) {
                        Long distinctCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        if (distinctCount > 0L) {
                            clazz.setDistinctInstances(distinctCount);
                        }
                    }
                }
            }
        }
        if (!foundClassWithInstanceCounts) {
            for (SchemaClass clazz : classes) {
                if (clazz.getDistinctInstances() == null) {
                    readDistinctInstanceCountForClass(request, schema, clazz);
                }
            }
        }
    }

    protected void validateClassesFromEndpoint(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull List<SchemaClass> classes,
                                               @Nonnull String classificationProperty, int classesCountFromStatisticsQuery) {
        log.info(request.getCorrelationId() + " - validateListOfClasses");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLASSES.name()), FIND_CLASSES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

        // if cannot find all classes then use the classes found by statistics query
        if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
            addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.STRUCT,
                    null, null, queryBuilder,
                    "Reading classes without instance count (as a cross-check) not successful");
            log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", classesCountFromStatisticsQuery, classificationProperty));
        } else {
            // combine results of both queries
            int classesFromSimpleQuery = 0;
            for (QueryResult queryResult : queryResponse.getResults()) {
                QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (classNameObject == null) {
                    continue;
                }
                if (!classNameObject.getIsLiteral() && !SchemaUtil.isValidURI(classNameObject.getValue())) {
                    log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + classNameObject.getValue());
                    continue;
                }
                if (!isNotExcludedResource(classNameObject.getValue(), request.getExcludedNamespaces())) {
                    continue;
                }
                if (findClass(classes, classNameObject.getValue()) == null) {
                    boolean addedClass = readInstanceCountForClass(request, schema, classNameObject, classificationProperty, classes);
                    if (addedClass) classesFromSimpleQuery++;
                }
            }
            if (classesFromSimpleQuery > 0) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.STRUCT,
                        null, null, queryBuilder,
                        "Found " + classesFromSimpleQuery + " more classes than FIND_CLASSES_WITH_INSTANCE_COUNT");
                log.info(request.getCorrelationId() + String.format(" - FIND_CLASSES found %d more classes than FIND_CLASSES_WITH_INSTANCE_COUNT for classification property %s", classesFromSimpleQuery, classificationProperty));
            }
            log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", classesCountFromStatisticsQuery + classesFromSimpleQuery, classificationProperty));
        }
    }

    // Bind the complete RDF class term so fallback counting preserves literal classification values.
    // Select the class count diagnostic from the actual normal or DISTINCT query while preserving class admission.
    protected boolean readInstanceCountForClass(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull QueryResultObject classNameObject,
                                                @Nonnull String classificationProperty, @Nonnull List<SchemaClass> classes) {
        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_INSTANCE_COUNT_FOR_CLASS, FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT);
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, classNameObject.getFullName(), classNameObject.getIsLiteral());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                addClass(classNameObject, classNameObject.getValue(), instancesCountStr, classificationProperty, classes, request);
                return true;
            }
        } else {
            addExtractionMessage(schema, MESSAGE, query == FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT ? SchemaExtractorMessage.FunctionalityType.DISTINCT : SchemaExtractorMessage.FunctionalityType.COUNT,
                    classNameObject.getValue(), null, queryBuilder);
        }
        return false;
    }

    // Preserve literal class terms when recounting distinct instances instead of formatting every class as an IRI.
    protected void readDistinctInstanceCountForClass(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull SchemaClass clazz) {
        log.info(request.getCorrelationId() + " - findDistinctInstanceCountForClass");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT.name()), FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, clazz.getFullName(), clazz.getIsLiteral());
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            Long distinctCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (distinctCount > 0L) {
                clazz.setDistinctInstances(distinctCount);
            }
        } else {
            addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    clazz.getFullName(), null, queryBuilder);
        }
    }

    // Report invalid returned class URIs through the common policy without changing accepted class terms.
    protected List<SchemaClass> processClassesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request,
                                                               @Nonnull String classificationProperty, @Nonnull Schema schema) {
        List<SchemaClass> classes = new ArrayList<>();
        for (QueryResult queryResult : queryResults) {
            QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            if (classNameObject == null) {
                continue;
            }
            if (!classNameObject.getIsLiteral() && !SchemaUtil.isValidURI(classNameObject.getValue())) {
                log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + classNameObject.getValue());
                addInvalidUriMessage(schema, classNameObject.getValue());
                continue;
            }
            if (!isNotExcludedResource(classNameObject.getValue(), request.getExcludedNamespaces())) {
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
            if (classificationProperty == null) {
                classificationProperty = request.getPrincipalClassificationProperties().contains(RDF_TYPE)
                        ? RDF_TYPE : request.getPrincipalClassificationProperties().stream().findFirst().orElse(RDF_TYPE);
            }
            classEntry.setClassificationProperty(classificationProperty);
            classes.add(classEntry);
        }
    }

    protected void buildProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses) {
        // find all properties with triple count
        log.info(request.getCorrelationId() + " - findAllPropertiesWithTripleCount");

        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();

        // if the request does not include the list of properties - read from the SPARQL endpoint
        if (isTrue(request.getIncludedProperties().isEmpty())) {
            readPropertiesFromEndpoint(request, schema, properties);
            if (SchemaExtractorRequestDto.DistinctQueryMode.no.equals(request.getExactCountCalculations()) && request.getCalculateDistinctTriples()) {
                calculateDistinctTriplesForProperties(request, schema, properties);
            }
        } else {
            // else read properties from the request
            for (SchemaExtractorRequestedPropertyDto includedProperty : request.getIncludedProperties()) {
                if (!SchemaUtil.isValidURI(includedProperty.getPropertyName())) {
                    log.error(request.getCorrelationId() + " - invalid property URI will not be processed - " + includedProperty.getPropertyName());
                    addInvalidUriMessage(schema, includedProperty.getPropertyName());
                    continue;
                }
                if (SchemaUtil.getLongValueFromString(includedProperty.getInstanceCount()) > 0L) {
                    addProperty(includedProperty.getPropertyName(), includedProperty.getInstanceCount(), properties, request);
                } else {
                    SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_TRIPLE_COUNT_FOR_PROPERTY, FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT);
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, includedProperty.getPropertyName(), false);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                        String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                        if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                            addProperty(includedProperty.getPropertyName(), instancesCountStr, properties, request);
                        }
                    } else {
                        addExtractionMessage(schema, query == FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT ? NOTE : WARNING, query == FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT ? SchemaExtractorMessage.FunctionalityType.DISTINCT : SchemaExtractorMessage.FunctionalityType.COUNT,
                                includedProperty.getPropertyName(), null, queryBuilder,
                                query == FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT ? "Distinct triple count for a property failed" : "Triple count for a property failed");
                    }
                }
            }
            if (SchemaExtractorRequestDto.DistinctQueryMode.no.equals(request.getExactCountCalculations()) && request.getCalculateDistinctTriples()) {
                for (SchemaExtractorPropertyNodeInfo property : properties.values()) {
                    if (property.getDistinctTriples() == null) {
                        readDistinctTripleCountForProperty(request, schema, property);
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

    protected void readPropertiesFromEndpoint(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - readPropertiesFromEndpoint");

        // read triple counts for all properties
        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTIES_WITH_TRIPLE_COUNT, FIND_PROPERTIES_WITH_TRIPLE_COUNT_DISTINCT);
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        boolean foundPropertiesWithTripleCounts = true;
        if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
            addExtractionMessage(schema, WARNING, SchemaExtractorMessage.FunctionalityType.STRUCT,
                    null, null, queryBuilder);
            foundPropertiesWithTripleCounts = false;
        } else {
            properties.putAll(processPropertiesWithEndpointData(queryResponse.getResults(), request, schema));
            // validate whether statistics query returned the full list of properties
            if (request.getValidatePropertiesList()) {
                validatePropertiesFromEndpoint(request, schema, properties);
            }
        }

        // if statistics query returns error then read all properties and read triple count separately for each property
        if (!foundPropertiesWithTripleCounts) {
            Set<String> propertiesList;
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTIES.name()), FIND_PROPERTIES);
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors() || queryResponse.getResults().isEmpty()) {
                addExtractionMessage(schema, WARNING, SchemaExtractorMessage.FunctionalityType.STRUCT,
                        null, null, queryBuilder,
                        "Reading properties without triple counts not successful");
                propertiesList = readPropertiesForClasses(request, schema);
            } else {
                propertiesList = addPropertyNames(queryResponse.getResults(), schema);
            }
            for (String propertyName : propertiesList) {
                readTripleCountForProperty(request, schema, propertyName, properties);
            }
        }

        if (properties.isEmpty()) {
            log.error(request.getCorrelationId() + " - no properties were found, stopping the schema extractor");
        } else {
            log.info(request.getCorrelationId() + String.format(" - found total %d properties", properties.size()));
        }
    }

    protected void calculateDistinctTriplesForProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - calculateDistinctTriplesForProperties");

        // read triple counts for all properties
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTIES_WITH_TRIPLE_COUNT_DISTINCT.name()), FIND_PROPERTIES_WITH_TRIPLE_COUNT_DISTINCT);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        boolean foundPropertiesWithTripleCounts = true;
        if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
            addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    null, null, queryBuilder);
            foundPropertiesWithTripleCounts = false;
        } else {
            for (QueryResult queryResult : queryResponse.getResults()) {
                String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
                if (propertyName == null) {
                    continue;
                }
                SchemaExtractorPropertyNodeInfo property = properties.get(propertyName);
                if (property != null) {
                    Long distinctCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (distinctCount > 0L) {
                        property.setDistinctTriples(distinctCount);
                    }
                }
            }
        }

        if (!foundPropertiesWithTripleCounts) {
            for (SchemaExtractorPropertyNodeInfo property : properties.values()) {
                if (property.getDistinctTriples() == null) {
                    readDistinctTripleCountForProperty(request, schema, property);
                }
            }
        }
    }

    protected void validatePropertiesFromEndpoint(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - validateListOfProperties");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTIES.name()), FIND_PROPERTIES);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        // if cannot find all properties then use the properties found by statistics query
        if (queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
            addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.STRUCT,
                    null, null, queryBuilder,
                    "Cross-check reading properties without triple count not successful");
        } else {
            // combine results of both queries
            int propertiesFromSimpleQuery = 0;
            for (QueryResult queryResult : queryResponse.getResults()) {
                String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
                if (!SchemaUtil.isValidURI(propertyName)) {
                    log.error(request.getCorrelationId() + " - invalid property URI will not be processed - " + propertyName);
                    continue;
                }
                if (!properties.containsKey(propertyName)) {
                    readTripleCountForProperty(request, schema, propertyName, properties);
                    propertiesFromSimpleQuery++;
                }
            }
            if (propertiesFromSimpleQuery > 0) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.STRUCT,
                        null, null, queryBuilder,
                        "Found " + propertiesFromSimpleQuery + " more properties than FIND_PROPERTIES_WITH_TRIPLE_COUNT");
                log.info(request.getCorrelationId() + String.format(" - FIND_PROPERTIES found %d more properties than FIND_PROPERTIES_WITH_TRIPLE_COUNT", propertiesFromSimpleQuery));
            }
        }
    }

    @Nonnull
    protected Set<String> readPropertiesForClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        Set<String> properties = new HashSet<>();

        for (SchemaClass clazz : schema.getClasses()) {
            properties.addAll(readPropertiesForClass(request, schema, clazz, false));
            properties.addAll(readPropertiesForClass(request, schema, clazz, true));
        }

        return properties;
    }

    // Try counted discovery first at each scope and base diagnostics on the name-only attempts or an empty success.
    private Set<String> readPropertiesForClass(SchemaExtractorRequestDto request, Schema schema, SchemaClass clazz, boolean incoming) {
        Iterator<Long> retryLimits = CLASS_PROPERTY_DISCOVERY_LIMITS.iterator();
        String direction = incoming ? "Incoming" : "Outgoing";
        SparqlQueryBuilder lastFailure = null;
        Long limit = null;

        while (true) {
            SparqlQueryBuilder query = classPropertyDiscoveryQuery(request, clazz, incoming, limit, true);
            QueryResponse response = sparqlEndpointProcessor.read(request, query);
            Map<String, Long> counts = readGroupedCounts(response, "property", row -> row.getValue("property"), true);
            if (counts == null) {
                query = classPropertyDiscoveryQuery(request, clazz, incoming, limit, false);
                response = sparqlEndpointProcessor.read(request, query);
            }

            if (response.hasErrors()) {
                lastFailure = query;
                if (!retryLimits.hasNext()) break;
                limit = retryLimits.next();
                continue;
            }
            if (counts != null) retainClassPropertyDiscoveryCounts(request, clazz, incoming, limit, counts);
            if (response.getResults().isEmpty()) {
                addExtractionMessage(schema, incoming ? NOTE : MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT,
                        clazz.getFullName(), null, query, "Empty results found. Context: property retrieval via classes");
                return Collections.emptySet();
            }
            if (counts != null) return counts.keySet();
            if (lastFailure != null) {
                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT,
                        clazz.getFullName(), null, lastFailure,
                        direction + " properties for the class were checked using a limited sample (LIMIT " + limit + ").");
            }
            return addPropertyNames(response.getResults(), schema);
        }

        addExtractionMessage(schema, incoming ? MESSAGE : WARNING, SchemaExtractorMessage.FunctionalityType.STRUCT,
                clazz.getFullName(), null, lastFailure, direction + " properties for the class could not be determined.");
        return Collections.emptySet();
    }

    // Keep discovery counts and successful empty results separate from the live CP relations.
    private void retainClassPropertyDiscoveryCounts(SchemaExtractorRequestDto request, SchemaClass clazz, boolean incoming,
                                                     Long limit, Map<String, Long> counts) {
        counts.keySet().removeIf(name -> !isNotExcludedResource(name, request.getExcludedNamespaces()) || !SchemaUtil.isValidURI(name));
        SchemaClass.PropertyDiscoveryCounts discoveryCounts = new SchemaClass.PropertyDiscoveryCounts(counts, limit);
        if (incoming) clazz.setIncomingPropertyDiscoveryCounts(discoveryCounts);
        else clazz.setOutgoingPropertyDiscoveryCounts(discoveryCounts);
    }

    private SparqlQueryBuilder classPropertyDiscoveryQuery(SchemaExtractorRequestDto request, SchemaClass clazz,
                                                           boolean incoming, Long limit, boolean counted) {
        SchemaExtractorQueries name;
        if (counted) {
            name = countedClassPropertyQueryName(request, incoming, limit);
        } else if (incoming) {
            name = limit == null ? FIND_INCOMING_PROPERTIES_FOR_CLASS : FIND_INCOMING_PROPERTIES_FOR_CLASS_WITH_LIMIT;
        } else {
            name = limit == null ? FIND_PROPERTIES_FOR_CLASS : FIND_PROPERTIES_FOR_CLASS_WITH_LIMIT;
        }
        String classBinding = incoming ? SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL : SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL;
        SparqlQueryBuilder query = new SparqlQueryBuilder(request.getQueries().get(name.name()), name)
                .withContextParam(classBinding, clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty());
        if (counted) {
            query.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES, StringUtils.EMPTY)
                    .withContextParam("<propertyValuesClause>", StringUtils.EMPTY);
        }
        if (limit != null) query.withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
        return query;
    }

    // Apply the common invalid-URI diagnostic while preserving empty-name skipping and property-name validation.
    @Nonnull
    protected Set<String> addPropertyNames(@Nonnull List<QueryResult> queryResults, @Nonnull Schema schema) {
        Set<String> properties = new HashSet<>();
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            if (StringUtils.isEmpty(propertyName)) continue;
            if (SchemaUtil.isValidURI(propertyName)) {
                properties.add(propertyName);
            } else {
                addInvalidUriMessage(schema, propertyName);
            }
        }
        return properties;
    }

    // Use the selected count query for diagnostic policy and metadata while preserving property retention.
    protected void readTripleCountForProperty(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull String propertyName,
                                              @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {

        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_TRIPLE_COUNT_FOR_PROPERTY, FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT);
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                addProperty(propertyName, instancesCountStr, properties, request);
            }
        } else {
            addProperty(propertyName, null, properties, request);
            addExtractionMessage(schema, query == FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT ? NOTE : WARNING, query == FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT ? SchemaExtractorMessage.FunctionalityType.DISTINCT : SchemaExtractorMessage.FunctionalityType.COUNT,
                    propertyName, null, queryBuilder,
                    query == FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT ? "Distinct triple count for a property failed" : "Triple count for a property failed");
        }
    }

    protected void readDistinctTripleCountForProperty(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property) {

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT.name()), FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            Long distinctCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (distinctCount > 0L) {
                property.setDistinctTriples(distinctCount);
            }
        } else {
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    property.getPropertyName(), null, queryBuilder);
        }
    }

    // Apply the common invalid-URI policy without changing property result processing.
    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processPropertiesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request,
                                                                                             @Nonnull Schema schema) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            if (!SchemaUtil.isValidURI(propertyName)) {
                log.error(request.getCorrelationId() + " - invalid property URI will not be processed - " + propertyName);
                addInvalidUriMessage(schema, propertyName);
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
            if (instancesCountStr != null) {
                property.setTripleCount(SchemaUtil.getLongValueFromString(instancesCountStr));
            }
        }
    }

    // Finalize paired value counts before dependent extraction and enrichment steps.
    protected void enrichProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema,
                                    @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request) {
        int totalCountOfProperties = properties.size();

        Map<String, Long> subjectsMap = null, objectsMap = null, blankNodeObjects = null, blankNodeSubjects = null;

        if (isTrue(SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.yes.equals(request.getIncludeDistinctSubjectsAndObjects()))
                || isTrue(SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.propertyLevel.equals(request.getIncludeDistinctSubjectsAndObjects()))) {
            subjectsMap = determineDistinctSubjectsForAllProperties(schema, request);
            objectsMap = determineDistinctObjectsForAllProperties(schema, request);
        }
        if (isTrue(SchemaExtractorRequestDto.BlankNodeMode.full.equals(request.getComputeBlankNodeStatistics()))
                && BooleanUtils.isNotFalse(schema.getHasBlankNodeSubjects())) {
            blankNodeSubjects = determineBlankNodeSubjectsForAllProperties(schema, request, properties);
        }
        boolean calculateBlankNodeObjects = false;
        if (isTrue(SchemaExtractorRequestDto.BlankNodeMode.targetsOnly.equals(request.getComputeBlankNodeStatistics()))
                || isTrue(SchemaExtractorRequestDto.BlankNodeMode.full.equals(request.getComputeBlankNodeStatistics()))) {
            validateBlankNodesForProperties(schema, request);
            if (BooleanUtils.isNotFalse(schema.getHasBlankNodeObjects())) {
                blankNodeObjects = determineBlankNodeObjectsForAllProperties(schema, request, properties);
                calculateBlankNodeObjects = true;
            }
        }

        // calculate property type separately (this information later is needed in other calculations)
        int currentPropertyInd = 1;
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            log.info(request.getCorrelationId() + String.format(PROPERTY_TYPE_PROCESSING, currentPropertyInd++, totalCountOfProperties, property.getPropertyName()));

            determinePropertyValueTripleCounts(schema, property, request, totalCountOfProperties);
            determinePropertyType(property, request);
        }
        for (SchemaExtractorPropertyNodeInfo property : properties.values()) {
            if (isTrue(SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.yes.equals(request.getIncludeDistinctSubjectsAndObjects()))
                    || isTrue(SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.propertyLevel.equals(request.getIncludeDistinctSubjectsAndObjects()))) {
                if (subjectsMap == null || subjectsMap.isEmpty()) {
                    determineDistinctPropertySubjects(schema, property, request);
                } else {
                    property.setDistinctSubjectsCount(subjectsMap.get(property.getPropertyName()));
                }
                if (objectsMap == null || objectsMap.isEmpty()) {
                    determineDistinctPropertyObjects(schema, property, request);
                } else {
                    property.setDistinctObjectsCount(objectsMap.get(property.getPropertyName()));
                }
            }
            if (isTrue(SchemaExtractorRequestDto.BlankNodeMode.full.equals(request.getComputeBlankNodeStatistics()))
                    && BooleanUtils.isNotFalse(schema.getHasBlankNodeSubjects())) {
                if (blankNodeSubjects == null || blankNodeSubjects.isEmpty()) {
                    determinePropertyBlankNodeSubjects(schema, property, request);
                } else {
                    property.setBlankNodeSubjects(blankNodeSubjects.get(property.getPropertyName()));
                }
            }
            if (calculateBlankNodeObjects && isNotFalse(nonLiteralObjectsExistence(property))) {
                if (blankNodeObjects.isEmpty()) {
                    determinePropertyBlankNodeObjects(schema, property, request);
                } else {
                    property.setBlankNodeObjects(blankNodeObjects.get(property.getPropertyName()));
                }
            }

        }
        Map<SchemaClass, Boolean> classBlankChecks = new IdentityHashMap<>();
        Set<String> propertiesWithoutClassChecks = Collections.emptySet();
        if (request.getIncludedProperties().isEmpty()) {
            Map<String, Long> cachedSubjects = subjectsMap, cachedObjects = objectsMap;
            Map<String, Long> cachedBlankSubjects = blankNodeSubjects, cachedBlankObjects = blankNodeObjects;
            propertiesWithoutClassChecks = expandAutomaticPropertyMappings(schema, properties, request, classBlankChecks,
                    property -> initializeDiscoveredProperty(request, schema, property, properties,
                            cachedSubjects, cachedObjects, cachedBlankSubjects, cachedBlankObjects));
        } else {
            determinePropertySources(schema, properties, request, classBlankChecks);
            determinePropertyTargets(schema, properties, request, classBlankChecks);
            if (isTrue(request.getCalculatePropertyPropertyRelations())) determinePropertyPropertyRelations(schema, properties, request);
        }
        totalCountOfProperties = properties.size();
        // enrich property with detailed information
        currentPropertyInd = 1;
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            log.info(request.getCorrelationId() + String.format(PROPERTY_PROCESSING, currentPropertyInd++, totalCountOfProperties, property.getPropertyName()));

            boolean includeClassContext = !propertiesWithoutClassChecks.contains(entry.getKey());
            if (includeClassContext) {
                determinePropertySourceValueTripleCounts(schema, property, request, totalCountOfProperties);
                if (isTrue(request.getCalculateSourceAndTargetPairs()) && !hasConfirmedNoNonLiteralObjects(property)
                        && isNotFalse(nonLiteralObjectsExistence(property))) {
                    determinePropertySourceTargetPairs(schema, property, request, totalCountOfProperties);
                }
                if (SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.yes.equals(request.getIncludeDistinctSubjectsAndObjects())) {
                    determineDistinctPropertySubjectsOnSourceClassLevel(schema, property, request);
                    determineDistinctPropertyObjectsOnTargetClassLevel(schema, property, request);
                }
                if (isTrue(request.getCalculateClosedClassSets())) {
                    determinePropertyClosedSources(schema, property, request, totalCountOfProperties);
                    determinePropertyClosedTargets(schema, property, request, totalCountOfProperties);
                    determinePropertyClosedTargetsOnSourceClassLevel(schema, property, request, totalCountOfProperties);
                    determinePropertyClosedSourcesOnTargetClassLevel(schema, property, request, totalCountOfProperties);
                }
            }

            if (isNotTrue(property.getIsObjectProperty())) {
                switch (request.getCalculateDataTypes()) {
                    case propertyLevelOnly:
                        determinePropertyDataTypes(schema, property, request, totalCountOfProperties);
                        break;
                    case propertyLevelAndClassContext:
                        determinePropertyDataTypes(schema, property, request, totalCountOfProperties);
                        if (includeClassContext) determinePropertySourceDataTypes(schema, property, request, totalCountOfProperties);
                        break;
                    case none:
                        // do not calculate data types
                    default:
                        break;
                }

            }

            // Share property-local inference evidence between the existing domain/range and importance passes.
            if (includeClassContext) {
                determineDomainsRangesAndImportanceIndexes(schema, property, schema.getClasses(), graphOfClasses, request, totalCountOfProperties);
            }

            switch (request.getCalculateCardinalities()) {
                case propertyLevelOnly:
                    determinePropertyMaxCardinality(schema, property, request, totalCountOfProperties);
                    if (isTrue(nonLiteralObjectsExistence(property))) {
                        determinePropertyInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                    }
                    break;
                case propertyLevelAndClassContext:
                    determinePropertyMaxCardinality(schema, property, request, totalCountOfProperties);
                    if (includeClassContext) {
                        determinePropertySourceMaxCardinality(schema, property, request, totalCountOfProperties);
                        determinePropertySourceMinCardinality(schema, property, request, totalCountOfProperties);
                    }
                    if (isTrue(nonLiteralObjectsExistence(property))) {
                        determinePropertyInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                        if (includeClassContext) {
                            determinePropertyTargetsInverseMinCardinality(schema, property, request, totalCountOfProperties);
                            determinePropertyTargetsInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                        }
                    }
                    break;
                case none:
                    // do not calculate cardinalities
                default:
                    break;
            }
        }
    }

    // Fix the CP input scope while discovering new predicates, then complete PP over the expanded list.
    private Set<String> expandAutomaticPropertyMappings(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                         SchemaExtractorRequestDto request, Map<SchemaClass, Boolean> blankChecks,
                                                         java.util.function.Consumer<SchemaExtractorPropertyNodeInfo> initializeProperty) {
        Map<String, SchemaExtractorPropertyNodeInfo> originalProperties = new LinkedHashMap<>(properties);
        ClassPropertyDiscovery discovery = new ClassPropertyDiscovery(originalProperties.keySet(), new LinkedHashSet<>());
        ClassPropertyContext firstSources = determineClassPropertyMappings(schema, originalProperties, request, false, blankChecks, discovery, null);
        ClassPropertyContext firstTargets = determinePropertyTargets(schema, originalProperties, request, blankChecks, discovery, null);

        Map<String, SchemaExtractorPropertyNodeInfo> additions = new LinkedHashMap<>();
        java.util.function.Consumer<SchemaExtractorPropertyNodeInfo> initializeAddition = property -> {
            additions.put(property.getPropertyName(), property);
            initializeProperty.accept(property);
        };
        for (String name : discovery.newNames()) {
            SchemaExtractorPropertyNodeInfo property = new SchemaExtractorPropertyNodeInfo();
            property.setPropertyName(name);
            properties.put(name, property);
            initializeAddition.accept(property);
        }

        boolean checkPp = isTrue(request.getCalculatePropertyPropertyRelations());
        if (checkPp) discoverPropertyPropertyRelations(schema, properties, request, initializeAddition);

        Set<String> withoutClassChecks = Collections.emptySet();
        if (!additions.isEmpty()) {
            if (isTrue(request.getCheckClassPropertyRelationsForNewProperties())) {
                determineClassPropertyMappings(schema, additions, request, false, blankChecks, null, firstSources);
                determinePropertyTargets(schema, additions, request, blankChecks, null, firstTargets);
            } else {
                retainObservedClassLinks(firstSources, additions);
                retainObservedClassLinks(firstTargets, additions);
                withoutClassChecks = additions.keySet();
            }
        }
        if (checkPp) completePropertyPropertyRelations(schema, properties, request);
        return withoutClassChecks;
    }

    // Preserve verified links without claiming full coverage for predicates outside the first pass.
    private void retainObservedClassLinks(ClassPropertyContext firstPass, Map<String, SchemaExtractorPropertyNodeInfo> additions) {
        boolean scopeGrew = !firstPass.target || additions.values().stream().anyMatch(property -> !hasConfirmedNoNonLiteralObjects(property));
        firstPass.classes.forEach((clazz, state) -> {
            if (scopeGrew && state.unrestrictedClassCoverage != 7) {
                if (firstPass.target) {
                    clazz.setIncomingPropertiesFullList(false);
                    if (Integer.valueOf(7).equals(clazz.getIncomingPropertiesOK())) clazz.setIncomingPropertiesOK(null);
                } else {
                    clazz.setOutgoingPropertiesFullList(false);
                    if (Integer.valueOf(7).equals(clazz.getOutgoingPropertiesOK())) clazz.setOutgoingPropertiesOK(null);
                }
            }
            classPropertyLinks(firstPass, clazz).forEach((name, count) -> {
                SchemaExtractorPropertyNodeInfo property = additions.get(name);
                if (property != null && (!firstPass.target || !hasConfirmedNoNonLiteralObjects(property))) {
                    publishClassPropertyLink(firstPass, clazz, property, count);
                }
            });
        });
        if (firstPass.target) additions.values().forEach(this::correctSampledTargetAbsence);
    }

    // Initialize only the new predicate and use statistics from successful discovery-wide queries where available.
    private void initializeDiscoveredProperty(SchemaExtractorRequestDto request, Schema schema,
                                              SchemaExtractorPropertyNodeInfo property, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                              Map<String, Long> subjectsMap, Map<String, Long> objectsMap,
                                              Map<String, Long> blankNodeSubjects, Map<String, Long> blankNodeObjects) {
        String name = property.getPropertyName();
        if (property.getTripleCount() == null) readTripleCountForProperty(request, schema, name, properties);
        if (SchemaExtractorRequestDto.DistinctQueryMode.no.equals(request.getExactCountCalculations())
                && isTrue(request.getCalculateDistinctTriples()) && property.getDistinctTriples() == null) {
            readDistinctTripleCountForProperty(request, schema, property);
        }
        determinePropertyValueTripleCounts(schema, property, request, properties.size());
        determinePropertyType(property, request);

        if (SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.yes.equals(request.getIncludeDistinctSubjectsAndObjects())
                || SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.propertyLevel.equals(request.getIncludeDistinctSubjectsAndObjects())) {
            Long subjects = subjectsMap == null ? null : subjectsMap.get(name);
            Long objects = objectsMap == null ? null : objectsMap.get(name);
            if (subjects == null) determineDistinctPropertySubjects(schema, property, request);
            else property.setDistinctSubjectsCount(subjects);
            if (objects == null) determineDistinctPropertyObjects(schema, property, request);
            else property.setDistinctObjectsCount(objects);
        }

        // A successful aggregate seeds zero for the original properties, so a nonempty map confirms coverage.
        if (request.getComputeBlankNodeStatistics() == SchemaExtractorRequestDto.BlankNodeMode.full
                && isNotFalse(schema.getHasBlankNodeSubjects())) {
            if (blankNodeSubjects == null || blankNodeSubjects.isEmpty()) determinePropertyBlankNodeSubjects(schema, property, request);
            else property.setBlankNodeSubjects(blankNodeSubjects.getOrDefault(name, 0L));
        }
        if ((SchemaExtractorRequestDto.BlankNodeMode.targetsOnly.equals(request.getComputeBlankNodeStatistics())
                || SchemaExtractorRequestDto.BlankNodeMode.full.equals(request.getComputeBlankNodeStatistics()))
                && isNotFalse(schema.getHasBlankNodeObjects()) && isNotFalse(nonLiteralObjectsExistence(property))) {
            if (blankNodeObjects == null || blankNodeObjects.isEmpty()) determinePropertyBlankNodeObjects(schema, property, request);
            else property.setBlankNodeObjects(blankNodeObjects.getOrDefault(name, 0L));
        }
    }

    // Resolve both value counts before reporting failures or issuing existence fallbacks.
    protected void determinePropertyValueTripleCounts(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyValueTripleCounts [" + property.getPropertyName() + "]");
        determinePropertyValueCounts(schema, property, null, request, true, true);
        determinePropertyObjectTripleRatio(schema, property, null, request);
    }

    // Keep the single-side entry point on the shared numeric-count and existence-fallback path.
    protected void determinePropertyObjectTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        determinePropertyValueCounts(schema, property, null, request, true, false);
    }

    // Keep the single-side entry point on the shared numeric-count and existence-fallback path.
    protected void determinePropertyDataTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        determinePropertyValueCounts(schema, property, null, request, false, true);
    }

    // Share paired count resolution between whole-property and source scopes, excluding based totals from subtraction.
    private void determinePropertyValueCounts(Schema schema, SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass,
                                               SchemaExtractorRequestDto request, boolean readObjects, boolean readLiterals) {
        SparqlQueryBuilder objectFailure = null, literalFailure = null;
        if (readObjects) {
            Long objectCount;
            if (sourceClass != null && Long.valueOf(0).equals(property.getObjectTripleCount())) objectCount = 0L;
            else {
                SparqlQueryBuilder query = propertyValueQuery(request, property, sourceClass,
                        sourceClass == null ? COUNT_PROPERTY_URL_VALUES : COUNT_PROPERTY_URL_VALUES_FOR_SOURCE,
                        sourceClass == null ? COUNT_PROPERTY_URL_VALUES_DISTINCT : COUNT_PROPERTY_URL_VALUES_FOR_SOURCE_DISTINCT);
                objectCount = readPropertyValueCount(request, query);
                if (objectCount == null) objectFailure = query;
            }
            if (objectCount != null) storePropertyValueCount(property, sourceClass, false, objectCount);
        }
        if (readLiterals) {
            Long literalCount;
            if (sourceClass != null && Long.valueOf(0).equals(property.getDataTripleCount())) literalCount = 0L;
            else {
                SparqlQueryBuilder query = propertyValueQuery(request, property, sourceClass,
                        sourceClass == null ? COUNT_PROPERTY_LITERAL_VALUES : COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE,
                        sourceClass == null ? COUNT_PROPERTY_LITERAL_VALUES_DISTINCT : COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE_DISTINCT);
                literalCount = readPropertyValueCount(request, query);
                if (literalCount == null) literalFailure = query;
            }
            if (literalCount != null) storePropertyValueCount(property, sourceClass, true, literalCount);
        }

        // When both counts fail, a confirmed zero from either check can still make subtraction possible.
        boolean bothFailed = objectFailure != null && literalFailure != null;
        if (bothFailed) {
            checkPropertyValueCount(schema, property, sourceClass, request, false);
            checkPropertyValueCount(schema, property, sourceClass, request, true);
        }
        if (objectFailure != null) finishFailedPropertyValueCount(schema, property, sourceClass, request, false, bothFailed, objectFailure);
        if (literalFailure != null) finishFailedPropertyValueCount(schema, property, sourceClass, request, true, bothFailed, literalFailure);
    }

    private void finishFailedPropertyValueCount(Schema schema, SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass,
                                               SchemaExtractorRequestDto request, boolean literal, boolean checked, SparqlQueryBuilder failedQuery) {
        Long total = sourceClass == null ? nonNegativeCount(property.getTripleCount())
                : sourceClass.getTripleCountBase() == null ? nonNegativeCount(sourceClass.getTripleCount()) : null;
        Long otherCount = nonNegativeCount(propertyValueCount(property, sourceClass, !literal));
        String relatedEntity = sourceClass == null ? null : sourceClass.getClassName();
        if (nonNegativeCount(propertyValueCount(property, sourceClass, literal)) == null && total != null && otherCount != null && otherCount <= total) {
            long count = total - otherCount;
            if (count > 0 || !hasExplicitPropertyValue(property, sourceClass, literal)) {
                storePropertyValueCount(property, sourceClass, literal, count);
                addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.URL_LITERAL,
                        property.getPropertyName(), relatedEntity, failedQuery,
                        "Direct URL/LITERAL value count failed; the count estimated from the total and the other triple count");
                return;
            }
        }
        addExtractionMessage(schema, sourceClass != null && literal ? INFO : NOTE,
                SchemaExtractorMessage.FunctionalityType.URL_LITERAL, property.getPropertyName(), relatedEntity, failedQuery);
        if (!checked) checkPropertyValueCount(schema, property, sourceClass, request, literal);
    }

    private Long propertyValueCount(SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass, boolean literal) {
        if (sourceClass == null) return literal ? property.getDataTripleCount() : property.getObjectTripleCount();
        return literal ? sourceClass.getDataTripleCount() : sourceClass.getObjectTripleCount();
    }

    private boolean hasExplicitPropertyValue(SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass, boolean literal) {
        Long count = propertyValueCount(property, sourceClass, literal);
        if (count != null) return count != 0;
        return sourceClass != null && (literal ? sourceClass.isLiteralObjectsKnown() : isTrue(sourceClass.getNonLiteralObjectsExistence()));
    }

    // Reuse query selection and source bindings for both count and existence queries.
    private SparqlQueryBuilder propertyValueQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                  SchemaExtractorClassNodeInfo sourceClass, SchemaExtractorQueries regular, SchemaExtractorQueries distinct) {
        SchemaExtractorQueries name = selectQuery(request.getExactCountCalculations(), regular, distinct);
        SparqlQueryBuilder query = new SparqlQueryBuilder(request.getQueries().get(name.name()), name)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        if (sourceClass != null) query
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty());
        return query;
    }

    // Accept only a successful numeric count so failed or malformed responses cannot be used for subtraction.
    private Long readPropertyValueCount(SchemaExtractorRequestDto request, SparqlQueryBuilder query) {
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        return !response.hasErrors() && response.getResults().size() == 1 && response.getResults().get(0) != null
                ? readNonNegativeCount(response.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT)) : null;
    }

    // Store direct and inferred numeric counts identically while clearing source fallback-only literal evidence.
    private void storePropertyValueCount(SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass, boolean literal, long count) {
        if (sourceClass == null) {
            if (literal) property.setDataTripleCount(count);
            else property.setObjectTripleCount(count);
        } else if (literal) {
            sourceClass.setDataTripleCount(count);
            sourceClass.setLiteralObjectsKnown(false);
        } else {
            sourceClass.setObjectTripleCount(count);
            sourceClass.setNonLiteralObjectsExistence(null);
        }
    }

    // Keep exact zero and positive existence evidence separate from unavailable counts.
    private void checkPropertyValueCount(Schema schema, SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass,
                                         SchemaExtractorRequestDto request, boolean literal) {
        String relatedEntity = sourceClass == null ? null : sourceClass.getClassName();
        SchemaExtractorQueries name = sourceClass == null
                ? (literal ? CHECK_PROPERTY_LITERAL_VALUES : CHECK_PROPERTY_URL_VALUES)
                : (literal ? CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE : CHECK_PROPERTY_URL_VALUES_FOR_SOURCE);
        SparqlQueryBuilder query = propertyValueQuery(request, property, sourceClass, name, name);
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        if (!response.hasErrors()) {
            if (response.getResults().isEmpty()) storePropertyValueCount(property, sourceClass, literal, 0L);
            else if (sourceClass == null) storePropertyValueCount(property, null, literal, -1L);
            else if (literal) sourceClass.setLiteralObjectsKnown(true);
            else sourceClass.setNonLiteralObjectsExistence(true);
        } else {
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.URL_LITERAL,
                    property.getPropertyName(), relatedEntity, query, sourceClass == null
                            ? (literal ? "Existence of literal objects for a property has not been established by a direct query"
                            : "Existence of non-literal objects for a property has not been established by a direct query") : null);
        }
    }

    // Sample incomplete counts unless an exact zero already settles the source relation's object kind.
    private void determinePropertyObjectTripleRatio(Schema schema, SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass,
                                                    SchemaExtractorRequestDto request) {
        Long objectCount = nonNegativeCount(propertyValueCount(property, sourceClass, false));
        Long literalCount = nonNegativeCount(propertyValueCount(property, sourceClass, true));
        if (objectCount != null && literalCount != null) return;

        boolean noObjects = Long.valueOf(0).equals(objectCount);
        boolean noLiterals = Long.valueOf(0).equals(literalCount);
        if (sourceClass != null && (noObjects || noLiterals)) {
            // An established source relation is evidence of presence even when its count is unavailable.
            if (!Long.valueOf(0).equals(sourceClass.getTripleCount())) {
                if (noObjects) sourceClass.setLiteralObjectsExistence(true);
                else sourceClass.setNonLiteralObjectsExistence(true);
            }
            return;
        }

        SchemaExtractorQueries name = sourceClass == null ? COUNT_PROPERTY_URL_VALUES_LIMITS : COUNT_PROPERTY_URL_VALUES_FOR_SOURCE_LIMITS;
        String relatedEntity = sourceClass == null ? null : sourceClass.getClassName();
        Long smallestLimit = sampleLimits.get(sampleLimits.size() - 1);
        for (Long limit : sampleLimits) {
            SparqlQueryBuilder query = propertyValueQuery(request, property, sourceClass, name, name)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
            QueryResponse response = sparqlEndpointProcessor.read(request, query);
            if (!response.hasErrors() && response.getResults().size() == 1 && response.getResults().get(0) != null) {
                QueryResult row = response.getResults().get(0);
                Long size = readNonNegativeCount(row.getValue("sampleSize"));
                Long objects = readNonNegativeCount(row.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (size != null && (size == 0 || (objects != null && objects <= size))) {
                    if (size > 0) storePropertyValueSample(property, sourceClass, size, objects);
                    return;
                }
            }
            if (limit.equals(smallestLimit)) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.URL_LITERAL,
                        property.getPropertyName(), relatedEntity, query);
            }
        }

        name = sourceClass == null ? FIND_PROPERTY_VALUE_TRIPLES_WITH_LIMIT : FIND_PROPERTY_VALUE_TRIPLES_FOR_SOURCE_WITH_LIMIT;
        SparqlQueryBuilder query = propertyValueQuery(request, property, sourceClass, name, name)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, smallestLimit.toString());
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        if (response.hasErrors()) return;

        long size = 0, objects = 0;
        for (QueryResult row : response.getResults()) {
            QueryResultObject object = row == null ? null : row.getResultObject("y");
            if (object == null || object.getIsLiteral() == null) continue;
            size++;
            if (!object.getIsLiteral()) objects++;
        }
        if (size > 0) storePropertyValueSample(property, sourceClass, size, objects);
    }

    // Apply sampled presence and absence only where counts and explicit checks have supplied no evidence.
    private void storePropertyValueSample(SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass,
                                          long size, long objects) {
        BigDecimal ratio = BigDecimal.valueOf(objects).divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
        boolean hasLiterals = objects < size;
        boolean hasObjects = objects > 0;
        if (sourceClass == null) {
            property.setObjectTripleRatio(ratio);
            if (property.getDataTripleCount() == null) property.setLiteralObjectsExistence(hasLiterals);
            if (property.getObjectTripleCount() == null) property.setNonLiteralObjectsExistence(hasObjects);
        } else {
            sourceClass.setObjectTripleRatio(ratio);
            if (sourceClass.getDataTripleCount() == null && !sourceClass.isLiteralObjectsKnown()) {
                sourceClass.setLiteralObjectsExistence(hasLiterals);
            }
            if (sourceClass.getObjectTripleCount() == null && isNotTrue(sourceClass.getNonLiteralObjectsExistence())) {
                sourceClass.setNonLiteralObjectsExistence(hasObjects);
            }
        }
    }

    private Boolean nonLiteralObjectsExistence(SchemaExtractorPropertyNodeInfo property) {
        Long count = property.getObjectTripleCount();
        return count != null ? Boolean.valueOf(count != 0) : property.getNonLiteralObjectsExistence();
    }

    protected void determinePropertyType(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyType [" + property.getPropertyName() + "]");

        if (property.getTripleCount() != null && property.getTripleCount().equals(property.getObjectTripleCount())) {
            property.setIsObjectProperty(Boolean.TRUE);
        }
        if (property.getTripleCount() != null && property.getTripleCount().equals(property.getDataTripleCount())) {
            property.setIsObjectProperty(Boolean.FALSE);
        } else {
            property.setIsObjectProperty(null);
        }
    }

    private static final long[] CLASS_PROPERTY_LIMITS = {1000, 3000, 10000, 100000, 1000000, 10000000};
    private static final int CLASS_PROPERTY_INSTANCE_LIMIT = 1000;
    private static final org.slf4j.Logger procedureDetailLog = org.slf4j.LoggerFactory.getLogger(SchemaExtractor.class.getName() + ".detail");

    private static class InstanceQueryState {
        boolean singletonReported;
    }

    private static final class ClassPropertyState extends InstanceQueryState {
        boolean complete;
        int failures;
        int skippedPairChecks;
        // Class queries can establish coverage for predicates admitted after this pass.
        int unrestrictedClassCoverage;
        SchemaExtractorMessage initialLimitFailure;
        SchemaExtractorQueries emptyClassQuery;
        // Use the same internal count-work marker for source and target contexts without exporting it.
        int sourceClassCountsOK;
        int missingCounts;
        int pendingRefinements;
    }

    private record ClassPropertyDiscovery(Set<String> originalNames, Set<String> newNames) {}

    private static final class ClassPropertyContext {
        Schema schema;
        SchemaExtractorRequestDto request;
        Map<String, SchemaExtractorPropertyNodeInfo> properties;
        Map<String, SchemaExtractorPropertyNodeInfo> activeProperties;
        boolean target;
        final Map<SchemaClass, ClassPropertyState> classes = new LinkedHashMap<>();
        final Map<String, Map<String, SchemaClass>> classIndex = new LinkedHashMap<>();
        final Map<SchemaExtractorPropertyNodeInfo, ClassPropertyState> propertyStates = new IdentityHashMap<>();
        Map<SchemaClass, Boolean> blankChecks;
        ClassPropertyDiscovery discovery;
        ClassPropertyContext firstPass;
    }

    private static final class InstanceBatchResult<K> {
        final Map<K, Long> counts = new LinkedHashMap<>();
        final BitSet covered = new BitSet();
    }

    // Run the revised source passes in this extractor while sharing blank-presence observations with target extraction.
    protected void determinePropertySources(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                            SchemaExtractorRequestDto request, Map<SchemaClass, Boolean> blankChecks) {
        determineClassPropertyMappings(schema, properties, request, false, blankChecks, null, null);
    }

    // Complete confirmed empty mappings directly and let target discovery improve sample-based estimates.
    protected void determinePropertyTargets(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                             SchemaExtractorRequestDto request, Map<SchemaClass, Boolean> blankChecks) {
        determinePropertyTargets(schema, properties, request, blankChecks, null, null);
    }

    private ClassPropertyContext determinePropertyTargets(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                          SchemaExtractorRequestDto request, Map<SchemaClass, Boolean> blankChecks,
                                                          ClassPropertyDiscovery discovery, ClassPropertyContext firstPass) {
        Map<String, SchemaExtractorPropertyNodeInfo> targetProperties = new LinkedHashMap<>();
        properties.forEach((name, property) -> {
            if (hasConfirmedNoNonLiteralObjects(property)) {
                property.setTargetClassesOK(7);
            } else {
                if (isFalse(nonLiteralObjectsExistence(property))) property.setTargetClassesOK(3);
                targetProperties.put(name, property);
            }
        });
        ClassPropertyContext context = determineClassPropertyMappings(schema, targetProperties, request, true, blankChecks, discovery, firstPass);
        targetProperties.values().forEach(this::correctSampledTargetAbsence);
        return context;
    }

    private void correctSampledTargetAbsence(SchemaExtractorPropertyNodeInfo property) {
        if (isFalse(nonLiteralObjectsExistence(property)) && !property.getTargetClasses().isEmpty()) {
            correctSampledObjectTripleRatio(property);
        }
    }

    // Revise the sampled absence using published target relations without filling in missing triple counts.
    private void correctSampledObjectTripleRatio(SchemaExtractorPropertyNodeInfo property) {
        long literalBase = 1000;
        long objectCount = 0;
        for (SchemaExtractorClassNodeInfo targetClass : property.getTargetClasses()) {
            Long count = nonNegativeCount(targetClass.getTripleCount());
            if (count != null) objectCount += Math.min(count, literalBase - objectCount);
        }
        BigDecimal ratio = BigDecimal.valueOf(objectCount)
                .divide(BigDecimal.valueOf(literalBase + objectCount), 2, RoundingMode.HALF_UP);
        property.setObjectTripleRatio(ratio.max(BigDecimal.valueOf(1, 2)));
        property.setNonLiteralObjectsExistence(true);
    }

    // Keep mapping state request-local and store only correspondences that have actually been discovered.
    private ClassPropertyContext determineClassPropertyMappings(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                                SchemaExtractorRequestDto request, boolean target, Map<SchemaClass, Boolean> blankChecks,
                                                                ClassPropertyDiscovery discovery, ClassPropertyContext firstPass) {
        ClassPropertyContext context = new ClassPropertyContext();
        context.schema = schema;
        context.request = request;
        context.properties = properties;
        context.target = target;
        context.blankChecks = blankChecks;
        context.discovery = discovery;
        context.firstPass = firstPass;
        properties.values().forEach(property -> context.propertyStates.put(property, new ClassPropertyState()));
        for (SchemaClass clazz : schema.getClasses()) {
            if (isNotFalse(clazz.getPropertiesInSchema()) && request.getMainClassificationProperties().contains(clazz.getClassificationProperty())) {
                context.classes.put(clazz, new ClassPropertyState());
                context.classIndex.computeIfAbsent(clazz.getClassificationProperty(), key -> new LinkedHashMap<>()).put(clazz.getFullName(), clazz);
                classPropertyLinks(context, clazz).forEach((name, count) -> {
                    SchemaExtractorPropertyNodeInfo property = properties.get(name);
                    if (property != null) {
                        ClassPropertyState state = context.propertyStates.get(property);
                        if (count.getTripleCount() == null) state.missingCounts++;
                        if (count.isCountNeedsRefinement()) state.pendingRefinements++;
                    }
                });
            }
        }
        if (target) directTargetClassCheckForProperties(context);
        else directSourceClassCheckForProperties(context);
        if (firstPass != null) {
            reuseClassSideCoverage(context);
            finishClassPropertyChecks(context);
            return context;
        }
        if (properties.values().stream().allMatch(property -> classPropertyHandled(context, property))) {
            context.classes.forEach((clazz, state) -> { state.complete = true; classPropertyClassQuality(context, clazz, 7); });
            publishClassPropertyMappings(context);
            return context;
        }
        Map<String, SchemaExtractorPropertyNodeInfo> remaining = new LinkedHashMap<>();
        properties.forEach((name, property) -> { if (!classPropertyHandled(context, property)) remaining.put(name, property); });
        context.activeProperties = remaining.size() <= 10 ? remaining : properties;
        if (target) checkIncomingPropertiesByClass(context);
        else checkOutgoingPropertiesByClass(context);
        if (context.classes.values().stream().allMatch(state -> state.complete)) {
            properties.values().forEach(property -> {
                context.propertyStates.get(property).complete = true;
                context.propertyStates.get(property).sourceClassCountsOK = 3;
                classPropertyPropertyQuality(context, property, 7);
            });
            publishClassPropertyMappings(context);
            return context;
        }
        boolean needsSampling = properties.values().stream().anyMatch(property -> classPropertyPropertyQuality(context, property) < 7
                || context.propertyStates.get(property).sourceClassCountsOK < 2);
        if (needsSampling) {
            if (target) checkIncomingPropertiesByClassWithLimits(context);
            else checkOutgoingPropertiesByClassWithLimits(context);
        }
        finishClassPropertyChecks(context);
        return context;
    }

    private void finishClassPropertyChecks(ClassPropertyContext context) {
        if (context.target) checkIndividualPropertyToClassPairs(context);
        else checkIndividualClassToPropertyPairs(context);
        setClassPropertyQualityMarks(context);
        publishClassPropertyMappings(context);
    }

    // Reuse only queries that covered all predicates, including any name added after the first pass.
    private void reuseClassSideCoverage(ClassPropertyContext context) {
        context.classes.forEach((clazz, state) -> {
            state.unrestrictedClassCoverage = context.firstPass.classes.get(clazz).unrestrictedClassCoverage;
            state.complete = state.unrestrictedClassCoverage == 7;
        });
        boolean complete = context.classes.values().stream().allMatch(state -> state.complete);
        boolean sampled = context.classes.values().stream().allMatch(state -> state.unrestrictedClassCoverage >= 4);
        if (complete || sampled) {
            context.propertyStates.forEach((property, state) -> {
                if (complete) state.complete = true;
                classPropertyPropertyQuality(context, property, Math.max(classPropertyPropertyQuality(context, property), complete ? 7 : 4));
            });
            refreshClassPropertyCountMarks(context);
        }
    }

    // Complete the direct source pass for every property before starting class-centered recovery.
    protected void directSourceClassCheckForProperties(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "directSourceClassCheckForProperties", null);
        context.properties.values().forEach(property -> directSourceClassCheckForProperty(context, property));
    }

    // Complete the symmetric direct target pass before any incoming class recovery.
    protected void directTargetClassCheckForProperties(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "directTargetClassCheckForProperties", null);
        context.properties.values().forEach(property -> directTargetClassCheckForProperty(context, property));
    }

    // Log one source-property procedure start and delegate to the shared count and count-free stages.
    protected void directSourceClassCheckForProperty(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        logClassPropertyProcedure(context, "directSourceClassCheckForProperty", property.getPropertyName());
        directClassCheckForProperty(context, property);
    }

    // Log the target-property start through the same file-only route used for sources.
    protected void directTargetClassCheckForProperty(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        logClassPropertyProcedure(context, "directTargetClassCheckForProperty", property.getPropertyName());
        directClassCheckForProperty(context, property);
    }

    // Separate complete correspondence discovery from count completion and record successful-instance bases for retained partial counts.
    private void directClassCheckForProperty(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        ClassPropertyState state = context.propertyStates.get(property);
        Set<String> failedCounts = context.target ? determinePropertyTargetsWithTripleCount(context, property)
                : determinePropertySourcesWithTripleCount(context, property);
        if (failedCounts.isEmpty()) {
            state.complete = true;
            state.sourceClassCountsOK = 3;
            classPropertyPropertyQuality(context, property, 7);
            return;
        }
        Set<String> failedClasses = context.target ? determinePropertyTarget(context, property, failedCounts)
                : determinePropertySource(context, property, failedCounts);
        if (failedClasses.isEmpty()) {
            state.complete = true;
            classPropertyPropertyQuality(context, property, 7);
        }
        if (!smallClassPropertyEntity(classPropertySize(property, context.target)) || !noPropertyBlankNodes(context, property)) return;
        List<String> instances = readClassPropertyInstances(context, property, null, 0);
        if (instances == null || instances.size() > CLASS_PROPERTY_INSTANCE_LIMIT) return;
        BitSet covered = new BitSet(instances.size());
        covered.set(0, instances.size());
        Map<SchemaClass, Long> counts = new LinkedHashMap<>();
        for (String predicate : failedCounts) {
            InstanceBatchResult<SchemaClass> result = new InstanceBatchResult<>();
            SchemaExtractorQueries query = context.target
                    ? selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_FOR_INSTANCES, FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_FOR_INSTANCES_DISTINCT)
                    : selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_FOR_INSTANCES, FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_FOR_INSTANCES_DISTINCT);
            executeInstanceBatches(context.schema, context.request, instances, 0, instances.size(), "class",
                    () -> propertyClassQuery(context, property, predicate, query),
                    row -> context.classIndex.get(predicate).get(row.getValueFullName("class")), result, state, property.getPropertyName());
            covered.and(result.covered);
            result.counts.forEach((clazz, count) -> counts.merge(clazz, count, Math::addExact));
        }
        int successful = covered.cardinality();
        boolean complete = successful == instances.size();
        boolean usableCounts = complete || (long) successful * 10 >= (long) instances.size() * 3;
        counts.forEach((clazz, count) -> recordClassPropertyRelation(context, clazz, property,
                usableCounts ? count : null, !complete && usableCounts ? (long) successful : null, complete, !complete && usableCounts));
        if (complete) {
            state.complete = true;
            state.sourceClassCountsOK = state.missingCounts == 0 ? 3 : 2;
            classPropertyPropertyQuality(context, property, 7);
        } else if (successful > 0) {
            if (!state.complete) classPropertyPropertyQuality(context, property, usableCounts ? 5 : 1);
            state.sourceClassCountsOK = usableCounts ? (fineTuneClassPropertyCounts(context) || state.missingCounts > 0 ? 2 : 3) : 0;
        }
    }

    // Reuse the source-count stage while returning failed predicates instead of conflating success with a nonempty answer.
    protected Set<String> determinePropertySourcesWithTripleCount(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        return readDirectPropertyClasses(context, property, context.classIndex.keySet(), true);
    }

    // Reuse the target-count stage with the same complete-empty and scoped-predicate semantics as sources.
    protected Set<String> determinePropertyTargetsWithTripleCount(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        return readDirectPropertyClasses(context, property, context.classIndex.keySet(), true);
    }

    // Keep count-free source discovery in its prescribed direct stage instead of executing nested fallback passes.
    protected Set<String> determinePropertySource(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property, Set<String> predicates) {
        return readDirectPropertyClasses(context, property, predicates, false);
    }

    // Keep count-free target discovery symmetric and retain any counts already obtained by successful predicates.
    protected Set<String> determinePropertyTarget(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property, Set<String> predicates) {
        return readDirectPropertyClasses(context, property, predicates, false);
    }

    // Report direct failures by stage and retain only genuine empty-response provenance for the final no-class message.
    private Set<String> readDirectPropertyClasses(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property,
                                                 Collection<String> predicates, boolean withCounts) {
        Set<String> failed = new LinkedHashSet<>();
        SchemaExtractorQueries name = withCounts ? (context.target
                ? selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT, FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT, FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_DISTINCT))
                : context.target ? FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT : FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT;
        for (String predicate : predicates) {
            SparqlQueryBuilder query = propertyClassQuery(context, property, predicate, name);
            QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
            Map<SchemaClass, Long> counts = readGroupedCounts(response, "class",
                    row -> context.classIndex.get(predicate).get(row.getValueFullName("class")), withCounts);
            if (counts == null) {
                failed.add(predicate);
                String side = context.target ? "object" : "subject";
                String text = withCounts
                        ? (context.target ? "Object" : "Subject") + " class retrieval for a property failed. Other methods attempted."
                        : "Backup method for establishing " + side + " classes for a property without triple counts was not successful. Retrieval of " + side + " classes by other methods is in progress.";
                addExtractionMessage(context.schema, withCounts ? NOTE : INFO, SchemaExtractorMessage.FunctionalityType.STRUCT, property.getPropertyName(), null, query, text);
            } else {
                if (response.getResults().isEmpty()) context.propertyStates.get(property).emptyClassQuery = name;
                counts.forEach((clazz, count) -> recordClassPropertyRelation(context, clazz, property, count, null, withCounts, false));
            }
        }
        return failed;
    }

    // Run full outgoing class attempts globally before considering LIMIT recovery.
    protected void checkOutgoingPropertiesByClass(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "checkOutgoingPropertiesByClass", null);
        context.classes.keySet().forEach(clazz -> checkOutgoingPropertiesForClass(context, clazz));
    }

    // Run the same full-class pass for incoming properties with its own procedure name.
    protected void checkIncomingPropertiesByClass(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "checkIncomingPropertiesByClass", null);
        context.classes.keySet().forEach(clazz -> checkIncomingPropertiesForClass(context, clazz));
    }

    // Keep per-class outgoing procedure starts out of the console while using the shared recovery policy.
    protected void checkOutgoingPropertiesForClass(ClassPropertyContext context, SchemaClass clazz) {
        logClassPropertyProcedure(context, "checkOutgoingPropertiesForClass", clazz.getFullName());
        checkPropertiesForClass(context, clazz);
    }

    // Apply identical per-class recovery and logging to incoming properties.
    protected void checkIncomingPropertiesForClass(ClassPropertyContext context, SchemaClass clazz) {
        logClassPropertyProcedure(context, "checkIncomingPropertiesForClass", clazz.getFullName());
        checkPropertiesForClass(context, clazz);
    }

    // Recover small classes through the shared batch executor and record successful-instance bases for partial counts.
    private void checkPropertiesForClass(ClassPropertyContext context, SchemaClass clazz) {
        Map<String, TripleCount> counts = context.target ? determineClassIncomingProperties(context, clazz, null)
                : determineClassOutgoingProperties(context, clazz, null);
        if (counts != null) {
            counts.forEach((name, count) -> recordClassSideRelation(context, clazz, name, count.getTripleCount(), null, true, false));
            recordClassSideCoverage(context, clazz, 7);
            context.classes.get(clazz).complete = true;
            classPropertyClassQuality(context, clazz, 7);
            refreshClassPropertyCountMarks(context);
            return;
        }
        if (!smallClassPropertyEntity(classPropertyClassSize(clazz)) || !noClassBlankNodes(context, clazz)) return;
        List<String> instances = readClassPropertyInstances(context, null, clazz, 0);
        if (instances == null || instances.size() > CLASS_PROPERTY_INSTANCE_LIMIT) return;
        InstanceBatchResult<String> result = executeClassInstanceBatches(context, clazz, instances, context.target
                ? "Retrieval of instance incoming properties not successful. Instance-based options for determining the properties of an instance collection may be limited."
                : "Retrieval of instance (outgoing) properties not successful. Instance-based options for determining the properties of an instance collection may be limited.");
        boolean complete = result.covered.cardinality() == instances.size();
        result.counts.forEach((name, count) -> recordClassSideRelation(context, clazz, name, count,
                complete ? null : (long) result.covered.cardinality(), complete, !complete));
        if (complete) {
            recordClassSideCoverage(context, clazz, 7);
            context.classes.get(clazz).complete = true;
            classPropertyClassQuality(context, clazz, 7);
        } else if (!result.covered.isEmpty()) {
            recordClassSideCoverage(context, clazz, 5);
            classPropertyClassQuality(context, clazz, 5);
        }
        refreshClassPropertyCountMarks(context);
    }

    // Expose a single outgoing full or LIMIT attempt so its caller controls the global pass order.
    protected Map<String, TripleCount> determineClassOutgoingProperties(ClassPropertyContext context, SchemaClass clazz, Long limit) {
        return determineClassProperties(context, clazz, limit);
    }

    // Expose the corresponding incoming attempt with identical scope and failure handling.
    protected Map<String, TripleCount> determineClassIncomingProperties(ClassPropertyContext context, SchemaClass clazz, Long limit) {
        return determineClassProperties(context, clazz, limit);
    }

    private SchemaExtractorQueries countedClassPropertyQueryName(SchemaExtractorRequestDto request, boolean incoming, Long limit) {
        if (incoming) {
            return limit == null
                    ? selectQuery(request.getExactCountCalculations(), FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS, FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_DISTINCT)
                    : selectQuery(request.getExactCountCalculations(), FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS, FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS_DISTINCT);
        }
        return limit == null
                ? selectQuery(request.getExactCountCalculations(), FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS, FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_DISTINCT)
                : selectQuery(request.getExactCountCalculations(), FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS, FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS_DISTINCT);
    }

    // Reuse discovery only at the matching class-side scope and retain live query failures for the existing diagnostics.
    private Map<String, TripleCount> determineClassProperties(ClassPropertyContext context, SchemaClass clazz, Long limit) {
        Map<String, TripleCount> discoveryCounts = reuseClassPropertyDiscoveryCounts(context, clazz, limit);
        if (discoveryCounts != null) return discoveryCounts;

        SchemaExtractorQueries name = countedClassPropertyQueryName(context.request, context.target, limit);
        SparqlQueryBuilder query = classPropertiesQuery(context, clazz, name);
        if (limit != null) query.withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
        QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
        Map<String, TripleCount> counts = context.target ? getClassIncomingProperties(context, response, limit) : getClassOutgoingProperties(context, response, limit);
        if (counts == null) {
            if (limit == null) addExtractionMessage(context.schema, INFO, SchemaExtractorMessage.FunctionalityType.STRUCT, clazz.getFullName(), null, query);
            else if (limit == CLASS_PROPERTY_LIMITS[0]) context.classes.get(clazz).initialLimitFailure =
                    addExtractionMessage(context.schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT, clazz.getFullName(), null, query);
        }
        return counts;
    }

    // Reuse only in-scope counts while honoring the relation and count evidence already obtained by CP.
    private Map<String, TripleCount> reuseClassPropertyDiscoveryCounts(ClassPropertyContext context, SchemaClass clazz, Long limit) {
        SchemaClass.PropertyDiscoveryCounts discoveryCounts = context.target
                ? clazz.getIncomingPropertyDiscoveryCounts() : clazz.getOutgoingPropertyDiscoveryCounts();
        if (discoveryCounts == null || !Objects.equals(discoveryCounts.limit(), limit)) return null;

        Map<String, TripleCount> links = classPropertyLinks(context, clazz);
        if (limit == null && links.entrySet().stream().anyMatch(entry -> context.activeProperties.containsKey(entry.getKey())
                && (entry.getValue().getTripleCount() == null || entry.getValue().isCountNeedsRefinement())
                && !discoveryCounts.counts().containsKey(entry.getKey()))) {
            return null;
        }

        Map<String, TripleCount> counts = new LinkedHashMap<>();
        discoveryCounts.counts().forEach((name, count) -> {
            if (classSidePropertyName(context, name) == null) return;
            TripleCount existing = links.get(name);
            SchemaExtractorPropertyNodeInfo property = context.properties.get(name);
            if (existing == null && property != null && context.propertyStates.get(property).complete) return;
            if (existing != null && existing.getTripleCount() != null
                    && (limit != null || existing.getTripleCountBase() == null)) return;
            counts.put(name, new TripleCount(count, limit));
        });
        return counts;
    }

    // Share validated grouped-count conversion instead of maintaining a separate outgoing parser.
    protected Map<String, TripleCount> getClassOutgoingProperties(ClassPropertyContext context, QueryResponse response, Long base) {
        return getClassProperties(context, response, base);
    }

    // Use the same grouped-property parser for incoming results while preserving the supplied sample base.
    protected Map<String, TripleCount> getClassIncomingProperties(ClassPropertyContext context, QueryResponse response, Long base) {
        return getClassProperties(context, response, base);
    }

    // Resolve only considered properties and distinguish a valid empty group list from a malformed response.
    private Map<String, TripleCount> getClassProperties(ClassPropertyContext context, QueryResponse response, Long base) {
        Map<String, Long> counts = readGroupedCounts(response, "property", row -> classSidePropertyName(context, row.getValue("property")), true);
        if (counts == null) return null;
        Map<String, TripleCount> result = new LinkedHashMap<>();
        counts.forEach((name, count) -> result.put(name, new TripleCount(count, base)));
        return result;
    }

    // Restrict the outgoing sampling pass to classes whose full discovery remains incomplete.
    protected void checkOutgoingPropertiesByClassWithLimits(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "checkOutgoingPropertiesByClassWithLimits", null);
        context.classes.forEach((clazz, state) -> { if (!state.complete) checkOutgoingPropertiesForClassWithLimits(context, clazz); });
    }

    // Apply the same incomplete-class selection to the incoming sampling pass.
    protected void checkIncomingPropertiesByClassWithLimits(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "checkIncomingPropertiesByClassWithLimits", null);
        context.classes.forEach((clazz, state) -> { if (!state.complete) checkIncomingPropertiesForClassWithLimits(context, clazz); });
    }

    // Give outgoing LIMIT recovery a file-only per-class start before shared sampling execution.
    protected void checkOutgoingPropertiesForClassWithLimits(ClassPropertyContext context, SchemaClass clazz) {
        logClassPropertyProcedure(context, "checkOutgoingPropertiesForClassWithLimits", clazz.getFullName());
        checkPropertiesForClassWithLimits(context, clazz);
    }

    // Give incoming LIMIT recovery the symmetric per-class logging and sampling behavior.
    protected void checkIncomingPropertiesForClassWithLimits(ClassPropertyContext context, SchemaClass clazz) {
        logClassPropertyProcedure(context, "checkIncomingPropertiesForClassWithLimits", clazz.getFullName());
        checkPropertiesForClassWithLimits(context, clazz);
    }

    // Retain discoveries across non-nested samples and back up failed or unavailable minimum limits with nonblank instances.
    private void checkPropertiesForClassWithLimits(ClassPropertyContext context, SchemaClass clazz) {
        Map<String, TripleCount> best = null;
        Long bestLimit = null;
        Long size = classPropertyClassSize(clazz);
        for (long limit : CLASS_PROPERTY_LIMITS) {
            if (size != null && size >= 0 && limit > size) break;
            Map<String, TripleCount> counts = context.target ? determineClassIncomingProperties(context, clazz, limit)
                    : determineClassOutgoingProperties(context, clazz, limit);
            if (counts == null) break;
            if (best == null) best = new LinkedHashMap<>();
            best.putAll(counts);
            bestLimit = limit;
        }
        if (best != null) {
            best.forEach((name, count) -> recordClassSideRelation(context, clazz, name, count.getTripleCount(), count.getTripleCountBase(), false, true));
            recordClassSideCoverage(context, clazz, 4);
            setClassPropertyLimit(context, clazz, bestLimit);
            if (classPropertyClassQuality(context, clazz) < 4) classPropertyClassQuality(context, clazz, 4);
        } else {
            List<String> instances = readClassPropertyInstances(context, null, clazz, CLASS_PROPERTY_INSTANCE_LIMIT);
            if (instances == null || instances.isEmpty()) return;
            InstanceBatchResult<String> result = executeClassInstanceBatches(context, clazz, instances);
            int successful = result.covered.cardinality();
            boolean enoughRetrieved = instances.size() >= 500 || size != null && size >= 0 && (long) instances.size() * 2 >= size;
            boolean usableCounts = enoughRetrieved && (long) successful * 2 >= instances.size();
            result.counts.forEach((name, count) -> recordClassSideRelation(context, clazz, name,
                    usableCounts ? count : null, usableCounts ? (long) successful : null, false, usableCounts));
            if (usableCounts) {
                recordClassSideCoverage(context, clazz, 4);
                // Downgrade only when this instance backup meets the existing sample coverage criteria, including empty successful results.
                SchemaExtractorMessage failure = context.classes.get(clazz).initialLimitFailure;
                if (failure != null) failure.setMessageLevel(NOTE);
                setClassPropertyLimit(context, clazz, (long) successful);
                if (classPropertyClassQuality(context, clazz) < 4) classPropertyClassQuality(context, clazz, 4);
            }
        }
        refreshClassPropertyCountMarks(context);
    }

    // Start the final source correspondence/count pass after all class recovery options.
    protected void checkIndividualClassToPropertyPairs(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "checkIndividualClassToPropertyPairs", null);
        checkIndividualClassPropertyPairs(context);
    }

    // Start the symmetric target correspondence/count pass with the procedure name prescribed by the design.
    protected void checkIndividualPropertyToClassPairs(ClassPropertyContext context) {
        logClassPropertyProcedure(context, "checkIndividualPropertyToClassPairs", null);
        checkIndividualClassPropertyPairs(context);
    }

    // Skip unresolved target existence probes only for sampled absence when cross-checking is disabled.
    private void checkIndividualClassPropertyPairs(ClassPropertyContext context) {
        boolean skipEstimatedTargets = context.target
                && isNotTrue(context.request.getCrossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure());
        for (SchemaClass clazz : context.classes.keySet()) {
            ClassPropertyState classState = context.classes.get(clazz);
            if (classState.complete && context.firstPass == null) continue;
            for (SchemaExtractorPropertyNodeInfo property : context.properties.values()) {
                if (classPropertyHandled(context, property)) continue;
                TripleCount relation = classPropertyLinks(context, clazz).get(property.getPropertyName());
                if (relation == null) {
                    ClassPropertyState propertyState = context.propertyStates.get(property);
                    if (classState.complete || propertyState.complete) continue;
                    if (skipEstimatedTargets && isFalse(nonLiteralObjectsExistence(property))) {
                        classState.skippedPairChecks++;
                        propertyState.skippedPairChecks++;
                        continue;
                    }
                    SparqlQueryBuilder query = classPropertyPairQuery(context, clazz, property,
                            context.target ? CHECK_CLASS_AS_PROPERTY_TARGET : CHECK_CLASS_AS_PROPERTY_SOURCE);
                    QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
                    if (response.hasErrors()) {
                        classState.failures++;
                        propertyState.failures++;
                        addExtractionMessage(context.schema, WARNING, SchemaExtractorMessage.FunctionalityType.STRUCT, property.getPropertyName(), clazz.getFullName(), query);
                        continue;
                    }
                    if (response.getResults().isEmpty()) continue;
                    relation = recordClassPropertyRelation(context, clazz, property, null, null, false, false);
                }
                if (relation.getTripleCount() == null || relation.isCountNeedsRefinement() && fineTuneClassPropertyCounts(context)) {
                    boolean refinement = relation.getTripleCount() != null;
                    if (context.target) determinePropertyTargetTripleCount(context, clazz, property, refinement);
                    else determinePropertySourceTripleCount(context, clazz, property, refinement);
                }
            }
        }
        refreshClassPropertyCountMarks(context);
    }

    // Reuse full source counting with explicit mandatory-versus-refinement retention and message policies.
    protected void determinePropertySourceTripleCount(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property, boolean refinement) {
        determineClassPropertyTripleCount(context, clazz, property, refinement);
    }

    // Apply the same count policy to targets while counting distinct triples rather than distinct subjects alone.
    protected void determinePropertyTargetTripleCount(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property, boolean refinement) {
        determineClassPropertyTripleCount(context, clazz, property, refinement);
    }

    // Replace counts only after a valid full recount and preserve accepted count/base values when optional refinement fails.
    private void determineClassPropertyTripleCount(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property, boolean refinement) {
        SchemaExtractorQueries name = context.target
                ? selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_TARGET_TRIPLE_COUNT, FIND_PROPERTY_TARGET_TRIPLE_COUNT_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_SOURCE_TRIPLE_COUNT, FIND_PROPERTY_SOURCE_TRIPLE_COUNT_DISTINCT);
        SparqlQueryBuilder query = classPropertyPairQuery(context, clazz, property, name);
        QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
        Long count = !response.hasErrors() && response.getResults().size() == 1 ? readNonNegativeCount(response.getResults().get(0).getValue("instances")) : null;
        if (count != null) recordClassPropertyRelation(context, clazz, property, count, null, true, false);
        else {
            String side = context.target ? "target" : "source";
            String text = refinement ? "Query for exact property " + side + " triple count not successful. Using an estimate instead."
                    : "Property " + side + " triple count unavailable";
            addExtractionMessage(context.schema, refinement ? MESSAGE : WARNING, SchemaExtractorMessage.FunctionalityType.COUNT, property.getPropertyName(), clazz.getFullName(), query, text);
        }
    }

    // Share request-local coverage tracking while leaving completion thresholds and quality assignment with the caller.
    private <K> void executeInstanceBatches(Schema schema, SchemaExtractorRequestDto request, List<String> instances, int start, int end, String binding,
                                            java.util.function.Supplier<SparqlQueryBuilder> factory, java.util.function.Function<QueryResult, K> key,
                                            InstanceBatchResult<K> result, InstanceQueryState state, String entity) {
        executeInstanceBatches(schema, request, instances, start, end, binding, factory, key, result, state, entity, null);
    }

    // Select direct-URI singleton templates while preserving coverage, retries and the first-failing-singleton diagnostic.
    private <K> void executeInstanceBatches(Schema schema, SchemaExtractorRequestDto request, List<String> instances, int start, int end, String binding,
                                            java.util.function.Supplier<SparqlQueryBuilder> factory, java.util.function.Function<QueryResult, K> key,
                                            InstanceBatchResult<K> result, InstanceQueryState state, String entity, String text) {
        if (start == end) return;
        SparqlQueryBuilder query = withInstanceBindings(request, factory.get(), instances.subList(start, end));
        Map<K, Long> counts = readGroupedCounts(sparqlEndpointProcessor.read(request, query), binding, key, true);
        if (counts != null) {
            counts.forEach((value, count) -> result.counts.merge(value, count, Math::addExact));
            result.covered.set(start, end);
            return;
        }
        int size = end - start;
        if (size == 1) {
            if (!state.singletonReported) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.STRUCT_INSTANCE, entity, null, query, text);
                state.singletonReported = true;
            }
            return;
        }
        int batchSize = size > 100 ? 100 : size > 10 ? 10 : 1;
        for (int offset = start; offset < end; offset += batchSize) executeInstanceBatches(schema, request, instances, offset, Math.min(offset + batchSize, end), binding, factory, key, result, state, entity, text);
    }

    // Keep VALUES for batches and select the corresponding direct-URI query for a single instance.
    private SparqlQueryBuilder withInstanceBindings(SchemaExtractorRequestDto request, SparqlQueryBuilder query, List<String> instances) {
        if (instances.size() != 1) return query.withInstanceValues(instances);
        SchemaExtractorQueries singleton = switch (SchemaExtractorQueries.valueOf(query.getQueryName())) {
            case FIND_INSTANCE_OUTGOING_PROPERTIES -> FIND_SINGLE_INSTANCE_OUTGOING_PROPERTIES;
            case FIND_INSTANCE_OUTGOING_PROPERTIES_DISTINCT -> FIND_SINGLE_INSTANCE_OUTGOING_PROPERTIES_DISTINCT;
            case FIND_INSTANCE_INCOMING_PROPERTIES -> FIND_SINGLE_INSTANCE_INCOMING_PROPERTIES;
            case FIND_INSTANCE_INCOMING_PROPERTIES_DISTINCT -> FIND_SINGLE_INSTANCE_INCOMING_PROPERTIES_DISTINCT;
            case FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_FOR_INSTANCES -> FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_FOR_SINGLE_INSTANCE;
            case FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_FOR_INSTANCES_DISTINCT -> FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_FOR_SINGLE_INSTANCE_DISTINCT;
            case FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_FOR_INSTANCES -> FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_FOR_SINGLE_INSTANCE;
            case FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_FOR_INSTANCES_DISTINCT -> FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_FOR_SINGLE_INSTANCE_DISTINCT;
            default -> null;
        };
        return singleton == null ? query.withInstanceValues(instances)
                : query.forSingleInstance(instances.get(0), singleton, request.getQueries().get(singleton.name()));
    }

    // Build class-instance batches with the same active property scope used by the aggregate and LIMIT queries.
    private InstanceBatchResult<String> executeClassInstanceBatches(ClassPropertyContext context, SchemaClass clazz, List<String> instances) {
        return executeClassInstanceBatches(context, clazz, instances, null);
    }

    // Supply the small-class diagnostic text without applying it to the sample path that retains an empty message.
    private InstanceBatchResult<String> executeClassInstanceBatches(ClassPropertyContext context, SchemaClass clazz, List<String> instances, String text) {
        SchemaExtractorQueries name = context.target
                ? selectQuery(context.request.getExactCountCalculations(), FIND_INSTANCE_INCOMING_PROPERTIES, FIND_INSTANCE_INCOMING_PROPERTIES_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_INSTANCE_OUTGOING_PROPERTIES, FIND_INSTANCE_OUTGOING_PROPERTIES_DISTINCT);
        InstanceBatchResult<String> result = new InstanceBatchResult<>();
        executeInstanceBatches(context.schema, context.request, instances, 0, instances.size(), "property", () -> classPropertiesQuery(context, clazz, name),
                row -> classSidePropertyName(context, row.getValue("property")), result, context.classes.get(clazz), clazz.getFullName(), text);
        return result;
    }

    private String classSidePropertyName(ClassPropertyContext context, String name) {
        if (context.activeProperties.containsKey(name)) return name;
        return context.discovery != null && classSidePropertyScope(context) == null
                && !context.discovery.originalNames().contains(name)
                && isNotExcludedResource(name, context.request.getExcludedNamespaces())
                && SchemaUtil.isValidURI(name) ? name : null;
    }

    private void recordClassSideCoverage(ClassPropertyContext context, SchemaClass clazz, int quality) {
        if (context.discovery != null && classSidePropertyScope(context) == null) {
            ClassPropertyState state = context.classes.get(clazz);
            state.unrestrictedClassCoverage = Math.max(state.unrestrictedClassCoverage, quality);
        }
    }

    private void recordClassSideRelation(ClassPropertyContext context, SchemaClass clazz, String name,
                                         Long count, Long base, boolean replace, boolean refine) {
        SchemaExtractorPropertyNodeInfo property = context.properties.get(name);
        if (property != null) {
            recordClassPropertyRelation(context, clazz, property, count, base, replace, refine);
        } else {
            context.discovery.newNames().add(name);
            recordClassPropertyRelation(context, clazz, name, null, count, base, replace, refine);
        }
    }

    // Parse grouped rows atomically so malformed counts never masquerade as zero or successful instance coverage.
    private <K> Map<K, Long> readGroupedCounts(QueryResponse response, String binding, java.util.function.Function<QueryResult, K> key, boolean counted) {
        if (response.hasErrors()) return null;
        Map<K, Long> result = new LinkedHashMap<>();
        for (QueryResult row : response.getResults()) {
            if (row.getValue(binding) == null) return null;
            K value = key.apply(row);
            if (value == null) continue;
            Long count = counted ? readNonNegativeCount(row.getValue("instances")) : null;
            if (counted && count == null) return null;
            if (!counted) result.put(value, null);
            else if (count > 0) {
                try { result.merge(value, count, Math::addExact); }
                catch (ArithmeticException overflow) { return null; }
            }
        }
        return result;
    }

    // Preserve the legacy global parser by using strict nonnegative parsing only for the new grouped/count paths.
    private Long readNonNegativeCount(String value) {
        try { long count = Long.parseLong(value); return count >= 0 ? count : null; }
        catch (NumberFormatException failure) { return null; }
    }

    // Keep pair counts canonical in the existing sparse class maps and track pending count work with scalar property counters.
    private TripleCount recordClassPropertyRelation(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property,
                                                    Long count, Long base, boolean replace, boolean refine) {
        return recordClassPropertyRelation(context, clazz, property.getPropertyName(), context.propertyStates.get(property), count, base, replace, refine);
    }

    // Keep discoveries in the canonical links until the added property has its own count-work state.
    private TripleCount recordClassPropertyRelation(ClassPropertyContext context, SchemaClass clazz, String name,
                                                    ClassPropertyState state, Long count, Long base, boolean replace, boolean refine) {
        TripleCount relation = classPropertyLinks(context, clazz).get(name);
        if (relation == null) {
            relation = new TripleCount(null, null);
            classPropertyLinks(context, clazz).put(name, relation);
            if (state != null) state.missingCounts++;
        }
        if (count != null && (replace || relation.getTripleCount() == null)) {
            if (state != null && relation.getTripleCount() == null) state.missingCounts--;
            relation.setTripleCount(count);
            relation.setTripleCountBase(base);
            if (replace && relation.isCountNeedsRefinement()) {
                relation.setCountNeedsRefinement(false);
                if (state != null) state.pendingRefinements--;
            }
        }
        if (refine && fineTuneClassPropertyCounts(context) && relation.getTripleCount() != null && !relation.isCountNeedsRefinement()) {
            relation.setCountNeedsRefinement(true);
            if (state != null) state.pendingRefinements++;
        }
        return relation;
    }

    // Recalculate count completion after later recovery while keeping structural completeness independent of count availability.
    private void refreshClassPropertyCountMarks(ClassPropertyContext context) {
        context.propertyStates.values().forEach(state -> state.sourceClassCountsOK = state.pendingRefinements > 0 ? 2 : state.missingCounts > 0
                ? (state.sourceClassCountsOK >= 2 ? 2 : 0) : 3);
    }

    // Treat skipped target checks as unresolved coverage and retain the best quality already established.
    protected void setClassPropertyQualityMarks(ClassPropertyContext context) {
        context.classes.forEach((clazz, state) -> {
            int unresolved = state.failures + state.skippedPairChecks;
            if (context.firstPass != null) {
                ClassPropertyState earlier = context.firstPass.classes.get(clazz);
                unresolved += earlier.failures + earlier.skippedPairChecks;
                state.complete = earlier.complete && unresolved == 0;
                int scopeSize = context.firstPass.properties.size() + context.properties.size();
                int quality = state.complete ? 7 : classPropertyFailureQuality(unresolved, scopeSize);
                if (!state.complete && state.unrestrictedClassCoverage >= 4 && state.unrestrictedClassCoverage < 7) {
                    quality = Math.max(quality, state.unrestrictedClassCoverage);
                }
                classPropertyClassQuality(context, clazz, quality);
            } else if (unresolved == 0) {
                state.complete = true;
                classPropertyClassQuality(context, clazz, 7);
            } else if (classPropertyClassQuality(context, clazz) < 3) {
                classPropertyClassQuality(context, clazz, classPropertyFailureQuality(unresolved, context.properties.size()));
            }
        });
        boolean allSampled = context.firstPass == null
                ? context.classes.keySet().stream().allMatch(clazz -> classPropertyClassQuality(context, clazz) >= 4)
                : context.classes.values().stream().allMatch(state -> state.unrestrictedClassCoverage >= 4);
        context.propertyStates.forEach((property, state) -> {
            if (state.skippedPairChecks > 0) return;
            int quality = state.failures == 0 ? 7 : allSampled ? 4 : classPropertyFailureQuality(state.failures, context.classes.size());
            if (state.failures == 0) state.complete = true;
            classPropertyPropertyQuality(context, property, Math.max(classPropertyPropertyQuality(context, property), quality));
        });
    }

    // Calculate only the class-property individual-failure grades rather than imposing them on other extraction workflows.
    static int classPropertyFailureQuality(int failures, int total) {
        if ((long) failures * 10 < total || failures == 1 && total >= 5 || failures == 2 && total >= 10) return 2;
        return (long) failures * 10 < (long) total * 3 ? 1 : 0;
    }

    // Publish canonical mappings for enrichment and release the discovery counts after their direction has been processed.
    private void publishClassPropertyMappings(ClassPropertyContext context) {
        if (context.firstPass == null) {
            for (SchemaClass clazz : context.schema.getClasses()) {
                if (context.target) clazz.setIncomingPropertyDiscoveryCounts(null);
                else clazz.setOutgoingPropertyDiscoveryCounts(null);
            }
        }
        context.properties.values().forEach(property -> { if (context.target) property.getTargetClasses().clear(); else property.getSourceClasses().clear(); });
        context.classes.forEach((clazz, state) -> {
            if (context.target) clazz.setIncomingPropertiesFullList(state.complete); else clazz.setOutgoingPropertiesFullList(state.complete);
            classPropertyLinks(context, clazz).forEach((name, count) -> {
                SchemaExtractorPropertyNodeInfo property = context.properties.get(name);
                if (property == null) return;
                publishClassPropertyLink(context, clazz, property, count);
            });
        });
        context.properties.values().forEach(property -> {
            boolean enabled = context.request.getLogNoClassesForProperty() == SchemaExtractorRequestDto.NoClassesLoggingOptions.yes
                    || !context.target && context.request.getLogNoClassesForProperty() == SchemaExtractorRequestDto.NoClassesLoggingOptions.sourcesOnly;
            if (enabled && classPropertyPropertyQuality(context, property) == 7 && (context.target ? property.getTargetClasses() : property.getSourceClasses()).isEmpty()) {
                SchemaExtractorQueries emptyQuery = context.propertyStates.get(property).emptyClassQuery;
                String queryName = emptyQuery != null ? emptyQuery.name() : context.target ? "No_Target_Class_For_Property" : "No_Source_Class_For_Property";
                SchemaExtractorMessage message = new SchemaExtractorMessage(INFO, context.target ? "No target class for a property" : "No source class for a property", property.getPropertyName(), null, queryName, null);
                message.setFunctionalityType(SchemaExtractorMessage.FunctionalityType.STRUCT);
                context.schema.getMessages().add(message);
            }
        });
    }

    private void publishClassPropertyLink(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property, TripleCount count) {
        SchemaExtractorClassNodeInfo info = new SchemaExtractorClassNodeInfo(clazz.getFullName(), count.getTripleCount(),
                clazz.getClassificationProperty(), clazz.getIsLiteral());
        info.setTripleCountBase(count.getTripleCountBase());
        if (context.target) property.getTargetClasses().add(info); else property.getSourceClasses().add(info);
    }

    // Reuse recorded blank-node statistics and issue a property existence probe only when presence is unknown.
    private boolean noPropertyBlankNodes(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        Long count = context.target ? property.getBlankNodeObjects() : property.getBlankNodeSubjects();
        Boolean global = context.target ? context.schema.getHasBlankNodeObjects() : context.schema.getHasBlankNodeSubjects();
        if (Boolean.FALSE.equals(global) || Long.valueOf(0).equals(count)) return true;
        if (count != null && count > 0) return false;
        SparqlQueryBuilder query = classPropertyQuery(context, context.target ? CHECK_PROPERTY_HAS_BLANK_OBJECTS : CHECK_PROPERTY_HAS_BLANK_SUBJECTS)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
        return !response.hasErrors() && response.getResults().isEmpty();
    }

    // Share unknown class blank-presence observations between source and target directions without changing count statistics.
    private boolean noClassBlankNodes(ClassPropertyContext context, SchemaClass clazz) {
        if (Boolean.FALSE.equals(context.schema.getHasBlankNodeSubjects()) || Long.valueOf(0).equals(clazz.getBlankNodeCount())) return true;
        if (clazz.getBlankNodeCount() != null && clazz.getBlankNodeCount() > 0) return false;
        if (context.blankChecks.containsKey(clazz)) return Boolean.FALSE.equals(context.blankChecks.get(clazz));
        SparqlQueryBuilder query = classPropertyQuery(context, CHECK_CLASS_HAS_BLANK_INSTANCES)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                .withContextParam("<class>", clazz.getFullName(), clazz.getIsLiteral());
        QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
        context.blankChecks.put(clazz, response.hasErrors() ? null : !response.getResults().isEmpty());
        return !response.hasErrors() && response.getResults().isEmpty();
    }

    // Read small blank-free entities without filters or limits and recover failed filtered samples by selecting nonblank terms locally.
    private List<String> readClassPropertyInstances(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property, SchemaClass clazz, int limit) {
        SchemaExtractorQueries name = clazz != null ? FIND_CLASS_INSTANCES : context.target ? FIND_PROPERTY_OBJECTS_INSTANCES : FIND_PROPERTY_SUBJECTS_INSTANCES;
        boolean noBlankNodes = property != null || Boolean.FALSE.equals(context.schema.getHasBlankNodeSubjects())
                || Long.valueOf(0).equals(clazz.getBlankNodeCount()) || Boolean.FALSE.equals(context.blankChecks.get(clazz));
        int queryLimit = noBlankNodes && (property != null || smallClassPropertyEntity(classPropertyClassSize(clazz))) ? 0 : limit;
        SparqlQueryBuilder query = classPropertyQuery(context, name)
                .withContextParam("<instanceFilter>", noBlankNodes ? "" : "FILTER(isIRI(?x))")
                .withContextParam("<limitClause>", queryLimit > 0 ? "LIMIT " + queryLimit : "");
        if (property != null) query.withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        if (clazz != null) query.withContextParam("<class>", clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty());
        List<String> instances = readInstanceTerms(context.request, query, limit);
        if (instances == null && !noBlankNodes) {
            Long size = classPropertyClassSize(clazz);
            query = classPropertyQuery(context, name)
                    .withContextParam("<class>", clazz.getFullName(), clazz.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                    .withContextParam("<instanceFilter>", "")
                    .withContextParam("<limitClause>", size != null && size >= 0 && size < 2000 ? "" : "LIMIT 2000");
            instances = readInstanceTerms(context.request, query, limit);
        }
        // Report only a terminal enumeration failure and leave successful empty or oversized enumerations unchanged.
        if (instances == null) {
            String text = property != null
                    ? "Property " + (context.target ? "object" : "subject") + " enumeration not successful. The instance-based fallback can not be used."
                    : limit == 0 ? "Small class enumeration not successful. The instance-based fallback can not be used."
                    : "Class sample enumeration not successful. The instance-based fallback can not be used.";
            addExtractionMessage(context.schema, NOTE, SchemaExtractorMessage.FunctionalityType.STRUCT_INSTANCE,
                    property != null ? property.getPropertyName() : clazz.getFullName(), null, query, text);
        }
        return instances;
    }

    // Skip literals and blank nodes, whose URI is null in shared query results, and retain at most the requested number of distinct IRIs.
    private List<String> readInstanceTerms(SchemaExtractorRequestDto request, SparqlQueryBuilder query, int limit) {
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        if (response.hasErrors()) return null;
        Set<String> instances = new LinkedHashSet<>();
        for (QueryResult row : response.getResults()) {
            if (row.getResultObject("x") == null) return null;
            String value = row.getValueFullName("x");
            if (value == null || Boolean.TRUE.equals(row.getResultObject("x").getIsLiteral()) || value.startsWith("_:")) continue;
            instances.add(SchemaUtil.addAngleBracketsToString(value));
            if (instances.size() == limit) break;
        }
        return new ArrayList<>(instances);
    }

    // Reuse existing template fallback and context binding for every class-property query.
    private SparqlQueryBuilder classPropertyQuery(ClassPropertyContext context, SchemaExtractorQueries name) {
        return new SparqlQueryBuilder(context.request.getQueries().get(name.name()), name);
    }

    // Attach class scope independently of instance bindings and retain literal class terms where configured.
    private SparqlQueryBuilder propertyClassQuery(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property, String predicate, SchemaExtractorQueries name) {
        String scope = "";
        if (!context.request.getIncludedClasses().isEmpty()) {
            List<String> terms = new ArrayList<>();
            context.classIndex.get(predicate).values().forEach(clazz -> terms.add(Boolean.TRUE.equals(clazz.getIsLiteral()) ? clazz.getFullName() : SchemaUtil.addAngleBracketsToString(clazz.getFullName())));
            scope = buildValuesClause(terms, "class", true);
        }
        return classPropertyQuery(context, name).withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, predicate).withContextParam("<classValuesClause>", scope);
    }

    // Apply the residual-property clause as a restriction of the original scope for all subsequent class query forms.
    private SparqlQueryBuilder classPropertiesQuery(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorQueries name) {
        SparqlQueryBuilder query = classPropertyQuery(context, name)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, clazz.getFullName(), clazz.getIsLiteral());
        Set<String> scope = classSidePropertyScope(context);
        updateValueClause(query, scope, "<propertyValuesClause>", SPARQL_QUERY_BINDING_NAME_PROPERTY);
        updateValueClause(query, scope, SPARQL_QUERY_BINDING_NAME_VALUES, SPARQL_QUERY_BINDING_NAME_PROPERTY);
        return query;
    }

    private Set<String> classSidePropertyScope(ClassPropertyContext context) {
        return context.activeProperties != context.properties || !context.request.getIncludedProperties().isEmpty()
                ? context.activeProperties.keySet() : null;
    }

    // Bind an individual property without reintroducing a residual VALUES clause into pair queries.
    private SparqlQueryBuilder classPropertyPairQuery(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property, SchemaExtractorQueries name) {
        return classPropertyQuery(context, name).withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
    }

    // Return the created diagnostic so a caller can finalize its severity after a fallback without adding a duplicate.
    private SchemaExtractorMessage addExtractionMessage(Schema schema, SchemaExtractorMessage.MessageLevel level, SchemaExtractorMessage.FunctionalityType type,
                                      String entity, String related, SparqlQueryBuilder query) {
        return addExtractionMessage(schema, level, type, entity, related, query, null);
    }

    // Attach optional caller text while retaining the existing entity, relation and actual-query metadata.
    private SchemaExtractorMessage addExtractionMessage(Schema schema, SchemaExtractorMessage.MessageLevel level, SchemaExtractorMessage.FunctionalityType type,
                                      String entity, String related, SparqlQueryBuilder query, String text) {
        SchemaExtractorMessage message = new SchemaExtractorMessage(level, text, entity, related, query.getQueryName(), query.getQueryString());
        message.setFunctionalityType(type);
        schema.getMessages().add(message);
        return message;
    }

    // Apply one schema-message policy to invalid class/property URIs without changing validation or server logging.
    private void addInvalidUriMessage(Schema schema, String entity) {
        SchemaExtractorMessage message = new SchemaExtractorMessage(WARNING, "Invalid URI", entity, null, INVALID_URI, null);
        message.setFunctionalityType(SchemaExtractorMessage.FunctionalityType.INVALID_URI);
        schema.getMessages().add(message);
    }

    // Send per-entity starts only to the detail logger while top-level procedure starts retain console and file logging.
    private void logClassPropertyProcedure(ClassPropertyContext context, String procedure, String entity) {
        String text = context.request.getCorrelationId() + " - " + procedure + (entity == null ? "" : " [" + entity + "]");
        if (entity == null) log.info(text); else procedureDetailLog.info(text);
    }

    // Select the existing sparse relation map without constructing a class-by-property matrix.
    private Map<String, TripleCount> classPropertyLinks(ClassPropertyContext context, SchemaClass clazz) {
        return context.target ? clazz.getIncomingProperties() : clazz.getOutgoingProperties();
    }

    // Require both complete correspondence discovery and completed count work before skipping a property.
    private boolean classPropertyHandled(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        return context.propertyStates.get(property).complete && context.propertyStates.get(property).sourceClassCountsOK == 3;
    }

    // Apply both supported refinement settings to class-property counts while preserving the None default.
    private boolean fineTuneClassPropertyCounts(ClassPropertyContext context) {
        return context.request.getFineTuneTripleCounts() != SchemaExtractorRequestDto.FineTuneTripleCounts.None;
    }

    // Read the direction-specific property marker with zero as its initial quality.
    private int classPropertyPropertyQuality(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property) {
        Integer quality = context.target ? property.getTargetClassesOK() : property.getSourceClassesOK();
        return quality == null ? 0 : quality;
    }

    // Keep target quality at least 3 when the property-side sample estimated absence.
    private void classPropertyPropertyQuality(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property, int quality) {
        if (context.target) {
            property.setTargetClassesOK(isFalse(nonLiteralObjectsExistence(property)) ? Math.max(3, quality) : quality);
        } else {
            property.setSourceClassesOK(quality);
        }
    }

    // Read the direction-specific class marker independently of its stored pair counts.
    private int classPropertyClassQuality(ClassPropertyContext context, SchemaClass clazz) {
        Integer quality = context.target ? clazz.getIncomingPropertiesOK() : clazz.getOutgoingPropertiesOK();
        return quality == null ? 0 : quality;
    }

    // Apply identical quality rules to outgoing and incoming class discovery.
    private void classPropertyClassQuality(ClassPropertyContext context, SchemaClass clazz, int quality) {
        if (context.target) clazz.setIncomingPropertiesOK(quality); else clazz.setOutgoingPropertiesOK(quality);
    }

    // Record the successful LIMIT or actual successful-instance base for the selected direction.
    private void setClassPropertyLimit(ClassPropertyContext context, SchemaClass clazz, Long limit) {
        if (context.target) clazz.setIncomingLimit(limit); else clazz.setOutgoingLimit(limit);
    }

    // Prefer recorded distinct subjects or objects before using the property's triple-count bound.
    private Long classPropertySize(SchemaExtractorPropertyNodeInfo property, boolean target) {
        Long distinct = target ? property.getDistinctObjectsCount() : property.getDistinctSubjectsCount();
        return distinct != null && distinct >= 0 ? distinct : property.getTripleCount();
    }

    // Prefer the recorded distinct class size without issuing another counting query.
    private Long classPropertyClassSize(SchemaClass clazz) {
        Long distinct = clazz.getDistinctInstances();
        return distinct != null && distinct >= 0 ? distinct : clazz.getInstanceCount();
    }

    // Include exactly one thousand instances as required by the revised class-property specification.
    private boolean smallClassPropertyEntity(Long size) {
        return size != null && size >= 0 && size <= CLASS_PROPERTY_INSTANCE_LIMIT;
    }

    // Match grouped component counts to the source classification scope used by subtraction recovery.
    protected boolean determineObjectTripleCountForAllPropertySources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_URL_VALUES_FOR_SOURCES, FIND_PROPERTY_URL_VALUES_FOR_SOURCES_DISTINCT);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.URL_LITERAL,
                        property.getPropertyName(), null, queryBuilder);
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaExtractorClassNodeInfo sourceClass = property.getSourceClasses().stream()
                            .filter(c -> className.equals(c.getClassName()) && classificationProperty.equals(c.getClassificationProperty())).findFirst().orElse(null);
                    if (sourceClass != null) {
                        Long objectTripleCountForSource = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        sourceClass.setObjectTripleCount(objectTripleCountForSource);
                    }
                }
            }
        }
        return found;
    }

    // Retain the URL-only entry point while sharing source-context count recovery and fallback handling.
    protected void determinePropertySourceObjectTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = determineObjectTripleCountForAllPropertySources(schema, property, request, totalCountOfProperties);
        if (!found) property.getSourceClasses().forEach(sourceClass -> determinePropertyValueCounts(schema, property, sourceClass, request, true, false));
    }

    // Match grouped component counts to the source classification scope used by subtraction recovery.
    protected boolean determineDataTripleCountForAllPropertySources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES, FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES_DISTINCT);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.URL_LITERAL,
                        property.getPropertyName(), null, queryBuilder);
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaExtractorClassNodeInfo sourceClass = property.getSourceClasses().stream()
                            .filter(c -> className.equals(c.getClassName()) && classificationProperty.equals(c.getClassificationProperty())).findFirst().orElse(null);
                    if (sourceClass != null) {
                        Long dataTripleCountForSource = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        sourceClass.setDataTripleCount(dataTripleCountForSource);
                    }
                }
            }
        }
        return found;
    }

    // Preserve grouped counts and resolve each source class independently when its results are incomplete.
    protected void determinePropertySourceValueTripleCounts(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceValueTripleCounts [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> sourceClass.setLiteralObjectsKnown(false));
        determineObjectTripleCountForAllPropertySources(schema, property, request, totalCountOfProperties);
        determineDataTripleCountForAllPropertySources(schema, property, request, totalCountOfProperties);
        property.getSourceClasses().forEach(sourceClass -> {
            boolean readObjects = nonNegativeCount(sourceClass.getObjectTripleCount()) == null;
            boolean readLiterals = nonNegativeCount(sourceClass.getDataTripleCount()) == null;
            if (readObjects || readLiterals) {
                determinePropertyValueCounts(schema, property, sourceClass, request, readObjects, readLiterals);
                determinePropertyObjectTripleRatio(schema, property, sourceClass, request);
            }
        });
    }

    // Retain the literal-only entry point and clear fallback-only evidence before reading fresh source counts.
    protected void determinePropertySourceDataTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        property.getSourceClasses().forEach(sourceClass -> sourceClass.setLiteralObjectsKnown(false));
        boolean found = determineDataTripleCountForAllPropertySources(schema, property, request, totalCountOfProperties);
        if (!found) property.getSourceClasses().forEach(sourceClass -> determinePropertyValueCounts(schema, property, sourceClass, request, false, true));
    }

    // Tag pair-discovery diagnostics without changing the count and existence fallback branches.
    protected void determinePropertySourceTargetPairs(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceTargetPairs [" + property.getPropertyName() + "]");

        for (String classificationPropertySource : request.getMainClassificationProperties()) {
            for (String classificationPropertyTarget : request.getMainClassificationProperties()) {
                SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_SOURCE_TARGET_PAIRS, FIND_PROPERTY_SOURCE_TARGET_PAIRS_DISTINCT);
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationPropertySource)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationPropertyTarget)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
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
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.STRUCT_PAIRS,
                            property.getPropertyName(), null, queryBuilder);
                }
            }
        }

        // endpoint was not able to return class pairs with direct query, so trying to find target classes for each source
        if (property.getSourceAndTargetPairs().isEmpty()) {
            log.info(request.getCorrelationId() + " - determinePropertySourceTargetPairsForEachSource [" + property.getPropertyName() + "]");

            property.getSourceClasses().forEach(sourceClass -> {
                for (String classificationPropertyTarget : request.getMainClassificationProperties()) {
                    SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_SOURCE, FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_SOURCE_DISTINCT);
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationPropertyTarget)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.hasErrors()) {
                        for (QueryResult queryResult : queryResponse.getResults()) {
                            String targetClass = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_TARGET);
                            String tripleCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                            if (StringUtils.isNotEmpty(targetClass) && isNotExcludedResource(targetClass, request.getExcludedNamespaces())) {
                                SchemaClass targetSchemaClass = findClass(schema.getClasses(), targetClass);
                                if (targetSchemaClass != null && isNotFalse(targetSchemaClass.getPropertiesInSchema())) {
                                    property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                            sourceClass.getClassName(), targetClass, SchemaUtil.getLongValueFromString(tripleCountStr), sourceClass.getClassificationProperty(), classificationPropertyTarget));
                                }
                            }
                        }
                    } else {
                        // endpoint was not able to return class pairs with direct query, so trying to match each source and target class combination
                        addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.STRUCT_PAIRS,
                                property.getPropertyName(), null, queryBuilder);
                        log.info(request.getCorrelationId() + " - determinePropertySourceTargetPairsForEachSourceAndTargetCombination [" + property.getPropertyName() + "]");
                        property.getTargetClasses().forEach(targetClass -> {
                            SchemaExtractorQueries pairQuery = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES, FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES_DISTINCT);
                            SparqlQueryBuilder pairQueryBuilder = new SparqlQueryBuilder(request.getQueries().get(pairQuery.name()), pairQuery)
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                            QueryResponse pairQueryResponse = sparqlEndpointProcessor.read(request, pairQueryBuilder);
                            if (!pairQueryResponse.getResults().isEmpty() && pairQueryResponse.getResults().get(0) != null) {
                                Long tripleCountForPair = SchemaUtil.getLongValueFromString(pairQueryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                                if (tripleCountForPair > 0) {
                                    property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                            sourceClass.getClassName(), targetClass.getClassName(), tripleCountForPair, sourceClass.getClassificationProperty(), targetClass.getClassificationProperty()));
                                }
                            } else if (pairQueryResponse.hasErrors()) {
                                // check if there is at least a relation between the given source and target
                                SchemaExtractorQueries checkQuery = selectQuery(request.getExactCountCalculations(), CHECK_PROPERTY_SOURCE_TARGET_PAIR, CHECK_PROPERTY_SOURCE_TARGET_PAIR);
                                SparqlQueryBuilder checkQueryBuilder = new SparqlQueryBuilder(request.getQueries().get(checkQuery.name()), checkQuery)
                                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                                QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, checkQueryBuilder);
                                // if the relation is found then create source-target pair but without the triple count
                                if (!checkQueryResponse.getResults().isEmpty() && checkQueryResponse.getResults().get(0) != null) {
                                    // Report the failed count query and both class parameters after positive existence recovery.
                                    addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.COUNT,
                                            property.getPropertyName(), sourceClass.getClassName(), pairQueryBuilder,
                                            "Count information not available for property source and target pair")
                                            .setRelatedEntity2(targetClass.getClassName());
                                    property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                            sourceClass.getClassName(), targetClass.getClassName(), null, sourceClass.getClassificationProperty(), targetClass.getClassificationProperty()));
                                } else if (checkQueryResponse.hasErrors()) {
                                    // Include both class parameters when the existence check fails.
                                    addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT_PAIRS,
                                            property.getPropertyName(), sourceClass.getClassName(), checkQueryBuilder,
                                            "Property source and target pair can not be checked. Source and target pair possibility is assumed for property.")
                                            .setRelatedEntity2(targetClass.getClassName());
                                    SchemaExtractorSourceTargetInfo assumedPair = new SchemaExtractorSourceTargetInfo(
                                            sourceClass.getClassName(), targetClass.getClassName(), null, sourceClass.getClassificationProperty(), targetClass.getClassificationProperty());
                                    assumedPair.setIsAssumed(true);
                                    property.getSourceAndTargetPairs().add(assumedPair);
                                }
                            }
                        });
                    }
                }
            });
        }
    }

    protected void validateBlankNodesForClasses(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - validateBlankNodesForClasses ");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(VALIDATE_BLANK_NODES_FOR_CLASS.name()), VALIDATE_BLANK_NODES_FOR_CLASS);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors()) {
            schema.setHasBlankNodeSubjects(!queryResponse.getResults().isEmpty());
        }
    }

    protected void validateBlankNodesForProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - validateBlankNodesForProperties ");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(VALIDATE_BLANK_NODES_FOR_PROPERTY.name()), VALIDATE_BLANK_NODES_FOR_PROPERTY);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors()) {
            schema.setHasBlankNodeObjects(!queryResponse.getResults().isEmpty());
        }
    }

    protected void determineBlankValuesForClasses(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineBlankValuesForClasses ");

        // read blank values for all classes
        List<String> classificationPropertiesWithError = new ArrayList<>();
        for (String classificationProperty : request.getAllClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_BLANK_INSTANCES_FOR_CLASSES.name()), COUNT_BLANK_INSTANCES_FOR_CLASSES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.BLANKS,
                        null, null, queryBuilder);
                classificationPropertiesWithError.add(classificationProperty);
            } else {
                schema.getClasses().stream()
                        .filter(clazz -> classificationProperty.equals(clazz.getClassificationProperty()) && clazz.getBlankNodeCount() == null)
                        .forEach(clazz -> clazz.setBlankNodeCount(0L));
                for (QueryResult queryResult : queryResponse.getResults()) {
                    String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                    if (className == null) {
                        continue;
                    }
                    SchemaClass clazz = findClass(schema.getClasses(), className);
                    if (clazz == null) {
                        continue;
                    }
                    String instancesCountStr = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                    if (StringUtils.isNotEmpty(instancesCountStr)) {
                        Long instanceCount = SchemaUtil.getLongValueFromString(instancesCountStr);
                        clazz.setBlankNodeCount(instanceCount);
                    }
                }

            }
        }
        // if statistics query returns error then read blank nodes count separately for each class
        if (!classificationPropertiesWithError.isEmpty()) {
            for (SchemaClass clazz : schema.getClasses()) {
                if (!classificationPropertiesWithError.contains(clazz.getClassificationProperty())) {
                    continue;
                }
                log.info(request.getCorrelationId() + " - determineBlankValuesForClass [" + clazz.getFullName() + "]");
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_BLANK_INSTANCES_FOR_CLASS.name()), COUNT_BLANK_INSTANCES_FOR_CLASS)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_FULL, clazz.getFullName(), clazz.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors()) {
                    if (!queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                        long blankNodeCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        clazz.setBlankNodeCount(blankNodeCount);
                    } else {
                        clazz.setBlankNodeCount(0L);
                    }
                } else {
                    addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.BLANKS,
                            clazz.getFullName(), null, queryBuilder);
                }
            }
        }
    }

    protected Map<String, Long> determineBlankNodeSubjectsForAllProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request,
                                                                           @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - determineBlankNodeSubjectsForAllProperties ");

        Map<String, Long> blankNodeMap = new HashMap<>();
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_SOURCE_BLANK_VALUES_FOR_PROPERTIES.name()), COUNT_SOURCE_BLANK_VALUES_FOR_PROPERTIES);
        if (!request.getIncludedProperties().isEmpty()) {
            if (!properties.keySet().isEmpty()) {
                queryBuilder.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES, buildValuesClause(properties.keySet(), SPARQL_QUERY_BINDING_NAME_PROPERTY));
            }
        } else {
            queryBuilder.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES, StringUtils.EMPTY);
        }
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors()) {
            properties.keySet().forEach(propertyName -> blankNodeMap.put(propertyName, 0L));
            for (QueryResult queryResult : queryResponse.getResults()) {
                String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
                String instanceCount = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                if (StringUtils.isNotEmpty(instanceCount)) {
                    blankNodeMap.put(propertyName, SchemaUtil.getLongValueFromString(instanceCount));
                }
            }
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.BLANKS,
                    null, null, queryBuilder);
        }
        return blankNodeMap;
    }

    protected Map<String, Long> determineBlankNodeObjectsForAllProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request,
                                                                          @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - determineBlankNodeObjectsForAllProperties ");

        Map<String, Long> blankNodeMap = new HashMap<>();
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_TARGET_BLANK_VALUES_FOR_PROPERTIES.name()), COUNT_TARGET_BLANK_VALUES_FOR_PROPERTIES);
        if (!request.getIncludedProperties().isEmpty()) {
            if (!properties.keySet().isEmpty()) {
                queryBuilder.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES, buildValuesClause(properties.keySet(), SPARQL_QUERY_BINDING_NAME_PROPERTY));
            }
        } else {
            queryBuilder.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES, StringUtils.EMPTY);
        }
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors()) {
            properties.keySet().forEach(propertyName -> blankNodeMap.put(propertyName, 0L));
            for (QueryResult queryResult : queryResponse.getResults()) {
                String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
                String instanceCount = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                if (StringUtils.isNotEmpty(instanceCount)) {
                    blankNodeMap.put(propertyName, SchemaUtil.getLongValueFromString(instanceCount));
                }
            }
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.BLANKS,
                    null, null, queryBuilder);
        }
        return blankNodeMap;
    }

    protected void determinePropertyBlankNodeSubjects(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyBlankNodeSubjects [" + property.getPropertyName() + "]");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_SOURCE_BLANK_VALUES_FOR_PROPERTY.name()), COUNT_SOURCE_BLANK_VALUES_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors()) {
            if (!queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                long blankNodeCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                property.setBlankNodeSubjects(blankNodeCount);
            } else {
                property.setBlankNodeSubjects(0L);
            }
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.BLANKS,
                    property.getPropertyName(), null, queryBuilder);
        }
    }

    protected void determinePropertyBlankNodeObjects(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determinePropertyBlankNodeObjects [" + property.getPropertyName() + "]");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_TARGET_BLANK_VALUES_FOR_PROPERTY.name()), COUNT_TARGET_BLANK_VALUES_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors()) {
            if (!queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                long blankNodeCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                property.setBlankNodeObjects(blankNodeCount);
            } else {
                property.setBlankNodeObjects(0L);
            }
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.BLANKS,
                    property.getPropertyName(), null, queryBuilder);
        }
    }

    protected Map<String, Long> determineDistinctSubjectsForAllProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDistinctSubjectsForAllProperties ");

        Map<String, Long> subjectsMap = new HashMap<>();
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_DISTINCT_SUBJECTS_FOR_PROPERTIES.name()), COUNT_DISTINCT_SUBJECTS_FOR_PROPERTIES);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
            for (QueryResult queryResult : queryResponse.getResults()) {
                String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
                String distinctSubjectsCount = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                if (StringUtils.isNotEmpty(distinctSubjectsCount)) {
                    subjectsMap.put(propertyName, SchemaUtil.getLongValueFromString(distinctSubjectsCount));
                }
            }
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    null, null, queryBuilder);
        }
        return subjectsMap;
    }

    protected Map<String, Long> determineDistinctObjectsForAllProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDistinctObjectsForAllProperties ");

        Map<String, Long> objectsMap = new HashMap<>();
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_DISTINCT_OBJECTS_FOR_PROPERTIES.name()), COUNT_DISTINCT_OBJECTS_FOR_PROPERTIES);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
            for (QueryResult queryResult : queryResponse.getResults()) {
                String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
                String distinctObjectsCount = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                if (StringUtils.isNotEmpty(distinctObjectsCount)) {
                    objectsMap.put(propertyName, SchemaUtil.getLongValueFromString(distinctObjectsCount));
                }
            }
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    null, null, queryBuilder);
        }
        return objectsMap;
    }

    protected void determineDistinctPropertySubjects(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDistinctPropertySubjects [" + property.getPropertyName() + "]");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY.name()), COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            long distinctSubjectsCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            property.setDistinctSubjectsCount(distinctSubjectsCount);
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    property.getPropertyName(), null, queryBuilder);
        }
    }

    protected void determineDistinctPropertyObjects(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDistinctPropertyObjects [" + property.getPropertyName() + "]");

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_DISTINCT_OBJECTS_FOR_PROPERTY.name()), COUNT_DISTINCT_OBJECTS_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            long distinctObjectsCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            property.setDistinctObjectsCount(distinctObjectsCount);
        } else if (queryResponse.hasErrors()) {
            addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                    property.getPropertyName(), null, queryBuilder);
        }
    }

    protected boolean determineDistinctPropertySubjectsForAllSources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDistinctPropertySubjectsForAllSources [" + property.getPropertyName() + "]");

        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY_SOURCES, COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY_SOURCES);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                        property.getPropertyName(), null, queryBuilder);
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaExtractorClassNodeInfo sourceClass = property.getSourceClasses().stream()
                            .filter(c -> className.equals(c.getClassName())).findFirst().orElse(null);
                    if (sourceClass != null) {
                        String distinctSubjectsCount = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                        if (StringUtils.isNotEmpty(distinctSubjectsCount)) {
                            sourceClass.setDistinctSubjectsCount(SchemaUtil.getLongValueFromString(distinctSubjectsCount));
                        }
                    }
                }
            }
        }
        return found;
    }

    protected void determineDistinctPropertySubjectsOnSourceClassLevel(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {

        // get distinct subjects for the specific property and for all source classes
        boolean found = determineDistinctPropertySubjectsForAllSources(schema, property, request);
        if (found) return;

        log.info(request.getCorrelationId() + " - determineDistinctPropertySubjectsOnSourceClassLevel [" + property.getPropertyName() + "]");

        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY_SOURCE.name()), COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                long distinctSubjectsCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                sourceClass.setDistinctSubjectsCount(distinctSubjectsCount);
            } else if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                        property.getPropertyName(), null, queryBuilder);
            }
        });
    }

    protected boolean determineDistinctPropertyObjectsForAllSources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        log.info(request.getCorrelationId() + " - determineDistinctPropertyObjectsForAllSources [" + property.getPropertyName() + "]");

        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), COUNT_DISTINCT_OBJECTS_FOR_PROPERTY_TARGETS, COUNT_DISTINCT_OBJECTS_FOR_PROPERTY_TARGETS);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, INFO, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                        property.getPropertyName(), null, queryBuilder);
                continue;
            }
            if (!queryResponse.getResults().isEmpty()) {
                found = true;
            }
            for (QueryResult queryResult : queryResponse.getResults()) {
                String className = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                if (StringUtils.isNotEmpty(className) && isNotExcludedResource(className, request.getExcludedNamespaces())) {
                    SchemaExtractorClassNodeInfo targetClass = property.getTargetClasses().stream()
                            .filter(c -> className.equals(c.getClassName())).findFirst().orElse(null);
                    if (targetClass != null) {
                        String distinctObjectsount = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                        if (StringUtils.isNotEmpty(distinctObjectsount)) {
                            targetClass.setDistinctObjectsCount(SchemaUtil.getLongValueFromString(distinctObjectsount));
                        }
                    }
                }
            }
        }
        return found;
    }

    protected void determineDistinctPropertyObjectsOnTargetClassLevel(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {

        // get distinct objects for the specific property and for all target classes
        boolean found = determineDistinctPropertyObjectsForAllSources(schema, property, request);
        if (found) return;

        log.info(request.getCorrelationId() + " - determineDistinctPropertyObjectsOnTargetClassLevel [" + property.getPropertyName() + "]");

        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(COUNT_DISTINCT_OBJECTS_FOR_PROPERTY_TARGET.name()), COUNT_DISTINCT_OBJECTS_FOR_PROPERTY_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                long distinctObjectsCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                targetClass.setDistinctObjectsCount(distinctObjectsCount);
            } else if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.DISTINCT,
                        property.getPropertyName(), null, queryBuilder);
            }
        });
    }

    // Finalize inference markers and NOTE text together after all required closed-set checks have been considered.
    private Integer finalizeClosedSourceTargetEstimate(List<SchemaExtractorMessage> failures, boolean canEstimate, boolean source) {
        if (!canEstimate || failures.isEmpty()) return null;
        String text = "The closed " + (source ? "source" : "target") + " assertion may be inferred based on the query failure";
        failures.forEach(message -> message.setMessage(text));
        return -1;
    }

    // Keep failed property-source checks false and treat successful blank-node witnesses as counterexamples before inferring closure.
    protected void determinePropertyClosedSources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedSources [" + property.getPropertyName() + "]");

        property.setClosedSourceAssertedSize(null);
        // Check for a property subject without a bound class.
        if (property.getSourceClasses().isEmpty()) {
            property.setIsClosedSource((property.getTripleCount() != null && property.getTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedSource(Boolean.TRUE);
            boolean canEstimate = request.getEstimateClosedSourceTarget();
            List<SchemaExtractorMessage> failures = new ArrayList<>();
            for (String classificationProperty : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_SOURCE_FOR_PROPERTY.name()), FIND_CLOSED_SOURCE_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    property.setIsClosedSource(Boolean.FALSE);
                    failures.add(addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.CLOSED_SOURCE_TARGET,
                            property.getPropertyName(), null, queryBuilder));
                    Long shortestFailedAttempt = queryResponse.getShortestFailedAttemptDurationNanos();
                    canEstimate &= shortestFailedAttempt != null && shortestFailedAttempt > TimeUnit.SECONDS.toNanos(45);
                } else if (!queryResponse.getResults().isEmpty()) {
                    property.setIsClosedSource(Boolean.FALSE);
                    canEstimate = false;
                    break;
                }
            }
            property.setClosedSourceAssertedSize(finalizeClosedSourceTargetEstimate(failures, canEstimate, true));
        }
    }

    // Keep failed property-target checks false and treat successful blank-node witnesses as counterexamples before inferring closure.
    protected void determinePropertyClosedTargets(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedTargets [" + property.getPropertyName() + "]");

        property.setClosedTargetAssertedSize(null);
        // Check for a non-literal property object without a bound class.
        if (property.getTargetClasses().isEmpty()) {
            property.setIsClosedTarget(isTrue(nonLiteralObjectsExistence(property)) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedTarget(Boolean.TRUE);
            boolean canEstimate = request.getEstimateClosedSourceTarget();
            List<SchemaExtractorMessage> failures = new ArrayList<>();
            for (String classificationProperty : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_TARGET_FOR_PROPERTY.name()), FIND_CLOSED_TARGET_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    property.setIsClosedTarget(Boolean.FALSE);
                    failures.add(addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.CLOSED_SOURCE_TARGET,
                            property.getPropertyName(), null, queryBuilder));
                    Long shortestFailedAttempt = queryResponse.getShortestFailedAttemptDurationNanos();
                    canEstimate &= shortestFailedAttempt != null && shortestFailedAttempt > TimeUnit.SECONDS.toNanos(45);
                } else if (!queryResponse.getResults().isEmpty()) {
                    property.setIsClosedTarget(Boolean.FALSE);
                    canEstimate = false;
                    break;
                }
            }
            property.setClosedTargetAssertedSize(finalizeClosedSourceTargetEstimate(failures, canEstimate, false));
        }
    }

    // Retain verified closed-target inheritance and reject failed source-context checks or counterexamples, including blank-node witnesses.
    protected void determinePropertyClosedTargetsOnSourceClassLevel(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedTargetsOnSourceClassLevel [" + property.getPropertyName() + "]");

        // Check for an unclassified non-literal object reached from this source class.
        if (isTrue(property.getIsClosedTarget())) {
            property.getSourceClasses().forEach(sourceClass -> {
                sourceClass.setIsClosedTarget(Boolean.TRUE);
                sourceClass.setClosedTargetAssertedSize(null);
            });
        } else if (isTrue(request.getCalculateSourceAndTargetPairs())) {
            property.getSourceClasses().forEach(sourceClass -> {
                sourceClass.setIsClosedTarget(Boolean.TRUE);
                sourceClass.setClosedTargetAssertedSize(null);
                boolean canEstimate = request.getEstimateClosedSourceTarget();
                List<SchemaExtractorMessage> failures = new ArrayList<>();
                for (String classificationProperty : request.getMainClassificationProperties()) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_TARGET_FOR_PROPERTY_AND_CLASS.name()), FIND_CLOSED_TARGET_FOR_PROPERTY_AND_CLASS)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (queryResponse.hasErrors()) {
                        sourceClass.setIsClosedTarget(Boolean.FALSE);
                        failures.add(addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.CLOSED_SOURCE_TARGET,
                                property.getPropertyName(), sourceClass.getClassName(), queryBuilder));
                        Long shortestFailedAttempt = queryResponse.getShortestFailedAttemptDurationNanos();
                        canEstimate &= shortestFailedAttempt != null && shortestFailedAttempt > TimeUnit.SECONDS.toNanos(45);
                    } else if (!queryResponse.getResults().isEmpty()) {
                        sourceClass.setIsClosedTarget(Boolean.FALSE);
                        canEstimate = false;
                        break;
                    }
                }
                sourceClass.setClosedTargetAssertedSize(finalizeClosedSourceTargetEstimate(failures, canEstimate, false));
            });
        }
    }

    // Retain verified closed-source inheritance and reject failed target-context checks or counterexamples, including blank-node witnesses.
    protected void determinePropertyClosedSourcesOnTargetClassLevel(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedSourcesOnTargetClassLevel [" + property.getPropertyName() + "]");

        // Check for an unclassified subject reaching this target class.
        if (isTrue(property.getIsClosedSource())) {
            property.getTargetClasses().forEach(targetClass -> {
                targetClass.setIsClosedSource(Boolean.TRUE);
                targetClass.setClosedSourceAssertedSize(null);
            });
        } else if (isTrue(request.getCalculateSourceAndTargetPairs())) {
            property.getTargetClasses().forEach(targetClass -> {
                targetClass.setIsClosedSource(Boolean.TRUE);
                targetClass.setClosedSourceAssertedSize(null);
                boolean canEstimate = request.getEstimateClosedSourceTarget();
                List<SchemaExtractorMessage> failures = new ArrayList<>();
                for (String classificationProperty : request.getMainClassificationProperties()) {
                    SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_SOURCE_FOR_PROPERTY_AND_CLASS.name()), FIND_CLOSED_SOURCE_FOR_PROPERTY_AND_CLASS)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (queryResponse.hasErrors()) {
                        targetClass.setIsClosedSource(Boolean.FALSE);
                        failures.add(addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.CLOSED_SOURCE_TARGET,
                                property.getPropertyName(), targetClass.getClassName(), queryBuilder));
                        Long shortestFailedAttempt = queryResponse.getShortestFailedAttemptDurationNanos();
                        canEstimate &= shortestFailedAttempt != null && shortestFailedAttempt > TimeUnit.SECONDS.toNanos(45);
                    } else if (!queryResponse.getResults().isEmpty()) {
                        targetClass.setIsClosedSource(Boolean.FALSE);
                        canEstimate = false;
                        break;
                    }
                }
                targetClass.setClosedSourceAssertedSize(finalizeClosedSourceTargetEstimate(failures, canEstimate, true));
            });
        }
    }

    protected void determinePropertyDataTypes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTypes [" + property.getPropertyName() + "]");
        determineDataTypes(schema, property, null, request, nonNegativeCount(property.getTripleCount()));
    }

    protected void determinePropertySourceDataTypes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceDataTypes [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass ->
                determineDataTypes(schema, property, sourceClass, request, sourceDataTypeLimitCap(schema, property, sourceClass)));
    }

    // Extrapolate sampled source counts for the limit cap, using available population counts when extrapolation is unavailable.
    private Long sourceDataTypeLimitCap(Schema schema, SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass) {
        Long propertyTotal = nonNegativeCount(property.getTripleCount());
        Long sourceCount = nonNegativeCount(sourceClass.getTripleCount());
        Long base = sourceClass.getTripleCountBase();
        if (propertyTotal != null && sourceCount != null && base != null && base > 0) {
            BigDecimal total = BigDecimal.valueOf(propertyTotal);
            return total.multiply(BigDecimal.valueOf(sourceCount))
                    .divideToIntegralValue(BigDecimal.valueOf(base)).min(total).longValueExact();
        }
        Long classSize = schema.getClasses().stream()
                .filter(clazz -> Objects.equals(clazz.getFullName(), sourceClass.getClassName())
                        && Objects.equals(clazz.getClassificationProperty(), sourceClass.getClassificationProperty()))
                .map(clazz -> nonNegativeCount(clazz.getInstanceCount()))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        if (propertyTotal == null) return classSize;
        return classSize == null ? propertyTotal : Long.valueOf(Math.min(propertyTotal, classSize));
    }

    // Keep the smallest failed attempt for one diagnostic and publish only the successful attempt's counts and limit.
    private void determineDataTypes(Schema schema, SchemaExtractorPropertyNodeInfo property, SchemaExtractorClassNodeInfo sourceClass,
                                    SchemaExtractorRequestDto request, Long limitCap) {
        Long limit = request.getSampleLimitForDataTypeCalculation();
        Long literalCount = nonNegativeCount(property.getDataTripleCount());
        if (limit == null || limit <= 0 || (literalCount != null && literalCount <= limit)
                || (limitCap != null && limit > limitCap)) limit = null;

        String relatedEntity = sourceClass == null ? null : sourceClass.getClassName();
        SparqlQueryBuilder query = dataTypeQuery(request, property, sourceClass, limit);
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        if (response.hasErrors()) {
            for (Long fallbackLimit : sampleLimits) {
                if ((limitCap != null && fallbackLimit > limitCap) || (limit != null && fallbackLimit >= limit)) continue;
                SparqlQueryBuilder fallback = dataTypeQuery(request, property, sourceClass, fallbackLimit);
                response = sparqlEndpointProcessor.read(request, fallback);
                if (!response.hasErrors()) {
                    limit = fallbackLimit;
                    break;
                }
                query = fallback;
            }
            if (response.hasErrors()) {
                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.DATATYPE,
                        property.getPropertyName(), relatedEntity, query, "Data types for property objects not found");
                return;
            }
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.DATATYPE,
                    property.getPropertyName(), relatedEntity, query);
        }

        List<SchemaExtractorDataTypeInfo> dataTypes = sourceClass == null ? property.getDataTypes() : sourceClass.getDataTypes();
        boolean hasEmptyDataType = false;
        for (QueryResult row : response.getResults()) {
            String dataType = row.getValue(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
            if (StringUtils.isNotEmpty(dataType)) {
                Long count = SchemaUtil.getLongValueFromString(row.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                dataTypes.add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(dataType), count, limit));
            } else {
                hasEmptyDataType = true;
            }
        }

        if (hasEmptyDataType) {
            query = languageTagQuery(request, property, sourceClass, limit);
            response = sparqlEndpointProcessor.read(request, query);
            if (response.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.DATATYPE,
                        property.getPropertyName(), relatedEntity, query);
            } else if (!response.getResults().isEmpty()) {
                Long count = SchemaUtil.getLongValueFromString(response.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (count > 0) dataTypes.add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, count, limit));
            }
        }
    }

    private SparqlQueryBuilder dataTypeQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                             SchemaExtractorClassNodeInfo sourceClass, Long limit) {
        if (limit == null) {
            return propertyValueQuery(request, property, sourceClass,
                    sourceClass == null ? FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT : FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE,
                    sourceClass == null ? FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_DISTINCT : FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_DISTINCT);
        }
        return propertyValueQuery(request, property, sourceClass,
                sourceClass == null ? FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS : FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS,
                sourceClass == null ? FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS_DISTINCT : FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS_DISTINCT)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
    }

    private SparqlQueryBuilder languageTagQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                SchemaExtractorClassNodeInfo sourceClass, Long limit) {
        if (limit == null) {
            return propertyValueQuery(request, property, sourceClass,
                    sourceClass == null ? FIND_PROPERTY_DATA_TYPE_LANG_STRING : FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE,
                    sourceClass == null ? FIND_PROPERTY_DATA_TYPE_LANG_STRING_DISTINCT : FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_DISTINCT);
        }
        return propertyValueQuery(request, property, sourceClass,
                sourceClass == null ? FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS : FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS,
                sourceClass == null ? FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS_DISTINCT : FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS_DISTINCT)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
    }

    // Discover directional followers here and complete unresolved pairs in the ordered PP phase.
    protected void determineFollowers(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                      @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        discoverPropertyRelation(schema, properties, request, property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower, false);
    }

    protected void determineOutgoingProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                               @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        discoverPropertyRelation(schema, properties, request, property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing, false);
    }

    protected void determineIncomingProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                               @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        discoverPropertyRelation(schema, properties, request, property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming, false);
    }

    // Share the same templates and fallbacks between fixed-scope extraction and property discovery.
    private void discoverPropertyRelation(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                          SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                          SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, boolean discoverNewProperties) {
        String procedure = switch (type) {
            case outgoing -> "OutgoingProperties";
            case incoming -> "IncomingProperties";
            case follower -> "Followers";
        };
        log.info(request.getCorrelationId() + " - determineProperty" + procedure + " [" + property.getPropertyName() + "]");
        SchemaExtractorQueries full = switch (type) {
            case outgoing -> selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_OUTGOING_PROPERTIES, FIND_PROPERTY_OUTGOING_PROPERTIES_DISTINCT);
            case incoming -> selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_INCOMING_PROPERTIES, FIND_PROPERTY_INCOMING_PROPERTIES_DISTINCT);
            case follower -> selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_FOLLOWERS, FIND_PROPERTY_FOLLOWERS_DISTINCT);
        };
        SchemaExtractorQueries limited = switch (type) {
            case outgoing -> selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS, FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS_DISTINCT);
            case incoming -> selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS, FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS_DISTINCT);
            case follower -> selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_FOLLOWERS_WITH_LIMITS, FIND_PROPERTY_FOLLOWERS_WITH_LIMITS_DISTINCT);
        };
        List<SchemaExtractorPropertyRelatedPropertyInfo> links = propertyRelationLinks(property, type);
        Integer quality = discoverNewProperties
                ? executePropertyRelationsQueries(request, schema, properties, property, links, type, full, limited, true)
                : executePropertyRelationsQueries(request, schema, properties, property, links, type, full, limited);
        setPropertyRelationQuality(property, type, quality);
    }

    // Keep PP recovery state per reference property and store only discovered positive links.
    private static final class PropertyRelationState extends InstanceQueryState {
        final Map<String, SchemaExtractorPropertyRelatedPropertyInfo> links = new LinkedHashMap<>();
        int quality;
        int unresolved;
        boolean outsideResolved;
        Long limit;

        PropertyRelationState(List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties, int quality) {
            relatedProperties.forEach(link -> links.put(link.getPropertyName(), link));
            this.quality = quality;
        }
    }

    // Run PP after basic statistics and CP, finishing symmetric discovery before the individual pair pass.
    protected void determinePropertyPropertyRelations(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                       SchemaExtractorRequestDto request) {
        determinePropertyRelations(schema, properties, request, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing);
        determinePropertyRelations(schema, properties, request, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming);
        determinePropertyRelations(schema, properties, request, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower);
    }

    // Keep fixed-scope callers on the same per-direction query and completion order.
    protected void determinePropertyRelations(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                              SchemaExtractorRequestDto request, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        discoverFixedPropertyRelations(schema, properties, request, type);
        executeDetailsQueriesOption2(request, schema, properties, type);
    }

    private void discoverFixedPropertyRelations(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                 SchemaExtractorRequestDto request, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        for (SchemaExtractorPropertyNodeInfo property : properties.values()) {
            switch (type) {
                case outgoing -> determineOutgoingProperties(schema, property, request, properties);
                case incoming -> determineIncomingProperties(schema, property, request, properties);
                case follower -> determineFollowers(schema, property, request, properties);
            }
        }
    }

    // Initialize a newly discovered predicate once, before using it as a reference in any direction.
    protected void discoverPropertyPropertyRelations(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                      SchemaExtractorRequestDto request,
                                                      java.util.function.Consumer<SchemaExtractorPropertyNodeInfo> initializeNewProperty) {
        var directions = List.of(SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing,
                SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower);
        if (!request.getIncludedProperties().isEmpty()) {
            directions.forEach(type -> discoverFixedPropertyRelations(schema, properties, request, type));
            return;
        }

        Deque<SchemaExtractorPropertyNodeInfo> pending = new ArrayDeque<>(properties.values());
        while (!pending.isEmpty()) {
            SchemaExtractorPropertyNodeInfo property = pending.removeFirst();
            for (var type : directions) {
                discoverPropertyRelation(schema, properties, request, property, type, true);
                for (SchemaExtractorPropertyRelatedPropertyInfo link : propertyRelationLinks(property, type)) {
                    String name = link.getPropertyName();
                    if (properties.containsKey(name)) continue;
                    addProperty(name, null, properties, request);
                    SchemaExtractorPropertyNodeInfo added = properties.get(name);
                    initializeNewProperty.accept(added);
                    pending.addLast(added);
                }
            }
        }
    }

    // Complete PP pairs only after the discovery queue and the second CP pass have finished.
    protected void completePropertyPropertyRelations(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                        SchemaExtractorRequestDto request) {
        executeDetailsQueriesOption2(request, schema, properties, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing);
        executeDetailsQueriesOption2(request, schema, properties, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming);
        executeDetailsQueriesOption2(request, schema, properties, SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower);
    }

    // Apply PP discovery diagnostics and downgrade only the first failed LIMIT when the existing instance backup is usable.
    protected Integer executePropertyRelationsQueries(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema,
                                                      @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                      @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                                      @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                                       @Nonnull SchemaExtractorQueries queryWithoutLimit, @Nonnull SchemaExtractorQueries queryWithLimit) {
        return executePropertyRelationsQueries(request, schema, properties, property, relatedProperties, linkType, queryWithoutLimit, queryWithLimit, false);
    }

    private Integer executePropertyRelationsQueries(SchemaExtractorRequestDto request, Schema schema,
                                                      Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                      SchemaExtractorPropertyNodeInfo property, List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                                      SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType, SchemaExtractorQueries queryWithoutLimit,
                                                      SchemaExtractorQueries queryWithLimit, boolean discoverNewProperties) {
        PropertyRelationState state = new PropertyRelationState(relatedProperties, 0);
        Long size = propertyRelationSize(property, linkType);
        if (Long.valueOf(0).equals(size)) return 7;
        SparqlQueryBuilder query = propertyRelationQuery(request, property, linkType, queryWithoutLimit);
        updateValueClause(request, properties, query);
        Map<String, Long> counts = readPropertyRelationCounts(request, properties, query, discoverNewProperties);
        if (counts != null) {
            updatedRelatedProperties(state, counts, linkType, null, true);
            relatedProperties.clear();
            relatedProperties.addAll(state.links.values());
            return 7;
        }
        addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.STRUCT_PP, property.getPropertyName(), null, query);

        boolean noBlank = propertyRelationNoBlankNodes(schema, request, property, linkType, smallClassPropertyEntity(size));
        if (smallClassPropertyEntity(size) && noBlank) {
            List<String> instances = readPropertyRelationInstances(schema, request, property, linkType, size, true, false);
            if (instances != null) {
                InstanceBatchResult<String> result = executePropertyRelationBatches(schema, request, properties, property, linkType, instances, state, discoverNewProperties);
                int covered = result.covered.cardinality();
                boolean complete = covered == instances.size();
                boolean counted = complete || (long) covered * 10 >= (long) instances.size() * 3;
                updatedRelatedProperties(state, result.counts, linkType, counted && !complete ? (long) covered : null, counted);
                state.quality = complete ? 7 : counted ? 5 : covered > 0 ? 1 : 0;
            }
        }
        if (state.quality != 7) {
            SchemaExtractorMessage limitFailure = executeLimitQueries(schema, request, properties, property, linkType, queryWithLimit, size, state, discoverNewProperties);
            if (state.limit == null) {
                List<String> instances = readPropertyRelationInstances(schema, request, property, linkType, size, noBlank, true);
                if (instances != null && !instances.isEmpty()) {
                    InstanceBatchResult<String> result = executePropertyRelationBatches(schema, request, properties, property, linkType, instances, state, discoverNewProperties);
                    int covered = result.covered.cardinality();
                    boolean enough = instances.size() >= 500 || size != null && (long) instances.size() * 2 >= size;
                    boolean usable = enough && (long) covered * 2 >= instances.size();
                    updatedRelatedProperties(state, result.counts, linkType, usable ? (long) covered : null, usable);
                    if (usable) {
                        state.quality = Math.max(state.quality, 3);
                        if (limitFailure != null) limitFailure.setMessageLevel(NOTE);
                    }
                }
            }
        }
        relatedProperties.clear();
        relatedProperties.addAll(state.links.values());
        switch (linkType) {
            case outgoing -> property.setOutgoingLimit(state.limit);
            case incoming -> property.setIncomingLimit(state.limit);
            case follower -> property.setFollowersLimit(state.limit);
        }
        return state.quality;
    }

    // Parameterize scope placement while preserving the existing property-relation defaults.
    private void updateValueClause(@Nonnull SchemaExtractorRequestDto request, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                   @Nonnull SparqlQueryBuilder queryBuilder) {
        Set<String> scope = !request.getIncludedProperties().isEmpty() && !properties.isEmpty() ? properties.keySet() : null;
        updateValueClause(queryBuilder, scope, SPARQL_QUERY_BINDING_NAME_VALUES, SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
    }

    // Distinguish an absent restriction from an explicitly empty scope for independent VALUES placeholders.
    private void updateValueClause(SparqlQueryBuilder query, Set<String> scope, String placeholder, String variable) {
        query.withContextParam(placeholder, scope == null ? StringUtils.EMPTY : buildValuesClause(scope, variable));
    }

    // Preserve accepted earlier-stage counts while adding every positive discovery with its own coverage base.
    private void updatedRelatedProperties(PropertyRelationState state, Map<String, Long> counts,
                                          SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, Long base, boolean counted) {
        counts.forEach((name, count) -> {
            SchemaExtractorPropertyRelatedPropertyInfo link = propertyRelationLink(state, name, type);
            if (counted && link.getTripleCount() == null) {
                link.setTripleCount(count);
                link.setTripleCountBase(base);
            }
        });
    }

    private SchemaExtractorPropertyRelatedPropertyInfo propertyRelationLink(PropertyRelationState state, String name,
                                                                            SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        return state.links.computeIfAbsent(name, key -> new SchemaExtractorPropertyRelatedPropertyInfo(key, null, null, type));
    }

    // Report only a failed first issued LIMIT, returning its diagnostic so usable instance recovery can downgrade it.
    private SchemaExtractorMessage executeLimitQueries(Schema schema, SchemaExtractorRequestDto request, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                     SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type,
                                      SchemaExtractorQueries name, Long size, PropertyRelationState state, boolean discoverNewProperties) {
        Map<String, TripleCount> best = new LinkedHashMap<>();
        SchemaExtractorMessage failure = null;
        for (long limit : CLASS_PROPERTY_LIMITS) {
            if (size != null && limit > size) break;
            SparqlQueryBuilder query = propertyRelationQuery(request, property, type, name)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, Long.toString(limit));
            updateValueClause(request, properties, query);
            Map<String, Long> counts = readPropertyRelationCounts(request, properties, query, discoverNewProperties);
            if (counts == null) {
                if (state.limit == null) failure = addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT_PP,
                        property.getPropertyName(), null, query);
                break;
            }
            counts.forEach((other, count) -> best.put(other, new TripleCount(count, limit)));
            state.limit = limit;
        }
        best.forEach((name2, count) -> updatedRelatedProperties(state, Collections.singletonMap(name2, count.getTripleCount()),
                type, count.getTripleCountBase(), true));
        if (state.limit != null) state.quality = Math.max(state.quality, 3);
        return failure;
    }

    // Preserve pair completion while classifying failed individual existence checks as MESSAGE/STRUCT_PP.
    private void executeDetailsQueriesOption2(SchemaExtractorRequestDto request, Schema schema,
                                              Map<String, SchemaExtractorPropertyNodeInfo> properties, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        boolean symmetric = type != SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower;
        Map<String, PropertyRelationState> states = new LinkedHashMap<>();
        properties.forEach((name, property) -> states.put(name, new PropertyRelationState(propertyRelationLinks(property, type), propertyRelationQuality(property, type))));
        if (symmetric) {
            for (SchemaExtractorPropertyNodeInfo property : properties.values()) {
                PropertyRelationState state = states.get(property.getPropertyName());
                if (state.quality != 7 && propertyRelationKnownNonempty(property, type) && !state.links.containsKey(property.getPropertyName())) {
                    propertyRelationLink(state, property.getPropertyName(), type);
                    state.outsideResolved = true;
                }
                for (String name : new ArrayList<>(state.links.keySet())) {
                    PropertyRelationState reverse = states.get(name);
                    if (reverse != null && !reverse.links.containsKey(property.getPropertyName())) {
                        propertyRelationLink(reverse, property.getPropertyName(), type);
                        reverse.outsideResolved = true;
                    }
                }
            }
        }
        if (request.getPropertyPropertyLinkCheckBackupMode() == SchemaExtractorRequestDto.PropertyRelationsCheckMode.limits) {
            // Derive unresolved totals from completeness and sparse positives without traversing all property pairs.
            int incomplete = (int) states.values().stream().filter(state -> state.quality != 7).count();
            states.values().forEach(state -> {
                if (state.quality != 7) {
                    long known = state.links.keySet().stream().filter(name -> !symmetric || states.get(name).quality != 7).count();
                    state.unresolved = (symmetric ? incomplete : properties.size()) - (int) known;
                }
            });
        } else {
            List<SchemaExtractorPropertyNodeInfo> ordered = new ArrayList<>(properties.values());
            SchemaExtractorQueries check = propertyRelationCheck(type);
            for (int i = 0; i < ordered.size(); i++) {
                SchemaExtractorPropertyNodeInfo property = ordered.get(i);
                PropertyRelationState state = states.get(property.getPropertyName());
                if (!symmetric && state.quality == 7) continue;
                for (int j = symmetric ? i : 0; j < ordered.size(); j++) {
                    SchemaExtractorPropertyNodeInfo other = ordered.get(j);
                    PropertyRelationState reverse = states.get(other.getPropertyName());
                    if (state.links.containsKey(other.getPropertyName())) continue;
                    if (state.quality == 7 || symmetric && reverse.quality == 7) {
                        if (state.quality != 7) state.outsideResolved = true;
                        if (symmetric && reverse.quality != 7) reverse.outsideResolved = true;
                        continue;
                    }
                    SparqlQueryBuilder query = propertyRelationPairQuery(request, property, other, type, check);
                    QueryResponse response = sparqlEndpointProcessor.read(request, query);
                    if (response.hasErrors()) {
                        state.unresolved++;
                        if (symmetric && property != other) reverse.unresolved++;
                        addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT_PP,
                                property.getPropertyName(), other.getPropertyName(), query);
                        continue;
                    }
                    state.outsideResolved = true;
                    if (symmetric) reverse.outsideResolved = true;
                    if (response.getResults().isEmpty()) continue;
                    propertyRelationLink(state, other.getPropertyName(), type);
                    if (symmetric) propertyRelationLink(reverse, property.getPropertyName(), type);
                    if (request.getPropertyPropertyLinkCheckBackupMode() == SchemaExtractorRequestDto.PropertyRelationsCheckMode.limitsPlus) {
                        determinePropertyRelationPairCounts(schema, request, property, other, type,
                                state.links.get(other.getPropertyName()), symmetric && property != other ? reverse.links.get(property.getPropertyName()) : null);
                    }
                }
            }
        }
        properties.forEach((name, property) -> {
            PropertyRelationState state = states.get(name);
            int quality;
            if (state.unresolved == 0) quality = 7;
            else if (state.quality >= 3) {
                boolean outsideChecked = state.outsideResolved && request.getPropertyPropertyLinkCheckBackupMode() != SchemaExtractorRequestDto.PropertyRelationsCheckMode.limits;
                quality = outsideChecked ? 4 : 3;
            } else quality = classPropertyFailureQuality(state.unresolved, properties.size());
            quality = Math.max(state.quality, quality);
            setPropertyRelationQuality(property, type, quality);
            List<SchemaExtractorPropertyRelatedPropertyInfo> links = propertyRelationLinks(property, type);
            links.clear();
            links.addAll(state.links.values());
        });
    }

    // Preserve directional count outcomes while assigning COUNT diagnostics and the exact query-specific CSV wording.
    private void determinePropertyRelationPairCounts(Schema schema, SchemaExtractorRequestDto request,
                                                     SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyNodeInfo other,
                                                     SchemaExtractorPropertyRelatedPropertyInfo.LinkType type,
                                                     SchemaExtractorPropertyRelatedPropertyInfo forward, SchemaExtractorPropertyRelatedPropertyInfo reverse) {
        SchemaExtractorQueries name = propertyRelationCountQuery(request, type);
        SparqlQueryBuilder forwardQuery = propertyRelationPairQuery(request, property, other, type, name);
        Long forwardCount = readPropertyRelationPairCount(request, forwardQuery);
        SparqlQueryBuilder reverseQuery = reverse == null ? null : propertyRelationPairQuery(request, other, property, type, name);
        Long reverseCount = reverseQuery == null ? null : readPropertyRelationPairCount(request, reverseQuery);
        String relation = type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower ? "follower" : type + " property";
        boolean nonLiteral = forwardQuery.getQueryName().endsWith("_NONLITERAL");
        String estimateMessage = "Query for exact property " + relation + " triple count" + (nonLiteral ? " (with non-literal filter)" : "")
                + " not successful. Using an estimate instead (from the reverse direction).";
        String unavailableRelation = switch (type) {
            case outgoing -> "common subject";
            case incoming -> "common object";
            case follower -> "follower";
        };
        String unavailableMessage = "Property " + unavailableRelation + " triple count unavailable" + (nonLiteral ? " (via a query with non-literal filter)" : "");
        if (forwardCount != null) {
            forward.setTripleCount(forwardCount);
            forward.setTripleCountBase(null);
        } else addExtractionMessage(schema, reverseCount == null ? MESSAGE : NOTE, SchemaExtractorMessage.FunctionalityType.COUNT,
                property.getPropertyName(), other.getPropertyName(), forwardQuery, reverseCount == null ? unavailableMessage : estimateMessage);
        if (reverse != null) {
            if (reverseCount != null) {
                reverse.setTripleCount(reverseCount);
                reverse.setTripleCountBase(null);
            } else addExtractionMessage(schema, forwardCount == null ? MESSAGE : NOTE, SchemaExtractorMessage.FunctionalityType.COUNT,
                    other.getPropertyName(), property.getPropertyName(), reverseQuery, forwardCount == null ? unavailableMessage : estimateMessage);
        }
    }

    private Long readPropertyRelationPairCount(SchemaExtractorRequestDto request, SparqlQueryBuilder query) {
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        return !response.hasErrors() && response.getResults().size() == 1 ? readNonNegativeCount(response.getResults().get(0).getValue("instances")) : null;
    }

    private Map<String, Long> readPropertyRelationCounts(SchemaExtractorRequestDto request, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                         SparqlQueryBuilder query, boolean discoverNewProperties) {
        return readGroupedCounts(sparqlEndpointProcessor.read(request, query), "p2",
                row -> propertyRelationCandidate(request, properties, row.getValue("p2"), discoverNewProperties), true);
    }

    // Accept a valid out-of-list predicate only in automatic mode and defer registration until the results have been validated.
    private String propertyRelationCandidate(SchemaExtractorRequestDto request, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                               String name, boolean discoverNewProperties) {
        return isNotExcludedResource(name, request.getExcludedNamespaces())
                && (properties.containsKey(name) || discoverNewProperties && SchemaUtil.isValidURI(name)) ? name : null;
    }

    // Reuse the tightest valid reference-side population bound; distinct object counts include literals unless their absence is known.
    private Long propertyRelationSize(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        if (type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing) {
            return nonNegativeCount(property.getDistinctSubjectsCount()) != null ? property.getDistinctSubjectsCount() : nonNegativeCount(property.getTripleCount());
        }
        Long size = null;
        for (Long bound : Arrays.asList(property.getDistinctObjectsCount(), property.getObjectTripleCount(), property.getTripleCount())) {
            if (nonNegativeCount(bound) != null && (size == null || bound < size)) size = bound;
        }
        return size;
    }

    private Long nonNegativeCount(Long count) {
        return count != null && count >= 0 ? count : null;
    }

    private boolean propertyRelationKnownNonempty(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        Long count = type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing ? property.getTripleCount() : property.getObjectTripleCount();
        if (count != null && count > 0) return true;
        if (type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing) count = property.getDistinctSubjectsCount();
        else if (Long.valueOf(0).equals(property.getDataTripleCount())) count = propertyRelationSize(property, type);
        return count != null && count > 0;
    }

    // Use existing numeric/global blank-node evidence and probe only unknown small-property eligibility, leaving CP's helper unchanged.
    private boolean propertyRelationNoBlankNodes(Schema schema, SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                 SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, boolean probe) {
        boolean objects = type != SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing;
        Long count = objects ? property.getBlankNodeObjects() : property.getBlankNodeSubjects();
        Boolean global = objects ? schema.getHasBlankNodeObjects() : schema.getHasBlankNodeSubjects();
        if (Boolean.FALSE.equals(global) || Long.valueOf(0).equals(count)) return true;
        if (count != null && count > 0 || !probe) return false;
        SparqlQueryBuilder query = propertyRelationQuery(request, property, type, objects ? CHECK_PROPERTY_HAS_BLANK_OBJECTS : CHECK_PROPERTY_HAS_BLANK_SUBJECTS);
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        return !response.hasErrors() && response.getResults().isEmpty();
    }

    // Report only the terminal enumeration failure, retaining existing filtered retries and successful empty results.
    private List<String> readPropertyRelationInstances(Schema schema, SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                       SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, Long size, boolean noBlank, boolean sample) {
        boolean filtered = sample && !noBlank;
        int limit = sample && !(noBlank && smallClassPropertyEntity(size)) ? CLASS_PROPERTY_INSTANCE_LIMIT : 0;
        SparqlQueryBuilder query = propertyRelationInstanceQuery(request, property, type, filtered, limit);
        List<String> instances = readInstanceTerms(request, query, sample ? CLASS_PROPERTY_INSTANCE_LIMIT : 0);
        if (instances == null && filtered) {
            query = propertyRelationInstanceQuery(request, property, type, false, size != null && size < 2000 ? 0 : 2000);
            instances = readInstanceTerms(request, query, CLASS_PROPERTY_INSTANCE_LIMIT);
        }
        if (instances == null) addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.STRUCT_INSTANCE,
                property.getPropertyName(), null, query);
        return instances;
    }

    private SparqlQueryBuilder propertyRelationInstanceQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                              SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, boolean filtered, int limit) {
        SchemaExtractorQueries name = type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing
                ? FIND_PROPERTY_SUBJECTS_INSTANCES_FOR_RELATIONS : FIND_PROPERTY_OBJECTS_INSTANCES_FOR_RELATIONS;
        return propertyRelationQuery(request, property, type, name)
                .withContextParam("<objectFilter>", filtered ? "" : propertyRelationObjectFilter(property, type))
                .withContextParam("<instanceFilter>", filtered ? "FILTER(isIRI(?x))" : "")
                .withContextParam("<limitClause>", limit == 0 ? "" : "LIMIT " + limit);
    }

    // Pass PP relation text to the shared first-singleton diagnostic without overwriting any emitted message.
    private InstanceBatchResult<String> executePropertyRelationBatches(Schema schema, SchemaExtractorRequestDto request,
                                                                       Map<String, SchemaExtractorPropertyNodeInfo> properties, SchemaExtractorPropertyNodeInfo property,
                                                                        SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, List<String> instances,
                                                                        PropertyRelationState state, boolean discoverNewProperties) {
        SchemaExtractorQueries name = type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming
                ? selectQuery(request.getExactCountCalculations(), FIND_INSTANCE_INCOMING_PROPERTIES, FIND_INSTANCE_INCOMING_PROPERTIES_DISTINCT)
                : selectQuery(request.getExactCountCalculations(), FIND_INSTANCE_OUTGOING_PROPERTIES, FIND_INSTANCE_OUTGOING_PROPERTIES_DISTINCT);
        String message = switch (type) {
            case outgoing -> "Context: common subjects for properties";
            case incoming -> "Context: common objects for properties";
            case follower -> "Context: property followers";
        };
        InstanceBatchResult<String> result = new InstanceBatchResult<>();
        executeInstanceBatches(schema, request, instances, 0, instances.size(), "property", () -> {
            SparqlQueryBuilder query = propertyRelationQuery(request, property, type, name);
            updateValueClause(query, request.getIncludedProperties().isEmpty() ? null : properties.keySet(), "<propertyValuesClause>", "property");
            return query;
        }, row -> propertyRelationCandidate(request, properties, row.getValue("property"), discoverNewProperties),
                result, state, property.getPropertyName(), message);
        return result;
    }

    private SparqlQueryBuilder propertyRelationQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                      SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, SchemaExtractorQueries name) {
        return new SparqlQueryBuilder(request.getQueries().get(name.name()), name)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                .withContextParam("<objectFilter>", propertyRelationObjectFilter(property, type));
    }

    // Select the filtered common-object pair template only when neither property is confirmed to have zero literal objects.
    private SparqlQueryBuilder propertyRelationPairQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyNodeInfo property,
                                                          SchemaExtractorPropertyNodeInfo other, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type, SchemaExtractorQueries name) {
        if (type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming
                && !Long.valueOf(0).equals(property.getDataTripleCount()) && !Long.valueOf(0).equals(other.getDataTripleCount())) {
            name = switch (name) {
                case CHECK_PROPERTY_INCOMING_PROPERTY -> CHECK_PROPERTY_INCOMING_PROPERTY_NONLITERAL;
                case CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES -> CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES_NONLITERAL;
                case CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES_DISTINCT -> CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES_DISTINCT_NONLITERAL;
                default -> name;
            };
        }
        return propertyRelationQuery(request, property, type, name).withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, other.getPropertyName(), false);
    }

    private String propertyRelationObjectFilter(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        return type == SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing || Long.valueOf(0).equals(property.getDataTripleCount()) ? "" : "FILTER(!isLiteral(?x))";
    }

    private List<SchemaExtractorPropertyRelatedPropertyInfo> propertyRelationLinks(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        return switch (type) {
            case outgoing -> property.getOutgoingProperties();
            case incoming -> property.getIncomingProperties();
            case follower -> property.getFollowers();
        };
    }

    private void setPropertyRelationQuality(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type,
                                            Integer quality) {
        switch (type) {
            case outgoing -> property.setHasOutgoingPropertiesOK(quality);
            case incoming -> property.setHasIncomingPropertiesOK(quality);
            case follower -> property.setHasFollowersOK(quality);
        }
    }

    private int propertyRelationQuality(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        Integer quality = switch (type) {
            case outgoing -> property.getHasOutgoingPropertiesOK();
            case incoming -> property.getHasIncomingPropertiesOK();
            case follower -> property.getHasFollowersOK();
        };
        return quality == null ? 0 : quality;
    }

    private SchemaExtractorQueries propertyRelationCheck(SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        return switch (type) {
            case outgoing -> CHECK_PROPERTY_OUTGOING_PROPERTY;
            case incoming -> CHECK_PROPERTY_INCOMING_PROPERTY;
            case follower -> CHECK_PROPERTY_FOLLOWER;
        };
    }

    private SchemaExtractorQueries propertyRelationCountQuery(SchemaExtractorRequestDto request, SchemaExtractorPropertyRelatedPropertyInfo.LinkType type) {
        return switch (type) {
            case outgoing -> selectQuery(request.getExactCountCalculations(), CHECK_PROPERTY_OUTGOING_PROPERTY_TRIPLES, CHECK_PROPERTY_OUTGOING_PROPERTY_TRIPLES_DISTINCT);
            case incoming -> selectQuery(request.getExactCountCalculations(), CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES, CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES_DISTINCT);
            case follower -> selectQuery(request.getExactCountCalculations(), CHECK_PROPERTY_FOLLOWER_TRIPLES, CHECK_PROPERTY_FOLLOWER_TRIPLES_DISTINCT);
        };
    }

    // Report a failed cardinality query and estimate only when every failed attempt was measured and exceeded 45 seconds.
    private boolean reportCardinalityFailure(Schema schema, SchemaExtractorRequestDto request, String property, String contextClass,
                                              SparqlQueryBuilder query, QueryResponse response) {
        Long shortestFailedAttempt = response.getShortestFailedAttemptDurationNanos();
        boolean estimated = request.getEstimateCardinalities() && shortestFailedAttempt != null
                && shortestFailedAttempt > TimeUnit.SECONDS.toNanos(45);
        addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.CARDINALITY, property, contextClass, query,
                estimated ? "The cardinality has been set on the basis of the query failure" : null);
        return estimated;
    }

    // Remove only the latest global cardinality inference wording while preserving its historical failure NOTE.
    private void clearCardinalityInferenceText(Schema schema, String propertyName, SchemaExtractorQueries query) {
        List<SchemaExtractorMessage> messages = schema.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            SchemaExtractorMessage message = messages.get(i);
            if (message.getMessageLevel() == NOTE && message.getFunctionalityType() == SchemaExtractorMessage.FunctionalityType.CARDINALITY
                    && Objects.equals(propertyName, message.getEntity()) && message.getRelatedEntity() == null
                    && query.name().equals(message.getQueryName())) {
                if ("The cardinality has been set on the basis of the query failure".equals(message.getMessage())) message.setMessage(null);
                return;
            }
        }
    }

    // Apply the configurable failure policy to source minimum cardinality while retaining successful counterexample checks.
    protected void determinePropertySourceMinCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                         @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMinCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MIN_CARDINALITY.name()), FIND_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                boolean estimated = reportCardinalityFailure(schema, request, property.getPropertyName(), sourceClass.getClassName(), queryBuilder, queryResponse);
                sourceClass.setMinCardinality(estimated ? 1 : DEFAULT_MIN_CARDINALITY);
                sourceClass.setMinCardinalityAssertionSize(estimated ? -1 : null);
            } else {
                sourceClass.setMinCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MIN_CARDINALITY);
                sourceClass.setMinCardinalityAssertionSize(null);
            }
        });
    }

    // Handle property maximum-cardinality failures before empty results can be interpreted as a successful restriction.
    protected void determinePropertyMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo
            property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY.name()), FIND_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            boolean estimated = reportCardinalityFailure(schema, request, property.getPropertyName(), null, queryBuilder, queryResponse);
            property.setMaxCardinality(estimated ? 1 : DEFAULT_MAX_CARDINALITY);
            property.setMaxCardinalityAssertionSize(estimated ? -1 : null);
        } else {
            property.setMaxCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
            property.setMaxCardinalityAssertionSize(null);
        }
    }

    // Apply the configurable failure policy and include the source class in maximum-cardinality diagnostics.
    protected void determinePropertySourceMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                         int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMaxCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE.name()), FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                boolean estimated = reportCardinalityFailure(schema, request, property.getPropertyName(), sourceClass.getClassName(), queryBuilder, queryResponse);
                sourceClass.setMaxCardinality(estimated ? 1 : DEFAULT_MAX_CARDINALITY);
                sourceClass.setMaxCardinalityAssertionSize(estimated ? -1 : null);
            } else {
                sourceClass.setMaxCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
                sourceClass.setMaxCardinalityAssertionSize(null);
                // A successful source-class counterexample disproves an estimated property maximum.
                if (!queryResponse.getResults().isEmpty() && Integer.valueOf(1).equals(property.getMaxCardinality())
                        && Integer.valueOf(-1).equals(property.getMaxCardinalityAssertionSize())) {
                    property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
                    property.setMaxCardinalityAssertionSize(null);
                    clearCardinalityInferenceText(schema, property.getPropertyName(), FIND_PROPERTY_MAX_CARDINALITY);
                }
            }
        });
    }

    // Handle property inverse-maximum failures before interpreting successful counterexample results.
    protected void determinePropertyInverseMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                          int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyInverseMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            boolean estimated = reportCardinalityFailure(schema, request, property.getPropertyName(), null, queryBuilder, queryResponse);
            property.setMaxInverseCardinality(estimated ? 1 : DEFAULT_MAX_CARDINALITY);
            property.setMaxInverseCardinalityAssertionSize(estimated ? -1 : null);
        } else {
            property.setMaxInverseCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
            property.setMaxInverseCardinalityAssertionSize(null);
        }
    }

    // Preserve the chosen failure or estimate value and record the target class for inverse-maximum diagnostics.
    protected void determinePropertyTargetsInverseMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                                 int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsInverseMaxCardinality [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                boolean estimated = reportCardinalityFailure(schema, request, property.getPropertyName(), targetClass.getClassName(), queryBuilder, queryResponse);
                targetClass.setMaxInverseCardinality(estimated ? 1 : DEFAULT_MAX_CARDINALITY);
                targetClass.setMaxInverseCardinalityAssertionSize(estimated ? -1 : null);
            } else {
                targetClass.setMaxInverseCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
                targetClass.setMaxInverseCardinalityAssertionSize(null);
                // A successful target-class counterexample disproves an estimated property inverse maximum.
                if (!queryResponse.getResults().isEmpty() && Integer.valueOf(1).equals(property.getMaxInverseCardinality())
                        && Integer.valueOf(-1).equals(property.getMaxInverseCardinalityAssertionSize())) {
                    property.setMaxInverseCardinality(DEFAULT_MAX_CARDINALITY);
                    property.setMaxInverseCardinalityAssertionSize(null);
                    clearCardinalityInferenceText(schema, property.getPropertyName(), FIND_INVERSE_PROPERTY_MAX_CARDINALITY);
                }
            }
        });
    }

    // Preserve the chosen failure or estimate value and record the target class for inverse-minimum diagnostics.
    protected void determinePropertyTargetsInverseMinCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                                 int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyTargetsInverseMinCardinality [" + property.getPropertyName() + "]");
        property.getTargetClasses().forEach(targetClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MIN_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                boolean estimated = reportCardinalityFailure(schema, request, property.getPropertyName(), targetClass.getClassName(), queryBuilder, queryResponse);
                targetClass.setMinInverseCardinality(estimated ? 1 : DEFAULT_MIN_CARDINALITY);
                targetClass.setMinInverseCardinalityAssertionSize(estimated ? -1 : null);
            } else {
                targetClass.setMinInverseCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MIN_CARDINALITY);
                targetClass.setMinInverseCardinalityAssertionSize(null);
            }
        });
    }


    // Keep the existing pass gates while carrying only this property's eligible inference references into importance checks.
    protected void determineDomainsRangesAndImportanceIndexes(Schema schema, SchemaExtractorPropertyNodeInfo property,
                                                              List<SchemaClass> classes, Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                              SchemaExtractorRequestDto request, int totalCountOfProperties) {
        List<DomainRangeEstimate> estimates = new ArrayList<>();
        if (isTrue(request.getCalculateDomainsAndRanges())) {
            determineDomainsAndRanges(schema, property, classes, request, totalCountOfProperties, estimates);
        }
        if (isFalse(SchemaExtractorRequestDto.ImportantIndexesMode.no.equals(request.getCalculateImportanceIndexes()))) {
            determineImportanceIndexes(schema, property, classes, graphOfClasses, request, totalCountOfProperties, estimates);
        }
    }

    // Preserve the existing entry point while clearing stale markers and exporting estimates from temporary candidates.
    protected void determineDomainsAndRanges(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                             @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        determineDomainsAndRanges(schema, property, classes, request, totalCountOfProperties, new ArrayList<>());
    }

    private void determineDomainsAndRanges(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                             @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties, List<DomainRangeEstimate> estimates) {
        property.getSourceClasses().forEach(link -> link.setPrincipalAssertedSize(null));
        property.getTargetClasses().forEach(link -> link.setPrincipalAssertedSize(null));
        property.getSourceAndTargetPairs().forEach(pair -> {
            pair.setSourcePrincipalAssertedSize(null);
            pair.setTargetPrincipalAssertedSize(null);
        });


        log.info(request.getCorrelationId() + " - determineDomainForProperty [" + property.getPropertyName() + "]");
        SchemaExtractorPropertyLinkedClassInfo domainClass = determinePropertyDomainOrRange(schema, property,
                getTheFirstMainClass(buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes)), request, LinkedClassType.SOURCE, null, null,
                totalCountOfProperties, estimates);
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
                totalCountOfProperties, estimates);
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
                    totalCountOfProperties, estimates);
            if (pairRange != null) {
                sourceClass.setHasRangeInClassPair(true);
                pairsForSpecificSource.forEach(linkedPair -> {
                    if (pairRange.getClassName().equals(linkedPair.getTargetClass())) {
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
                    totalCountOfProperties, estimates);
            if (pairDomain != null) {
                targetClass.setHasDomainInClassPair(true);
                pairsForSpecificTarget.forEach(linkedPair -> {
                    if (pairDomain.getClassName().equals(linkedPair.getSourceClass())) {
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
            if (linkedClass.getClassName().equals(clazz.getClassName())) {
                linkedClass.setIsPrincipal(true);
                linkedClass.setImportanceIndex(1);
            } else {
                linkedClass.setIsPrincipal(false);
                linkedClass.setImportanceIndex(0);
            }
        });
    }


    // Preserve strict candidate-check callers while copying inference markers to their persistent class or pair entries.
    @Nullable
    protected SchemaExtractorPropertyLinkedClassInfo determinePropertyDomainOrRange(@Nonnull Schema schema,
                                                                                    @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                    @Nullable SchemaExtractorPropertyLinkedClassInfo currentClass,
                                                                                    @Nonnull SchemaExtractorRequestDto request,
                                                                                    @Nonnull LinkedClassType linkedClassType,
                                                                                    @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                    @Nullable SchemaExtractorClassNodeInfo targetClass,
                                                                                    int totalCountOfProperties) {
        return determinePropertyDomainOrRange(schema, property, currentClass, request, linkedClassType, sourceClass, targetClass,
                totalCountOfProperties, new ArrayList<>());
    }

    @Nullable
    private SchemaExtractorPropertyLinkedClassInfo determinePropertyDomainOrRange(@Nonnull Schema schema,
                                                                                    @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                    @Nullable SchemaExtractorPropertyLinkedClassInfo currentClass,
                                                                                    @Nonnull SchemaExtractorRequestDto request,
                                                                                    @Nonnull LinkedClassType linkedClassType,
                                                                                    @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                    @Nullable SchemaExtractorClassNodeInfo targetClass,
                                                                                    int totalCountOfProperties, List<DomainRangeEstimate> estimates) {
        if (currentClass == null) {
            return null;
        }
        int messageStart = schema.getMessages().size();
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
        boolean domain = linkedClassType == LinkedClassType.SOURCE || linkedClassType == LinkedClassType.PAIR_TARGET;
        SchemaExtractorClassNodeInfo context = linkedClassType == LinkedClassType.PAIR_SOURCE ? sourceClass
                : linkedClassType == LinkedClassType.PAIR_TARGET ? targetClass : null;
        SchemaExtractorMessage failureMessage = schema.getMessages().size() > messageStart ? schema.getMessages().get(messageStart) : null;
        boolean knownLiteralObjects = !domain && (context == null
                ? hasKnownLiteralObjects(property.getDataTripleCount(), property.getLiteralObjectsExistence())
                        || property.getSourceClasses().stream().anyMatch(this::hasKnownLiteralObjects)
                : hasKnownLiteralObjects(context));
        if (knownLiteralObjects && Integer.valueOf(-1).equals(currentClass.getTargetPrincipalAssertedSize())) {
            currentClass.setTargetPrincipalAssertedSize(null);
            if (failureMessage != null) failureMessage.setMessage(null);
        }
        retractDomainRangeEstimates(estimates, linkedClassType, sourceClass, targetClass, currentClass.getPrincipalWitness());
        currentClass.setPrincipalWitness(null);
        mapDomainRangeAssertion(property, currentClass, domain, context, failureMessage, estimates);
        if (isApplicable) {
            return currentClass;
        }
        return null;
    }

    // Interpret one strict domain/range query without accepting a failed check as verified.
    private boolean readDomainRangeCheck(Schema schema, String propertyName, SchemaExtractorPropertyLinkedClassInfo candidate,
                                         SchemaExtractorRequestDto request, String contextClass, SparqlQueryBuilder query, boolean domain) {
        candidate.setPrincipalWitness(null);
        if (domain) candidate.setSourcePrincipalAssertedSize(null);
        else candidate.setTargetPrincipalAssertedSize(null);
        QueryResponse response = sparqlEndpointProcessor.read(request, query);
        if (response.hasErrors()) {
            Long shortestFailedAttempt = response.getShortestFailedAttemptDurationNanos();
            boolean estimated = request.getEstimateDomainRange() && shortestFailedAttempt != null
                    && shortestFailedAttempt > TimeUnit.SECONDS.toNanos(45);
            if (domain) candidate.setSourcePrincipalAssertedSize(estimated ? -1 : null);
            else candidate.setTargetPrincipalAssertedSize(estimated ? -1 : null);
            addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.DOMAIN_RANGE,
                    propertyName, contextClass == null ? candidate.getClassName() : contextClass, query,
                    estimated ? "The " + (domain ? "domain" : "range") + " assertion may be inferred based on the query failure" : null);
            return false;
        }
        if (!response.getResults().isEmpty()) {
            candidate.setPrincipalWitness(new SchemaExtractorPropertyLinkedClassInfo.PrincipalWitness(
                    candidate.getClassificationProperty(), List.of(candidate.getClassName())));
            return false;
        }
        return true;
    }

    // Treat the existence-check sentinel -1 and positive sample evidence as known literal presence.
    private boolean hasKnownLiteralObjects(Long count, Boolean sampledExistence) {
        return count != null ? count != 0 : isTrue(sampledExistence);
    }

    // Include successful source-scoped existence checks whose positive result has no numeric count.
    private boolean hasKnownLiteralObjects(SchemaExtractorClassNodeInfo clazz) {
        return clazz.isLiteralObjectsKnown() || hasKnownLiteralObjects(clazz.getDataTripleCount(), clazz.getLiteralObjectsExistence());
    }

    // Copy the candidate marker to the class or pair that is actually serialized, retaining only eligible inference references.
    private void mapDomainRangeAssertion(SchemaExtractorPropertyNodeInfo property, SchemaExtractorPropertyLinkedClassInfo candidate,
                                         boolean domain, SchemaExtractorClassNodeInfo context, SchemaExtractorMessage message,
                                         List<DomainRangeEstimate> estimates) {
        Integer marker = domain ? candidate.getSourcePrincipalAssertedSize() : candidate.getTargetPrincipalAssertedSize();
        if (context == null) {
            List<SchemaExtractorClassNodeInfo> links = domain ? property.getSourceClasses() : property.getTargetClasses();
            for (SchemaExtractorClassNodeInfo link : links) {
                if (Objects.equals(link.getClassName(), candidate.getClassName())
                        && Objects.equals(link.getClassificationProperty(), candidate.getClassificationProperty())) {
                    link.setPrincipalAssertedSize(marker);
                    if (Integer.valueOf(-1).equals(marker)) {
                        estimates.add(new DomainRangeEstimate(domain, candidate.getClassName(), candidate.getClassificationProperty(),
                                null, null, message, () -> link.setPrincipalAssertedSize(null)));
                    }
                }
            }
        } else {
            for (SchemaExtractorSourceTargetInfo pair : property.getSourceAndTargetPairs()) {
                String candidateClass = domain ? pair.getSourceClass() : pair.getTargetClass();
                String candidatePredicate = domain ? pair.getClassificationPropertyForSource() : pair.getClassificationPropertyForTarget();
                String fixedClass = domain ? pair.getTargetClass() : pair.getSourceClass();
                String fixedPredicate = domain ? pair.getClassificationPropertyForTarget() : pair.getClassificationPropertyForSource();
                if (Objects.equals(candidateClass, candidate.getClassName())
                        && Objects.equals(candidatePredicate, candidate.getClassificationProperty())
                        && Objects.equals(fixedClass, context.getClassName())
                        && Objects.equals(fixedPredicate, context.getClassificationProperty())) {
                    if (domain) pair.setSourcePrincipalAssertedSize(marker);
                    else pair.setTargetPrincipalAssertedSize(marker);
                    if (Integer.valueOf(-1).equals(marker)) {
                        estimates.add(new DomainRangeEstimate(domain, candidateClass, candidatePredicate, fixedClass, fixedPredicate, message, () -> {
                            if (domain) pair.setSourcePrincipalAssertedSize(null);
                            else pair.setTargetPrincipalAssertedSize(null);
                        }));
                    }
                }
            }
        }
    }

    // Revoke only inferences contradicted by a clean witness with the same class, predicate and a compatible scope.
    private void retractDomainRangeEstimates(List<DomainRangeEstimate> estimates, LinkedClassType type,
                                             SchemaExtractorClassNodeInfo sourceClass, SchemaExtractorClassNodeInfo targetClass,
                                             SchemaExtractorPropertyLinkedClassInfo.PrincipalWitness witness) {
        if (witness == null || estimates.isEmpty()) return;
        boolean domain = type == LinkedClassType.SOURCE || type == LinkedClassType.PAIR_TARGET;
        SchemaExtractorClassNodeInfo context = type == LinkedClassType.PAIR_SOURCE ? sourceClass
                : type == LinkedClassType.PAIR_TARGET ? targetClass : null;
        estimates.removeIf(estimate -> {
            if (estimate.domain() != domain || !witness.comparedClasses().contains(estimate.className())
                    || !Objects.equals(witness.classificationPropertyOther(), estimate.classificationProperty())) return false;
            if (estimate.contextClass() != null && (context == null
                    || !Objects.equals(context.getClassName(), estimate.contextClass())
                    || !Objects.equals(context.getClassificationProperty(), estimate.contextClassificationProperty()))) return false;
            estimate.clearMarker().run();
            if (estimate.message() != null) estimate.message().setMessage(null);
            return true;
        });
    }


    // Apply the configurable domain/range failure policy while preserving successful counterexample semantics.
    protected boolean checkDomainClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain,
                                       @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PROPERTY.name()), CHECK_DOMAIN_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        return readDomainRangeCheck(schema, propertyName, potentialDomain, request, null, queryBuilder, true);
    }

    // Apply the configurable domain/range failure policy while preserving successful counterexample semantics.
    protected boolean checkRangeClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange,
                                      @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PROPERTY.name()), CHECK_RANGE_FOR_PROPERTY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        return readDomainRangeCheck(schema, propertyName, potentialRange, request, null, queryBuilder, false);
    }

    // Apply the configurable domain/range failure policy while preserving successful counterexample semantics.
    protected boolean checkRangeForSource(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass,
                                          @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange,
                                          @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PAIR_SOURCE.name()), CHECK_RANGE_FOR_PAIR_SOURCE)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        return readDomainRangeCheck(schema, propertyName, potentialRange, request, sourceClass.getClassName(), queryBuilder, false);
    }

    // Apply the configurable domain/range failure policy while preserving successful counterexample semantics.
    protected boolean checkDomainForTarget(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass,
                                           @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain,
                                           @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PAIR_TARGET.name()), CHECK_DOMAIN_FOR_PAIR_TARGET)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        return readDomainRangeCheck(schema, propertyName, potentialDomain, request, targetClass.getClassName(), queryBuilder, true);
    }

    // Preserve the importance entry point and keep inference retraction local to the combined property workflow.
    protected void determineImportanceIndexes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                              @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                              @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        determineImportanceIndexes(schema, property, classes, graphOfClasses, request, totalCountOfProperties, new ArrayList<>());
    }

    private void determineImportanceIndexes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                              @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                              @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties, List<DomainRangeEstimate> estimates) {

        if (isNotTrue(property.getHasDomain()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForSourceClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalSourceClasses = determinePrincipalClasses(schema, property,
                    buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes), classes, graphOfClasses, request, LinkedClassType.SOURCE, null, null,
                    totalCountOfProperties, estimates);
            mapPrincipalClasses(property.getSourceClasses(), principalSourceClasses);
        }

        if (isNotTrue(property.getHasRange()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForTargetClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalTargetClasses = determinePrincipalClasses(schema, property,
                    buildAndSortPropertyLinkedClasses(property.getTargetClasses(), classes), classes, graphOfClasses, request, LinkedClassType.TARGET, null, null,
                    totalCountOfProperties, estimates);
            mapPrincipalClasses(property.getTargetClasses(), principalTargetClasses);
        }

        log.info(request.getCorrelationId() + " - determineImportanceIndexesForClassPairSource [" + property.getPropertyName() + "]");
        // set pair target important indexes
        property.getSourceClasses().forEach(sourceClass -> {
            if (isNotTrue(sourceClass.getHasRangeInClassPair()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
                List<SchemaExtractorSourceTargetInfo> pairsForSpecificSource = findPairsWithSpecificSource(property.getSourceAndTargetPairs(), sourceClass.getClassName());
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairTargets = determinePrincipalClasses(schema, property,
                        buildAndSortPropertyPairTargets(pairsForSpecificSource, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_SOURCE, sourceClass, null,
                        totalCountOfProperties, estimates);
                pairsForSpecificSource.forEach(linkedPair -> {
                    SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairTargets.stream()
                            .filter(pairTarget -> pairTarget.getClassName().equals(linkedPair.getTargetClass())).findFirst().orElse(null);
                    linkedPair.setTargetImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
                });
            }

        });
        log.info(request.getCorrelationId() + " - determineImportanceIndexesForClassPairTarget [" + property.getPropertyName() + "]");
        // set pair source important indexes
        property.getTargetClasses().forEach(targetClass -> {
            if (isNotTrue(targetClass.getHasDomainInClassPair()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
                List<SchemaExtractorSourceTargetInfo> pairsForSpecificTarget = findPairsWithSpecificTarget(property.getSourceAndTargetPairs(), targetClass.getClassName());
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairSources = determinePrincipalClasses(schema, property,
                        buildAndSortPropertyPairSources(pairsForSpecificTarget, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_TARGET, null, targetClass,
                        totalCountOfProperties, estimates);
                pairsForSpecificTarget.forEach(linkedPair -> {
                    SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairSources.stream()
                            .filter(pairTarget -> pairTarget.getClassName().equals(linkedPair.getSourceClass())).findFirst().orElse(null);
                    linkedPair.setSourceImportanceIndex((pairWithIndex != null) ? pairWithIndex.getImportanceIndex() : 0);
                });
            }
        });
    }

    // Preserve principal selection and use only explicit successful witnesses to revoke an earlier strict assertion estimate.
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
        return determinePrincipalClasses(schema, property, propertyLinkedClassesSorted, classes, graphOfClasses, request,
                linkedClassType, sourceClass, targetClass, totalCountOfProperties, new ArrayList<>());
    }

    @Nonnull
    private List<SchemaExtractorPropertyLinkedClassInfo> determinePrincipalClasses(@Nonnull Schema schema,
                                                                                     @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                                                     @Nonnull List<SchemaExtractorPropertyLinkedClassInfo> propertyLinkedClassesSorted,
                                                                                     @Nonnull List<SchemaClass> classes,
                                                                                     @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                                                     @Nonnull SchemaExtractorRequestDto request,
                                                                                     @Nonnull LinkedClassType linkedClassType,
                                                                                     @Nullable SchemaExtractorClassNodeInfo sourceClass,
                                                                                     @Nullable SchemaExtractorClassNodeInfo targetClass,
                                                                                     int totalCountOfProperties, List<DomainRangeEstimate> estimates) {

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

            // check whether there is partial intersection and if yes - check case by case
            if (graphOfClasses.get(currentClass.getClassName()) != null) {
                List<SchemaExtractorIntersectionClassDto> currentClassNeighbors = graphOfClasses.get(currentClass.getClassName()).getNeighbors();
                List<String> includedClassesToCheckWith = sortNeighborsByTripleCountDsc(currentClassNeighbors, graphOfClasses).stream()
                        .map(SchemaExtractorIntersectionClassDto::getClassName).filter(importantClasses::contains).collect(Collectors.toList());

                // if there is no intersection, set importance index > 0
                if (includedClassesToCheckWith.isEmpty()) {
                    currentClass.setImportanceIndex(index++);
                    importantClasses.add(currentClass.getClassName());
                    continue;
                }

                // Class coverage accepts intersecting classes without checking their property ends.
                if (SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
                    currentClass.setImportanceIndex(index++);
                    importantClasses.add(currentClass.getClassName());
                    continue;
                }

                boolean needToInclude;
                if (SchemaExtractorRequestDto.ImportantIndexesMode.basic.equals(request.getCalculateImportanceIndexes())) {
                    // A single already important class can cover all of this candidate's property ends.
                    needToInclude = true;
                    for (String includedClass : includedClassesToCheckWith) {
                        if (!checkNewPrincipalClass(schema, property, currentClass, List.of(includedClass), classes, request,
                                linkedClassType, sourceClass, targetClass, totalCountOfProperties, estimates)) {
                            needToInclude = false;
                            break;
                        }
                    }
                } else {
                    needToInclude = checkNewPrincipalClass(schema, property, currentClass, includedClassesToCheckWith, classes, request,
                            linkedClassType, sourceClass, targetClass, totalCountOfProperties, estimates);
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

    // Process each comparison's witness before another principal check can replace it.
    private boolean checkNewPrincipalClass(Schema schema, SchemaExtractorPropertyNodeInfo property,
                                           SchemaExtractorPropertyLinkedClassInfo currentClass, List<String> includedClasses,
                                           List<SchemaClass> classes, SchemaExtractorRequestDto request, LinkedClassType linkedClassType,
                                           SchemaExtractorClassNodeInfo sourceClass, SchemaExtractorClassNodeInfo targetClass,
                                           int totalCountOfProperties, List<DomainRangeEstimate> estimates) {
        String propertyName = property.getPropertyName();
        boolean needToInclude = switch (linkedClassType) {
            case SOURCE -> checkNewPrincipalSourceClass(schema, propertyName, currentClass, includedClasses, classes, request, totalCountOfProperties);
            case TARGET -> checkNewPrincipalTargetClass(schema, propertyName, currentClass, includedClasses, classes, request, totalCountOfProperties);
            case PAIR_SOURCE -> sourceClass != null && StringUtils.isNotEmpty(sourceClass.getClassName())
                    && checkNewPrincipalTargetClassForSource(schema, propertyName, sourceClass, currentClass, includedClasses, classes, request, totalCountOfProperties);
            case PAIR_TARGET -> targetClass != null && StringUtils.isNotEmpty(targetClass.getClassName())
                    && checkNewPrincipalSourceClassForTarget(schema, propertyName, targetClass, currentClass, includedClasses, classes, request, totalCountOfProperties);
        };
        retractDomainRangeEstimates(estimates, linkedClassType, sourceClass, targetClass, currentClass.getPrincipalWitness());
        currentClass.setPrincipalWitness(null);
        return needToInclude;
    }

    // Retain clean principal-source witnesses so proven exclusions can cancel contradicted domain estimates.
    protected boolean checkNewPrincipalSourceClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass,
                                                   @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                   int totalCountOfProperties) {
        newSourceClass.setPrincipalWitness(null);
        boolean isPrincipalSource = false;

        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            List<String> comparedClasses = classesForCustomFilter;
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                        propertyName, null, queryBuilder);
            }
            // Retry only failed multi-class comparisons with the largest class to avoid repeating the same singleton query.
            if (queryResponse.hasErrors() && classesForCustomFilter.size() > 1) {
                comparedClasses = Collections.unmodifiableList(existingClasses.subList(0, 1));
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                            propertyName, null, queryBuilder);
                }
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                newSourceClass.setPrincipalWitness(new SchemaExtractorPropertyLinkedClassInfo.PrincipalWitness(
                        classificationProperty, List.copyOf(comparedClasses)));
            }
            isPrincipalSource = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalSource)) break;
        }
        return isPrincipalSource;
    }

    // Retain clean principal-target witnesses so proven exclusions can cancel contradicted range estimates.
    protected boolean checkNewPrincipalTargetClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass,
                                                   @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                   int totalCountOfProperties) {
        newTargetClass.setPrincipalWitness(null);
        boolean isPrincipalTarget = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            List<String> comparedClasses = classesForCustomFilter;
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                        propertyName, null, queryBuilder);
            }
            // Retry only failed multi-class comparisons with the largest class to avoid repeating the same singleton query.
            if (queryResponse.hasErrors() && classesForCustomFilter.size() > 1) {
                comparedClasses = Collections.unmodifiableList(existingClasses.subList(0, 1));
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                            propertyName, null, queryBuilder);
                }
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                newTargetClass.setPrincipalWitness(new SchemaExtractorPropertyLinkedClassInfo.PrincipalWitness(
                        classificationProperty, List.copyOf(comparedClasses)));
            }
            isPrincipalTarget = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    // Retain clean source-context target witnesses so proven exclusions can cancel contradicted range estimates.
    protected boolean checkNewPrincipalTargetClassForSource(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass,
                                                            @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass,
                                                            @Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                            int totalCountOfProperties) {
        newTargetClass.setPrincipalWitness(null);
        boolean isPrincipalTarget = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            List<String> comparedClasses = classesForCustomFilter;
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name()), CHECK_PRINCIPAL_TARGET_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                        propertyName, null, queryBuilder);
            }
            // Retry only failed multi-class comparisons with the largest class to avoid repeating the same singleton query.
            if (queryResponse.hasErrors() && classesForCustomFilter.size() > 1) {
                comparedClasses = Collections.unmodifiableList(existingClasses.subList(0, 1));
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name()), CHECK_PRINCIPAL_TARGET_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                            propertyName, null, queryBuilder);
                }
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                newTargetClass.setPrincipalWitness(new SchemaExtractorPropertyLinkedClassInfo.PrincipalWitness(
                        classificationProperty, List.copyOf(comparedClasses)));
            }
            isPrincipalTarget = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    // Retain clean target-context source witnesses so proven exclusions can cancel contradicted domain estimates.
    protected boolean checkNewPrincipalSourceClassForTarget(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass,
                                                            @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass,
                                                            @Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                            int totalCountOfProperties) {
        newSourceClass.setPrincipalWitness(null);
        boolean isPrincipalSource = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            List<String> comparedClasses = classesForCustomFilter;
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name()), CHECK_PRINCIPAL_SOURCE_FOR_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                        propertyName, null, queryBuilder);
            }
            // Retry only failed multi-class comparisons with the largest class to avoid repeating the same singleton query.
            if (queryResponse.hasErrors() && classesForCustomFilter.size() > 1) {
                comparedClasses = Collections.unmodifiableList(existingClasses.subList(0, 1));
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name()), CHECK_PRINCIPAL_SOURCE_FOR_TARGET)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(comparedClasses, classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.IMPORTANCE_INDEX,
                            propertyName, null, queryBuilder);
                }
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                newSourceClass.setPrincipalWitness(new SchemaExtractorPropertyLinkedClassInfo.PrincipalWitness(
                        classificationProperty, List.copyOf(comparedClasses)));
            }
            isPrincipalSource = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalSource)) break;
        }
        return isPrincipalSource;
    }

    // Separate only appended class predicates so missing class entries cannot create a leading disjunction.
    protected String buildCustomFilterToCheckPrincipalClass(@Nonnull List<String> existingClasses, @Nonnull List<SchemaClass> classes) {
        StringBuilder customFilter = new StringBuilder(StringUtils.EMPTY);
        for (int i = 0; i < existingClasses.size(); i++) {
            SchemaClass clazz = findClass(classes, existingClasses.get(i));
            if (clazz == null) {
                continue;
            }
            if (customFilter.length() > 0) {
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

    // Preserve existing IRI callers while sharing VALUES construction with already formatted literal class terms.
    protected String buildValuesClause(@Nonnull Set<String> values, @Nonnull String param) {
        return buildValuesClause(values, param, false);
    }

    // Keep each clause independent and retain empty restricted sets instead of accidentally querying the unrestricted graph.
    private String buildValuesClause(Collection<String> values, String variable, boolean formatted) {
        StringBuilder clause = new StringBuilder("VALUES ?").append(variable).append(" { ");
        values.forEach(value -> clause.append(formatted ? value : SchemaUtil.addAngleBracketsToString(value)).append(" "));
        return clause.append("} ").toString();
    }

    protected void updateClassesWithIncomingTripleCount(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        Map<String, Long> targetClassTripleCounts = new HashMap<>();
        properties.values().forEach(property -> {
            property.getTargetClasses().forEach(targetClass -> {
                Long targetClassTripleCount = 0L;
                if (targetClassTripleCounts.containsKey(targetClass.getClassName())) {
                    targetClassTripleCount = targetClassTripleCounts.get(targetClass.getClassName());
                }
                targetClassTripleCounts.put(targetClass.getClassName(), targetClassTripleCount + (targetClass.getTripleCount() != null ? targetClass.getTripleCount() : 0L));
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
            property.setMaxCardinalityAssertionSize(propertyData.getMaxCardinalityAssertionSize());
            property.setMaxInverseCardinality(propertyData.getMaxInverseCardinality());
            property.setMaxInverseCardinalityAssertionSize(propertyData.getMaxInverseCardinalityAssertionSize());
            property.setTripleCount(propertyData.getTripleCount());
            property.setDistinctTriples(propertyData.getDistinctTriples());
            if (propertyData.getDataTripleCount() != null && propertyData.getDataTripleCount() != -1) {
                property.setDataTripleCount(propertyData.getDataTripleCount());
            }
            if (propertyData.getObjectTripleCount() != null && propertyData.getObjectTripleCount() != -1) {
                property.setObjectTripleCount(propertyData.getObjectTripleCount());
            }
            property.setObjectTripleRatio(propertyData.getObjectTripleRatio());
            property.setBlankNodeObjects(propertyData.getBlankNodeObjects());
            property.setBlankNodeSubjects(propertyData.getBlankNodeSubjects());
            property.setDistinctSubjectsCount(propertyData.getDistinctSubjectsCount());
            property.setDistinctObjectsCount(propertyData.getDistinctObjectsCount());
            property.setClosedSource(propertyData.getIsClosedSource());
            property.setClosedSourceAssertedSize(propertyData.getClosedSourceAssertedSize());
            property.setClosedTarget(propertyData.getIsClosedTarget());
            property.setClosedTargetAssertedSize(propertyData.getClosedTargetAssertedSize());
            property.setSourceClassesOK(propertyData.getSourceClassesOK());
            property.setTargetClassesOK(propertyData.getTargetClassesOK());
            property.setHasFollowersOK(propertyData.getHasFollowersOK());
            property.setHasOutgoingPropertiesOK(propertyData.getHasOutgoingPropertiesOK());
            property.setHasIncomingPropertiesOK(propertyData.getHasIncomingPropertiesOK());
            // Publish reference-side PP LIMIT scopes independently of each pair's count base.
            property.setOutgoingLimit(propertyData.getOutgoingLimit());
            property.setIncomingLimit(propertyData.getIncomingLimit());
            property.setFollowersLimit(propertyData.getFollowersLimit());
            property.getDataTypes().addAll(convertInternalDataTypesToApiDto(propertyData.getDataTypes()));
            property.getSourceClasses().addAll(convertInternalDtoToApiDto(propertyData.getSourceClasses()));
            property.getTargetClasses().addAll(convertInternalDtoToApiDto(propertyData.getTargetClasses()));
            propertyData.getSourceAndTargetPairs().forEach(pair -> {
                if (isFalse(isDuplicatePair(property.getClassPairs(), pair.getSourceClass(), pair.getTargetClass()))) {
                    property.getClassPairs().add(new ClassPair(pair.getSourceClass(), pair.getTargetClass(),
                            pair.getTripleCount(), pair.getIsPrincipalSource(), pair.getSourcePrincipalAssertedSize(), pair.getSourceImportanceIndex(),
                            pair.getIsPrincipalTarget(), pair.getTargetPrincipalAssertedSize(), pair.getTargetImportanceIndex(), pair.getIsAssumed()));
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
                        .map(internalDto -> {
                            SchemaPropertyLinkedClassDetails details = new SchemaPropertyLinkedClassDetails(
                                internalDto.getClassName(),
                                internalDto.getTripleCount(), internalDto.getTripleCountBase(), internalDto.getDataTripleCount(), internalDto.getObjectTripleCount(),
                                internalDto.getDistinctSubjectsCount(), internalDto.getDistinctObjectsCount(),
                                internalDto.getIsClosedSource(), internalDto.getClosedSourceAssertedSize(), internalDto.getIsClosedTarget(), internalDto.getClosedTargetAssertedSize(),
                                internalDto.getIsPrincipal(), internalDto.getPrincipalAssertedSize(),
                                internalDto.getMinCardinality(), internalDto.getMinCardinalityAssertionSize(), internalDto.getMaxCardinality(), internalDto.getMaxCardinalityAssertionSize(),
                                internalDto.getMinInverseCardinality(), internalDto.getMinInverseCardinalityAssertionSize(), internalDto.getMaxInverseCardinality(), internalDto.getMaxInverseCardinalityAssertionSize(),
                                internalDto.getImportanceIndex(), internalDto.getDataTypes());
                            details.setObjectTripleRatio(internalDto.getObjectTripleRatio());
                            return details;
                        })
                        .collect(Collectors.toList()));
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

    protected void buildInstanceNamespaces(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        if (SchemaExtractorRequestDto.InstanceNamespacesMode.no.equals(request.getCalculateInstanceNamespaces())) {
            return;
        }
        if (SchemaExtractorRequestDto.InstanceNamespacesMode.overview.equals(request.getCalculateInstanceNamespaces())) {
            buildInstanceNamespacesWithOverviewMode(request, schema);
            return;
        }
        if (SchemaExtractorRequestDto.InstanceNamespacesMode.detailed.equals(request.getCalculateInstanceNamespaces())) {
            buildInstanceNamespacesWithDetailedMode(request, schema);
        }
    }

    protected void buildInstanceNamespacesWithOverviewMode(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {

        // find instance namespaces for classes with limit
        for (SchemaClass clazz : schema.getClasses()) {
            List<InstanceNamespace> instanceNamespaces = new ArrayList<>();
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES_FOR_CLASS_WITH_LIMIT.name()), FIND_INSTANCE_NAMESPACES_FOR_CLASS_WITH_LIMIT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, clazz.getFullName(), clazz.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, request.getSampleLimitForInstanceNamespacesCalculation().toString());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.INSTANCE_NAMESPACES,
                        clazz.getFullName(), null, queryBuilder);
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                queryResponse.getResults().forEach(queryResult -> {
                    String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_NAMESPACE);
                    if (StringUtils.isNotEmpty(instanceNamespace)) {
                        Long count = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT));
                        instanceNamespaces.add(new InstanceNamespace(instanceNamespace, count, request.getSampleLimitForInstanceNamespacesCalculation()));
                    }
                });
            }
            clazz.getInstanceNamespaces().addAll(instanceNamespaces);
        }

        // find instance namespaces for property subjects and objects
        for (SchemaProperty property : schema.getProperties()) {

            List<InstanceNamespace> subjectInstanceNamespaces = new ArrayList<>();
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_SUBJECTS_WITH_LIMIT.name()), FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_SUBJECTS_WITH_LIMIT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getFullName(), false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, request.getSampleLimitForInstanceNamespacesCalculation().toString());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.INSTANCE_NAMESPACES,
                        property.getFullName(), null, queryBuilder);
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                queryResponse.getResults().forEach(queryResult -> {
                    String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_NAMESPACE);
                    if (StringUtils.isNotEmpty(instanceNamespace)) {
                        Long count = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT));
                        subjectInstanceNamespaces.add(new InstanceNamespace(instanceNamespace, count, request.getSampleLimitForInstanceNamespacesCalculation()));
                    }
                });
            }
            property.getSubjectInstanceNamespaces().addAll(subjectInstanceNamespaces);

            List<InstanceNamespace> objectInstanceNamespaces = new ArrayList<>();
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_OBJECTS_WITH_LIMIT.name()), FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_OBJECTS_WITH_LIMIT)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getFullName(), false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, request.getSampleLimitForInstanceNamespacesCalculation().toString());
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.INSTANCE_NAMESPACES,
                        property.getFullName(), null, queryBuilder);
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                queryResponse.getResults().forEach(queryResult -> {
                    String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_NAMESPACE);
                    if (StringUtils.isNotEmpty(instanceNamespace)) {
                        Long count = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT));
                        objectInstanceNamespaces.add(new InstanceNamespace(instanceNamespace, count, request.getSampleLimitForInstanceNamespacesCalculation()));
                    }
                });
            }
            property.getObjectInstanceNamespaces().addAll(objectInstanceNamespaces);
        }
    }

    protected void buildInstanceNamespacesWithDetailedMode(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {

        // find instance namespaces for classes
        for (SchemaClass clazz : schema.getClasses()) {
            List<InstanceNamespace> instanceNamespaces = new ArrayList<>();
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES_FOR_CLASS.name()), FIND_INSTANCE_NAMESPACES_FOR_CLASS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, clazz.getFullName(), clazz.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.INSTANCE_NAMESPACES,
                        clazz.getFullName(), null, queryBuilder);
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                queryResponse.getResults().forEach(queryResult -> {
                    String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_NAMESPACE);
                    if (StringUtils.isNotEmpty(instanceNamespace)) {
                        Long count = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT));
                        instanceNamespaces.add(new InstanceNamespace(instanceNamespace, count));
                    }
                });
            }
            clazz.getInstanceNamespaces().addAll(instanceNamespaces);
        }

        // find instance namespaces for property subjects and objects
        for (SchemaProperty property : schema.getProperties()) {

            List<InstanceNamespace> subjectInstanceNamespaces = new ArrayList<>();
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_SUBJECTS.name()), FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_SUBJECTS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getFullName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.INSTANCE_NAMESPACES,
                        property.getFullName(), null, queryBuilder);
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                queryResponse.getResults().forEach(queryResult -> {
                    String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_NAMESPACE);
                    if (StringUtils.isNotEmpty(instanceNamespace)) {
                        Long count = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT));
                        subjectInstanceNamespaces.add(new InstanceNamespace(instanceNamespace, count));
                    }
                });
            }
            property.getSubjectInstanceNamespaces().addAll(subjectInstanceNamespaces);

            List<InstanceNamespace> objectInstanceNamespaces = new ArrayList<>();
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_OBJECTS.name()), FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_OBJECTS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getFullName(), false);
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.INSTANCE_NAMESPACES,
                        property.getFullName(), null, queryBuilder);
            }
            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                queryResponse.getResults().forEach(queryResult -> {
                    String instanceNamespace = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_NAMESPACE);
                    if (StringUtils.isNotEmpty(instanceNamespace)) {
                        Long count = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT));
                        objectInstanceNamespaces.add(new InstanceNamespace(instanceNamespace, count));
                    }
                });
            }
            property.getObjectInstanceNamespaces().addAll(objectInstanceNamespaces);
        }
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
        Map<String, Long> namespacesWithCounts = new HashMap<>();

        // get all schema class and property namespaces
        for (SchemaClass item : schema.getClasses()) {
            if (StringUtils.isNotEmpty(item.getNamespace())) {
                namespacesWithCounts.merge(item.getNamespace(), 1L, Long::sum);
            }
            item.getInstanceNamespaces().forEach(
                    namespace -> namespacesWithCounts.merge(namespace.getNamespace(), namespace.getCount(), Long::sum));
        }

        for (SchemaProperty item : schema.getProperties()) {
            if (StringUtils.isNotEmpty(item.getNamespace())) {
                namespacesWithCounts.merge(item.getNamespace(), 1L, Long::sum);
            }
            item.getSubjectInstanceNamespaces().forEach(
                    namespace -> namespacesWithCounts.merge(namespace.getNamespace(), namespace.getCount(), Long::sum));
            item.getObjectInstanceNamespaces().forEach(
                    namespace -> namespacesWithCounts.merge(namespace.getNamespace(), namespace.getCount(), Long::sum));
        }

        // order all class and property namespaces by usage count
        Set<String> orderedNamespaces = new LinkedHashSet<>();
        namespacesWithCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> orderedNamespaces.add(entry.getKey()));

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

    // Report failed aggregate and individual intersection queries without changing hierarchy fallback decisions.
    protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull Schema schema, @Nonnull List<SchemaClass> classes,
                                                                  @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequestDto request,
                                                                  boolean allClassesHaveDistinctCounts) {
        QueryResponse queryResponse;
        // order classes by instance count ascending
        List<SchemaClass> sortedClasses = sortClassesByTripleCountAsc(classes);
        Map<String, List<String>> processedClasses = new HashMap<>();

        SchemaExtractorQueries intersectionQuery = FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS;
        SchemaExtractorQueries checkQuery = CHECK_CLASS_INTERSECTION;
        if (SchemaExtractorRequestDto.DistinctQueryMode.yes.equals(request.getExactCountCalculations()) || allClassesHaveDistinctCounts) {
            intersectionQuery = FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS_DISTINCT;
            checkQuery = CHECK_CLASS_INTERSECTION_DISTINCT;
        }

        for (SchemaClass classA : sortedClasses) {
            boolean hasErrors = false;
            for (String classificationProperty : request.getPrincipalClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(intersectionQuery.name()), intersectionQuery)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, classA.getFullName(), classA.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, classA.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classificationProperty);
                if (!request.getIncludedClasses().isEmpty()) {
                    queryBuilder.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES,
                            buildValuesClause(classes.stream().map(SchemaClass::getFullName).collect(Collectors.toSet()), SPARQL_QUERY_BINDING_NAME_CLASS_B));
                } else {
                    queryBuilder.withContextParam(SPARQL_QUERY_BINDING_NAME_VALUES, StringUtils.EMPTY);
                }
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE,
                            intersectionQuery == FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS_DISTINCT
                                    ? SchemaExtractorMessage.FunctionalityType.DISTINCT : SchemaExtractorMessage.FunctionalityType.STRUCT,
                            classA.getFullName(), null, queryBuilder);
                }
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty()) {
                    updateGraphOfClassesWithNeighbors(schema, classA.getFullName(), queryResponse.getResults(), graphOfClasses, request);
                    queryResponse.getResults().clear();
                    hasErrors = false;
                    break;
                }
                if (isFalse(hasErrors)) {
                    hasErrors = queryResponse.hasErrors();
                }
            }
            if (isFalse(hasErrors)) {
                updateProcessedClasses(processedClasses, classA, null);
            }

            if (hasErrors) {
                for (SchemaClass classB : sortedClasses) {
                    // skip the same class
                    if (classA.getFullName().equals(classB.getFullName())) continue;
                    // skip the class which was already successfully processed from the opposite direction
                    if (processedClasses.containsKey(classB.getFullName())) {
                        if (processedClasses.get(classB.getFullName()) == null || processedClasses.get(classB.getFullName()).contains(classA.getFullName())) {
                            continue;
                        }
                    }
                    if (isNotExcludedResource(classB.getFullName(), request.getExcludedNamespaces())
                            && (isNotFalse(classA.getPropertiesInSchema()) || isNotFalse(classB.getPropertiesInSchema()))) {
                        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_CLASS_INTERSECTION_PLAIN.name()), CHECK_CLASS_INTERSECTION_PLAIN)
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, classA.getFullName(), classA.getIsLiteral())
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL, classB.getFullName(), classB.getIsLiteral())
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, classA.getClassificationProperty())
                                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classB.getClassificationProperty());
                        QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                        if (!checkQueryResponse.hasErrors()) {
                            updateProcessedClasses(processedClasses, classA, classB);
                        } else {
                            addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT_SUB,
                                    classA.getFullName(), null, queryBuilder,
                                    "Check class intersection failed. Assume no intersection.");
                        }
                        if (!checkQueryResponse.hasErrors() && !checkQueryResponse.getResults().isEmpty()) {
                            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(checkQuery.name()), checkQuery)
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, classA.getFullName(), classA.getIsLiteral())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL, classB.getFullName(), classB.getIsLiteral())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, classA.getClassificationProperty())
                                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, classB.getClassificationProperty());
                            QueryResponse countQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                            if (!countQueryResponse.hasErrors() && !countQueryResponse.getResults().isEmpty()) {
                                Long intersectionCount = SchemaUtil.getLongValueFromString(countQueryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                                addNeighbor(graphOfClasses, classA.getFullName(), classB.getFullName(), intersectionCount);
                            } else if (countQueryResponse.hasErrors()) {
                                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.COUNT,
                                        classA.getFullName(), null, queryBuilder,
                                        checkQuery == CHECK_CLASS_INTERSECTION_DISTINCT ? "Check class intersection. Unable to obtain distinct statistics." : "Check class intersection. Unable to obtain statistics.");
                            }
                        }
                    }
                }
            }
        }
    }

    private void updateProcessedClasses(@Nonnull Map<String, List<String>> processedClasses, @Nonnull SchemaClass key, @Nullable SchemaClass neighbor) {
        String keyName = key.getFullName();
        if (neighbor == null) {
            processedClasses.put(keyName, null);
            return;
        }
        processedClasses.computeIfAbsent(keyName, k -> new ArrayList<>()).add(neighbor.getFullName());
    }

    private void addNeighbor(@Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses, @Nonnull String classA, @Nonnull String classB,
                             @Nonnull Long intersectionCount) {

        if (intersectionCount <= 0L) {
            return;
        }

        SchemaExtractorClassNodeInfo nodeA = graphOfClasses.get(classA);
        SchemaExtractorClassNodeInfo nodeB = graphOfClasses.get(classB);
        if (nodeA == null || nodeB == null) {
            return;
        }

        addNeighborIfAbsent(nodeA, classB, intersectionCount);
        addNeighborIfAbsent(nodeB, classA, intersectionCount);
    }

    private void addNeighborIfAbsent(@Nonnull SchemaExtractorClassNodeInfo node, @Nonnull String neighborClassName, @Nonnull Long intersectionCount) {
        boolean exists = node.getNeighbors().stream().anyMatch(n -> neighborClassName.equals(n.getClassName()));
        if (!exists) {
            node.getNeighbors().add(new SchemaExtractorIntersectionClassDto(neighborClassName, intersectionCount));
        }
    }

    protected void updateGraphOfClassesWithNeighbors(@Nonnull Schema schema, @Nonnull String sourceClass, @Nonnull List<QueryResult> queryResults,
                                                     @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                     @Nonnull SchemaExtractorRequestDto request) {
        Set<String> includedClasses = request.getIncludedClasses().stream().map(SchemaExtractorRequestedClassDto::getClassName).collect(Collectors.toSet());
        for (QueryResult queryResult : queryResults) {
            String classB = queryResult.getValueFullName(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B);
            if (StringUtils.isNotEmpty(classB)
                    && isNotExcludedResource(classB, request.getExcludedNamespaces())
                    && (includedClasses.isEmpty() || includedClasses.contains(classB))
                    && findClass(schema.getClasses(), classB) != null) {
                Long intersectionCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                addNeighbor(graphOfClasses, sourceClass, classB, intersectionCount);
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
                    compareResult = ObjectUtils.compare(o1.getValue().getTripleCount(), o2.getValue().getTripleCount());
                    if (compareResult != 0) {
                        return compareResult;
                    } else {
                        return nullSafeStringComparator.compare(o1.getValue().getClassName(), o2.getValue().getClassName());
                    }
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    @Nonnull
    protected List<SchemaExtractorIntersectionClassDto> sortNeighborsByTripleCountAsc(@Nonnull List<SchemaExtractorIntersectionClassDto> neighbors,
                                                                                      @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
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
    protected List<SchemaExtractorIntersectionClassDto> sortNeighborsByTripleCountDsc(@Nonnull List<SchemaExtractorIntersectionClassDto> neighbors,
                                                                                      @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
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
    protected List<SchemaClass> sortClassesByTripleCountAsc(@Nonnull List<SchemaClass> classes) {
        // sort classes by triple count (ascending)
        return classes.stream()
                .sorted((c1, c2) -> {
                    int compareResult = nullSafeLongComparator.compare(c1.getInstanceCount(), c2.getInstanceCount());
                    if (compareResult == 0) {
                        return nullSafeStringComparator.compare(c1.getFullName(), c2.getFullName());
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
                    Long indexA = Optional.ofNullable(o1.getTripleCount()).orElse(0L);
                    Long indexB = Optional.ofNullable(o2.getTripleCount()).orElse(0L);
                    int compareResult = indexB.compareTo(indexA);
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

    // Apply the superclass diagnostic policy without changing superclass selection after an endpoint failure.
    protected void findSuperClass(@Nonnull Schema schema, @Nonnull SchemaClass currentClass, @Nonnull SchemaExtractorClassNodeInfo currentClassInfo,
                                  @Nonnull List<SchemaExtractorIntersectionClassDto> neighbors,
                                  @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, List<SchemaClass> classes,
                                  @Nonnull SchemaExtractorRequestDto request) {

        for (SchemaExtractorIntersectionClassDto neighbor : neighbors) {
            SchemaExtractorClassNodeInfo neighborClassInfo = classesGraph.get(neighbor.getClassName());
            if (neighborClassInfo == null) {
                log.error("The Neighbor [" + neighbor + "] of the class [ " + currentClassInfo.getClassName() + " ] cannot be found in the classes list.");
                continue;
            }
            Long neighborClassTotalInstances = (neighborClassInfo.getTripleCount() != null ? neighborClassInfo.getTripleCount() : 0L);
            Long currentClassInfoTripleCount = (currentClassInfo.getTripleCount() != null ? currentClassInfo.getTripleCount() : 0L);
            if (neighborClassTotalInstances < currentClassInfoTripleCount || neighbor.getInstanceCount() < currentClassInfoTripleCount
                    || neighborClassTotalInstances < request.getMinimalAnalyzedClassSize()) {
                continue;
            }
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_SUPERCLASS.name()), CHECK_SUPERCLASS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, currentClass.getFullName(), currentClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL, neighborClassInfo.getClassName(), neighborClassInfo.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A, currentClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B, neighborClassInfo.getClassificationProperty());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.STRUCT_SUB,
                        currentClass.getFullName(), null, queryBuilder,
                        "Check superclass failed. Assume no superclass relation.");
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
                if (superClass == null || THING_NAME.equals(superClass.getLocalName())) {
                    continue;
                }
            }

            SchemaExtractorClassNodeInfo classInfo = classesGraph.get(currentClass.getFullName());
            if (classInfo == null) {
                continue;
            }

            // 2. validate whether all neighbors are accessible
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
        return allPairs.stream().filter(pair -> sourceClass.equals(pair.getSourceClass())).collect(Collectors.toList());
    }

    @Nonnull
    protected List<SchemaExtractorSourceTargetInfo> findPairsWithSpecificTarget(@Nonnull List<SchemaExtractorSourceTargetInfo> allPairs, @Nonnull String targetClass) {
        return allPairs.stream().filter(pair -> targetClass.equals(pair.getTargetClass())).collect(Collectors.toList());
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
                    .filter(c -> c.getClassName().equals(linkedClass.getClassName())).findFirst().orElse(null);
            linkedClass.setImportanceIndex((classInfo != null) ? classInfo.getImportanceIndex() : 0);
        });
    }

    @Nonnull
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyPairTargets(@Nonnull List<SchemaExtractorSourceTargetInfo> propertyLinkedPairs,
                                                                                           @Nonnull List<SchemaClass> classes) {
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
    protected List<SchemaExtractorPropertyLinkedClassInfo> buildAndSortPropertyPairSources(@Nonnull List<SchemaExtractorSourceTargetInfo> propertyLinkedPairs,
                                                                                           @Nonnull List<SchemaClass> classes) {
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
                buildLabelsForSchemaElements(request, schema, label, schema.getClasses());
                log.info(request.getCorrelationId() + " - findLabelsForProperties " + label);
                buildLabelsForSchemaElements(request, schema, label, schema.getProperties());
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

    // Pass the extraction schema into shared label reading so query failures are reported for both classes and properties.
    protected void buildLabelsForSchemaElements(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull SchemaExtractorRequestedLabelDto label,
                                                @Nonnull List<? extends SchemaElement> elements) {
        elements.forEach(element -> {
            QueryResponse queryResponse;
            if (label.getLanguages().isEmpty()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL.name()), FIND_LABEL)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE1, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE2, label.getLabelProperty());
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.LABELS,
                            element.getFullName(), null, queryBuilder);
                }
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL_WITH_LANG.name()), FIND_LABEL_WITH_LANG)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE1, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE2, label.getLabelProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildFilterWithLanguages(label.getLanguages()));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    addExtractionMessage(schema, NOTE, SchemaExtractorMessage.FunctionalityType.LABELS,
                            element.getFullName(), null, queryBuilder);
                }
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

    @Nonnull
    private SchemaExtractorQueries selectQuery(@Nonnull SchemaExtractorRequestDto.DistinctQueryMode distinctMode,
                                               @Nonnull SchemaExtractorQueries regularQuery, @Nonnull SchemaExtractorQueries distinctQuery) {
        if (SchemaExtractorRequestDto.DistinctQueryMode.yes.equals(distinctMode)) {
            return distinctQuery;
        }
        return regularQuery;
    }

    private boolean hasConfirmedNoNonLiteralObjects(SchemaExtractorPropertyNodeInfo property) {
        Long objectCount = property.getObjectTripleCount();
        if (objectCount != null) return objectCount == 0;
        Long total = nonNegativeCount(property.getTripleCount());
        return total != null && (total == 0 || total.equals(property.getDataTripleCount()));
    }

}

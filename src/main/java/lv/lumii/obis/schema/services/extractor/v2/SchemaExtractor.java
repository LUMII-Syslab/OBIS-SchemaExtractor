package lv.lumii.obis.schema.services.extractor.v2;

import com.google.common.collect.Lists;
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
import java.util.*;
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

    private static final List<Long> sampleLimits = List.of(1000000L, 100000L, 10000L, 1000L);

    private static final List<Long> limitsLarge = List.of(10000000L, 1000000L, 100000L, 10000L, 1000L);
    private static final List<Long> sampleLimitsSmall = List.of(300L, 100L, 30L, 10L);

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

    protected void validateEndpointHealth(@Nonnull SchemaExtractorRequestDto request) {
        // validate GET health check query
        request.setPostMethod(Boolean.FALSE);
        boolean isEndpointHealthy = sparqlEndpointProcessor.checkEndpointHealthQuery(request);
        if (isEndpointHealthy) {
            log.info(String.format("The endpoint [ %s ] is available and in working state - schema extractor execution [ %s ] is in progress with GET requests",
                    SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId()));
            return;
        }

        // validate POST health check query
        request.setPostMethod(Boolean.TRUE);
        isEndpointHealthy = sparqlEndpointProcessor.checkEndpointHealthQuery(request);
        if (isEndpointHealthy) {
            log.info(String.format("The endpoint [ %s ] is available and in working state - schema extractor execution [ %s ] is in progress with POST requests",
                    SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId()));
            return;
        }

        // log error and stop extractor execution for the specific request
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
                    schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Invalid class URI in the input request", includedClass.getClassName(),
                            null, INVALID_URI, null));
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
                            schema.getMessages().add(new SchemaExtractorMessage(WARNING_HIGH, includedClass.getClassName(), query.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Error reading all classes with instance counts", null, null, query.name(), queryBuilder.getQueryString()));
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
                    schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Error reading all classes without instance counts", null, null,
                            FIND_CLASSES.name(), queryBuilder.getQueryString()));
                } else {
                    int totalClasses = 0;
                    for (QueryResult queryResult : queryResponse.getResults()) {
                        QueryResultObject classNameObject = queryResult.getResultObject(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
                        if (classNameObject == null) {
                            continue;
                        }
                        if (!classNameObject.getIsLiteral() && !SchemaUtil.isValidURI(classNameObject.getValue())) {
                            log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + classNameObject.getValue());
                            schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Invalid class URI returned from the endpoint", classNameObject.getValue(),
                                    null, INVALID_URI, null));
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
                schema.getMessages().add(new SchemaExtractorMessage(INFO, null, FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(ERROR, null, FIND_CLASSES.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Found " + classesFromSimpleQuery + " more classes than FIND_CLASSES_WITH_INSTANCE_COUNT",
                        null, null, FIND_CLASSES.name(), queryBuilder.getQueryString()));
                log.info(request.getCorrelationId() + String.format(" - FIND_CLASSES found %d more classes than FIND_CLASSES_WITH_INSTANCE_COUNT for classification property %s", classesFromSimpleQuery, classificationProperty));
            }
            log.info(request.getCorrelationId() + String.format(" - found total %d classes with classification property %s", classesCountFromStatisticsQuery + classesFromSimpleQuery, classificationProperty));
        }
    }

    protected boolean readInstanceCountForClass(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull QueryResultObject classNameObject,
                                                @Nonnull String classificationProperty, @Nonnull List<SchemaClass> classes) {
        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_INSTANCE_COUNT_FOR_CLASS, FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT);
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, classNameObject.getValue(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            String instancesCountStr = queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
            if (SchemaUtil.getLongValueFromString(instancesCountStr) > 0L) {
                addClass(classNameObject, classNameObject.getValue(), instancesCountStr, classificationProperty, classes, request);
                return true;
            }
        } else {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING_HIGH, classNameObject.getValue(), query.name(), queryBuilder.getQueryString()));
        }
        return false;
    }

    protected void readDistinctInstanceCountForClass(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull SchemaClass clazz) {
        log.info(request.getCorrelationId() + " - findDistinctInstanceCountForClass");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT.name()), FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL, clazz.getFullName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
            Long distinctCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (distinctCount > 0L) {
                clazz.setDistinctInstances(distinctCount);
            }
        } else {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, clazz.getFullName(), FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT.name(), queryBuilder.getQueryString()));
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
            if (!classNameObject.getIsLiteral() && !SchemaUtil.isValidURI(classNameObject.getValue())) {
                log.error(request.getCorrelationId() + " - invalid class URI will not be processed - " + classNameObject.getValue());
                schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Invalid class URI returned from the endpoint", classNameObject.getValue(),
                        null, INVALID_URI, null));
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
                    schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Invalid property URI in the input request", includedProperty.getPropertyName(),
                            null, INVALID_URI, null));
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
                        schema.getMessages().add(new SchemaExtractorMessage(ERROR, includedProperty.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, null, query.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(ERROR, null, FIND_PROPERTIES.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(INFO, null, FIND_PROPERTIES_WITH_TRIPLE_COUNT_DISTINCT.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, null, FIND_PROPERTIES.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Found " + propertiesFromSimpleQuery + " more properties than FIND_PROPERTIES_WITH_TRIPLE_COUNT",
                        null, null, FIND_PROPERTIES.name(), queryBuilder.getQueryString()));
                log.info(request.getCorrelationId() + String.format(" - FIND_PROPERTIES found %d more properties than FIND_PROPERTIES_WITH_TRIPLE_COUNT", propertiesFromSimpleQuery));
            }
        }
    }

    @Nonnull
    protected Set<String> readPropertiesForClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        Set<String> properties = new HashSet<>();

        for (SchemaClass clazz : schema.getClasses()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTIES_FOR_CLASS.name()), FIND_PROPERTIES_FOR_CLASS)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, clazz.getFullName(), clazz.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty());
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (queryResponse.hasErrors() || queryResponse.getResults().isEmpty()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, clazz.getFullName(), FIND_PROPERTIES_FOR_CLASS.name(), queryBuilder.getQueryString()));
                for (Long limit : sampleLimits) {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTIES_FOR_CLASS_WITH_LIMIT.name()), FIND_PROPERTIES_FOR_CLASS_WITH_LIMIT)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, clazz.getFullName(), clazz.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
                    queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (queryResponse.hasErrors() || queryResponse.getResults().isEmpty()) {
                        schema.getMessages().add(new SchemaExtractorMessage(WARNING, clazz.getFullName(), FIND_PROPERTIES_FOR_CLASS_WITH_LIMIT.name(), queryBuilder.getQueryString()));
                        continue;
                    }
                    properties = addPropertyNames(queryResponse.getResults(), schema);
                    break;
                }
            } else {
                properties.addAll(addPropertyNames(queryResponse.getResults(), schema));
            }
        }

        return properties;
    }

    @Nonnull
    protected Set<String> addPropertyNames(@Nonnull List<QueryResult> queryResults, @Nonnull Schema schema) {
        Set<String> properties = new HashSet<>();
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            if (StringUtils.isEmpty(propertyName)) continue;
            if (SchemaUtil.isValidURI(propertyName)) {
                properties.add(propertyName);
            } else {
                schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Invalid property URI returned from the endpoint", propertyName,
                        null, INVALID_URI, null));
            }
        }
        return properties;
    }

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
            schema.getMessages().add(new SchemaExtractorMessage(ERROR, propertyName, FIND_TRIPLE_COUNT_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT.name(), queryBuilder.getQueryString()));
        }
    }

    @Nonnull
    protected Map<String, SchemaExtractorPropertyNodeInfo> processPropertiesWithEndpointData(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request,
                                                                                             @Nonnull Schema schema) {
        Map<String, SchemaExtractorPropertyNodeInfo> properties = new HashMap<>();
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            if (!SchemaUtil.isValidURI(propertyName)) {
                log.error(request.getCorrelationId() + " - invalid property URI will not be processed - " + propertyName);
                schema.getMessages().add(new SchemaExtractorMessage(ERROR, "Invalid property URI returned from the endpoint", propertyName,
                        null, INVALID_URI, null));
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

            determinePropertyObjectTripleCount(schema, property, request, totalCountOfProperties);
            determinePropertyDataTripleCount(schema, property, request, totalCountOfProperties);
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
            if (calculateBlankNodeObjects && (property.getObjectTripleCount() == null || property.getObjectTripleCount() != 0)) {
                if (blankNodeObjects.isEmpty()) {
                    determinePropertyBlankNodeObjects(schema, property, request);
                } else {
                    property.setBlankNodeObjects(blankNodeObjects.get(property.getPropertyName()));
                }
            }

        }
        // Initialize existing statistics before the ordered mapping passes and keep later enrichment on its usual DTO lists.
        Map<SchemaClass, Boolean> classBlankChecks = new IdentityHashMap<>();
        determinePropertySources(schema, properties, request, classBlankChecks);
        Map<String, SchemaExtractorPropertyNodeInfo> targetProperties = new LinkedHashMap<>();
        properties.forEach((name, property) -> {
            if (calculateTargetClasses(request, property)) targetProperties.put(name, property);
        });
        determinePropertyTargets(schema, targetProperties, request, classBlankChecks);
        // enrich property with detailed information
        currentPropertyInd = 1;
        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {

            SchemaExtractorPropertyNodeInfo property = entry.getValue();

            log.info(request.getCorrelationId() + String.format(PROPERTY_PROCESSING, currentPropertyInd++, totalCountOfProperties, property.getPropertyName()));

            determinePropertySourceObjectTripleCount(schema, property, request, totalCountOfProperties);
            determinePropertySourceDataTripleCount(schema, property, request, totalCountOfProperties);
            if (calculateTargetClasses(request, property) && isTrue(request.getCalculateSourceAndTargetPairs())) {
                determinePropertySourceTargetPairs(schema, property, request, totalCountOfProperties);
            }
            if (isTrue(SchemaExtractorRequestDto.DistinctSubjectsAndObjectsMode.yes.equals(request.getIncludeDistinctSubjectsAndObjects()))) {
                determineDistinctPropertySubjectsOnSourceClassLevel(schema, property, request);
                determineDistinctPropertyObjectsOnTargetClassLevel(schema, property, request);
            }

            if (isTrue(request.getCalculateClosedClassSets())) {
                determinePropertyClosedDomains(schema, property, request, totalCountOfProperties);
                determinePropertyClosedRanges(schema, property, request, totalCountOfProperties);
                determinePropertyClosedRangesOnSourceClassLevel(schema, property, request, totalCountOfProperties);
                determinePropertyClosedDomainsOnTargetClassLevel(schema, property, request, totalCountOfProperties);
            }

            if (isNotTrue(property.getIsObjectProperty())) {
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
                determineOutgoingProperties(schema, property, request, properties);
                if (property.getObjectTripleCount() != null && property.getObjectTripleCount() != 0) {
                    determineFollowers(schema, property, request, properties);
                    determineIncomingProperties(schema, property, request, properties);
                }
            }

            switch (request.getCalculateCardinalities()) {
                case propertyLevelOnly:
                    determinePropertyMaxCardinality(schema, property, request, totalCountOfProperties);
                    if (property.getObjectTripleCount() != null && property.getObjectTripleCount() != 0) {
                        determinePropertyInverseMaxCardinality(schema, property, request, totalCountOfProperties);
                    }
                    break;
                case propertyLevelAndClassContext:
                    determinePropertyMaxCardinality(schema, property, request, totalCountOfProperties);
                    determinePropertySourceMaxCardinality(schema, property, request, totalCountOfProperties);
                    determinePropertySourceMinCardinality(schema, property, request, totalCountOfProperties);
                    if (property.getObjectTripleCount() != null && property.getObjectTripleCount() != 0) {
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
        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), COUNT_PROPERTY_URL_VALUES, COUNT_PROPERTY_URL_VALUES_DISTINCT);
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponseForUrlCount = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponseForUrlCount.hasErrors() && !queryResponseForUrlCount.getResults().isEmpty() && queryResponseForUrlCount.getResults().get(0) != null) {
            long objectTripleCount = SchemaUtil.getLongValueFromString(queryResponseForUrlCount.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            property.setObjectTripleCount(objectTripleCount);
        } else {
            schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_URL_VALUES.name()), CHECK_PROPERTY_URL_VALUES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse checkQueryResponseForUrlCount = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!checkQueryResponseForUrlCount.hasErrors()) {
                if (checkQueryResponseForUrlCount.getResults().isEmpty()) {
                    property.setObjectTripleCount(0L);
                } else {
                    property.setObjectTripleCount(-1L);
                }
            } else {
                schema.getMessages().add(new SchemaExtractorMessage(ERROR, property.getPropertyName(), CHECK_PROPERTY_URL_VALUES.name(), queryBuilder.getQueryString()));
            }
        }
    }

    protected void determinePropertyDataTripleCount(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTripleCount [" + property.getPropertyName() + "]");

        // get count of URL values for the specific property
        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), COUNT_PROPERTY_LITERAL_VALUES, COUNT_PROPERTY_LITERAL_VALUES_DISTINCT);
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponseForLiteralCount = sparqlEndpointProcessor.read(request, queryBuilder);
        if (!queryResponseForLiteralCount.hasErrors() && !queryResponseForLiteralCount.getResults().isEmpty() && queryResponseForLiteralCount.getResults().get(0) != null) {
            long dataTripleCount = SchemaUtil.getLongValueFromString(queryResponseForLiteralCount.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            property.setDataTripleCount(dataTripleCount);
        } else {
            schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_LITERAL_VALUES.name()), CHECK_PROPERTY_LITERAL_VALUES)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse checkQueryResponseForLiteralCount = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!checkQueryResponseForLiteralCount.hasErrors()) {
                if (checkQueryResponseForLiteralCount.getResults().isEmpty()) {
                    property.setDataTripleCount(0L);
                } else {
                    property.setDataTripleCount(-1L);
                }
            } else {
                schema.getMessages().add(new SchemaExtractorMessage(ERROR, property.getPropertyName(), CHECK_PROPERTY_LITERAL_VALUES.name(), queryBuilder.getQueryString()));
            }
        }
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
        // Use the same internal count-work marker for source and target contexts without exporting it.
        int sourceClassCountsOK;
        int missingCounts;
        int pendingRefinements;
    }

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
    }

    private static final class InstanceBatchResult<K> {
        final Map<K, Long> counts = new LinkedHashMap<>();
        final BitSet covered = new BitSet();
    }

    // Run the revised source passes in this extractor while sharing blank-presence observations with target extraction.
    protected void determinePropertySources(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                            SchemaExtractorRequestDto request, Map<SchemaClass, Boolean> blankChecks) {
        determineClassPropertyMappings(schema, properties, request, false, blankChecks);
    }

    // Apply the same execution and count policies to the target direction without duplicating the extraction algorithm.
    protected void determinePropertyTargets(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                            SchemaExtractorRequestDto request, Map<SchemaClass, Boolean> blankChecks) {
        determineClassPropertyMappings(schema, properties, request, true, blankChecks);
    }

    // Keep mapping state request-local and store only correspondences that have actually been discovered.
    private void determineClassPropertyMappings(Schema schema, Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                               SchemaExtractorRequestDto request, boolean target, Map<SchemaClass, Boolean> blankChecks) {
        ClassPropertyContext context = new ClassPropertyContext();
        context.schema = schema;
        context.request = request;
        context.properties = properties;
        context.target = target;
        context.blankChecks = blankChecks;
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
        if (properties.values().stream().allMatch(property -> classPropertyHandled(context, property))) {
            context.classes.forEach((clazz, state) -> { state.complete = true; classPropertyClassQuality(context, clazz, 7); });
            publishClassPropertyMappings(context);
            return;
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
            return;
        }
        boolean needsSampling = properties.values().stream().anyMatch(property -> classPropertyPropertyQuality(context, property) < 7
                || context.propertyStates.get(property).sourceClassCountsOK < 2);
        if (needsSampling) {
            if (target) checkIncomingPropertiesByClassWithLimits(context);
            else checkOutgoingPropertiesByClassWithLimits(context);
        }
        if (target) checkIndividualPropertyToClassPairs(context);
        else checkIndividualClassToPropertyPairs(context);
        setClassPropertyQualityMarks(context);
        publishClassPropertyMappings(context);
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

    // Separate complete correspondence discovery from count completion and preserve quality seven during partial count recovery.
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
                usableCounts ? count : null, null, complete, !complete && usableCounts));
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

    // Apply a direct response only after validating it and report recoverable aggregate failures as INFO(Struct).
    private Set<String> readDirectPropertyClasses(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property,
                                                 Collection<String> predicates, boolean withCounts) {
        Set<String> failed = new LinkedHashSet<>();
        SchemaExtractorQueries name = withCounts ? (context.target
                ? selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT, FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT, FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_DISTINCT))
                : context.target ? FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT : FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT;
        for (String predicate : predicates) {
            SparqlQueryBuilder query = propertyClassQuery(context, property, predicate, name);
            Map<SchemaClass, Long> counts = readGroupedCounts(sparqlEndpointProcessor.read(context.request, query), "class",
                    row -> context.classIndex.get(predicate).get(row.getValueFullName("class")), withCounts);
            if (counts == null) {
                failed.add(predicate);
                addExtractionMessage(context.schema, INFO, SchemaExtractorMessage.FunctionalityType.Struct, property.getPropertyName(), null, query);
            } else {
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

    // Finish each full class attempt before later sampling passes and recover small classes through the shared batch executor.
    private void checkPropertiesForClass(ClassPropertyContext context, SchemaClass clazz) {
        Map<String, TripleCount> counts = context.target ? determineClassIncomingProperties(context, clazz, null)
                : determineClassOutgoingProperties(context, clazz, null);
        if (counts != null) {
            counts.forEach((name, count) -> recordClassPropertyRelation(context, clazz, context.properties.get(name), count.getTripleCount(), null, true, false));
            context.classes.get(clazz).complete = true;
            classPropertyClassQuality(context, clazz, 7);
            refreshClassPropertyCountMarks(context);
            return;
        }
        if (!smallClassPropertyEntity(classPropertyClassSize(clazz)) || !noClassBlankNodes(context, clazz)) return;
        List<String> instances = readClassPropertyInstances(context, null, clazz, 0);
        if (instances == null || instances.size() > CLASS_PROPERTY_INSTANCE_LIMIT) return;
        InstanceBatchResult<SchemaExtractorPropertyNodeInfo> result = executeClassInstanceBatches(context, clazz, instances);
        boolean complete = result.covered.cardinality() == instances.size();
        result.counts.forEach((property, count) -> recordClassPropertyRelation(context, clazz, property, count, null, complete, !complete));
        if (complete) {
            context.classes.get(clazz).complete = true;
            classPropertyClassQuality(context, clazz, 7);
        } else if (!result.covered.isEmpty()) {
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

    // Reuse query selection and scoped VALUES for both full and sampled class responses.
    private Map<String, TripleCount> determineClassProperties(ClassPropertyContext context, SchemaClass clazz, Long limit) {
        SchemaExtractorQueries name = context.target
                ? (limit == null ? selectQuery(context.request.getExactCountCalculations(), FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS, FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS, FIND_INCOMING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS_DISTINCT))
                : (limit == null ? selectQuery(context.request.getExactCountCalculations(), FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS, FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS, FIND_OUTGOING_PROPERTY_TRIPLE_COUNTS_FOR_CLASS_WITH_LIMITS_DISTINCT));
        SparqlQueryBuilder query = classPropertiesQuery(context, clazz, name);
        if (limit != null) query.withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, limit.toString());
        QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
        Map<String, TripleCount> counts = context.target ? getClassIncomingProperties(context, response, limit) : getClassOutgoingProperties(context, response, limit);
        if (counts == null && limit == null) addExtractionMessage(context.schema, INFO, SchemaExtractorMessage.FunctionalityType.Struct, clazz.getFullName(), null, query);
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
        Map<SchemaExtractorPropertyNodeInfo, Long> counts = readGroupedCounts(response, "property",
                row -> context.activeProperties.get(row.getValue("property")), true);
        if (counts == null) return null;
        Map<String, TripleCount> result = new LinkedHashMap<>();
        counts.forEach((property, count) -> result.put(property.getPropertyName(), new TripleCount(count, base)));
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
            best.forEach((name, count) -> recordClassPropertyRelation(context, clazz, context.properties.get(name), count.getTripleCount(), count.getTripleCountBase(), false, true));
            setClassPropertyLimit(context, clazz, bestLimit);
            if (classPropertyClassQuality(context, clazz) < 4) classPropertyClassQuality(context, clazz, 4);
        } else {
            List<String> instances = readClassPropertyInstances(context, null, clazz, CLASS_PROPERTY_INSTANCE_LIMIT);
            if (instances == null || instances.isEmpty()) return;
            InstanceBatchResult<SchemaExtractorPropertyNodeInfo> result = executeClassInstanceBatches(context, clazz, instances);
            int successful = result.covered.cardinality();
            boolean enoughRetrieved = instances.size() >= 500 || size != null && size >= 0 && (long) instances.size() * 2 >= size;
            boolean usableCounts = enoughRetrieved && (long) successful * 2 >= instances.size();
            result.counts.forEach((property, count) -> recordClassPropertyRelation(context, clazz, property,
                    usableCounts ? count : null, usableCounts ? (long) successful : null, false, usableCounts));
            if (usableCounts) {
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

    // Reuse positive evidence and complete positive sets before querying unknown pairs, without storing negative-pair tables.
    private void checkIndividualClassPropertyPairs(ClassPropertyContext context) {
        for (SchemaClass clazz : context.classes.keySet()) {
            if (context.classes.get(clazz).complete) continue;
            for (SchemaExtractorPropertyNodeInfo property : context.properties.values()) {
                if (classPropertyHandled(context, property)) continue;
                TripleCount relation = classPropertyLinks(context, clazz).get(property.getPropertyName());
                if (relation == null) {
                    if (context.propertyStates.get(property).complete) continue;
                    SparqlQueryBuilder query = classPropertyPairQuery(context, clazz, property,
                            context.target ? CHECK_CLASS_AS_PROPERTY_TARGET : CHECK_CLASS_AS_PROPERTY_SOURCE);
                    QueryResponse response = sparqlEndpointProcessor.read(context.request, query);
                    if (response.hasErrors()) {
                        context.classes.get(clazz).failures++;
                        context.propertyStates.get(property).failures++;
                        addExtractionMessage(context.schema, WARNING, SchemaExtractorMessage.FunctionalityType.Struct, property.getPropertyName(), clazz.getFullName(), query);
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
        else addExtractionMessage(context.schema, refinement ? MESSAGE : WARNING, SchemaExtractorMessage.FunctionalityType.Count, property.getPropertyName(), clazz.getFullName(), query);
    }

    // Share request-local coverage tracking while leaving completion thresholds and quality assignment with the caller.
    private <K> void executeInstanceBatches(Schema schema, SchemaExtractorRequestDto request, List<String> instances, int start, int end, String binding,
                                            java.util.function.Supplier<SparqlQueryBuilder> factory, java.util.function.Function<QueryResult, K> key,
                                            InstanceBatchResult<K> result, InstanceQueryState state, String entity) {
        if (start == end) return;
        SparqlQueryBuilder query = factory.get().withInstanceValues(instances.subList(start, end));
        Map<K, Long> counts = readGroupedCounts(sparqlEndpointProcessor.read(request, query), binding, key, true);
        if (counts != null) {
            counts.forEach((value, count) -> result.counts.merge(value, count, Math::addExact));
            result.covered.set(start, end);
            return;
        }
        int size = end - start;
        if (size == 1) {
            if (!state.singletonReported) {
                addExtractionMessage(schema, MESSAGE, SchemaExtractorMessage.FunctionalityType.Struct, entity, null, query);
                state.singletonReported = true;
            }
            return;
        }
        int batchSize = size > 100 ? 100 : size > 10 ? 10 : 1;
        for (int offset = start; offset < end; offset += batchSize) executeInstanceBatches(schema, request, instances, offset, Math.min(offset + batchSize, end), binding, factory, key, result, state, entity);
    }

    // Build class-instance batches with the same active property scope used by the aggregate and LIMIT queries.
    private InstanceBatchResult<SchemaExtractorPropertyNodeInfo> executeClassInstanceBatches(ClassPropertyContext context, SchemaClass clazz, List<String> instances) {
        SchemaExtractorQueries name = context.target
                ? selectQuery(context.request.getExactCountCalculations(), FIND_INSTANCE_INCOMING_PROPERTIES, FIND_INSTANCE_INCOMING_PROPERTIES_DISTINCT)
                : selectQuery(context.request.getExactCountCalculations(), FIND_INSTANCE_OUTGOING_PROPERTIES, FIND_INSTANCE_OUTGOING_PROPERTIES_DISTINCT);
        InstanceBatchResult<SchemaExtractorPropertyNodeInfo> result = new InstanceBatchResult<>();
        executeInstanceBatches(context.schema, context.request, instances, 0, instances.size(), "property", () -> classPropertiesQuery(context, clazz, name),
                row -> context.activeProperties.get(row.getValue("property")), result, context.classes.get(clazz), clazz.getFullName());
        return result;
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
        ClassPropertyState state = context.propertyStates.get(property);
        TripleCount relation = classPropertyLinks(context, clazz).get(property.getPropertyName());
        if (relation == null) {
            relation = new TripleCount(null, null);
            classPropertyLinks(context, clazz).put(property.getPropertyName(), relation);
            state.missingCounts++;
        }
        if (count != null && (replace || relation.getTripleCount() == null)) {
            if (relation.getTripleCount() == null) state.missingCounts--;
            relation.setTripleCount(count);
            relation.setTripleCountBase(base);
            if (replace && relation.isCountNeedsRefinement()) { relation.setCountNeedsRefinement(false); state.pendingRefinements--; }
        }
        if (refine && fineTuneClassPropertyCounts(context) && relation.getTripleCount() != null && !relation.isCountNeedsRefinement()) {
            relation.setCountNeedsRefinement(true);
            state.pendingRefinements++;
        }
        return relation;
    }

    // Recalculate count completion after later recovery while keeping structural completeness independent of count availability.
    private void refreshClassPropertyCountMarks(ClassPropertyContext context) {
        context.propertyStates.values().forEach(state -> state.sourceClassCountsOK = state.pendingRefinements > 0 ? 2 : state.missingCounts > 0
                ? (state.sourceClassCountsOK >= 2 ? 2 : 0) : 3);
    }

    // Apply the agreed structural failure scale without downgrading quality five to limit-based quality four.
    protected void setClassPropertyQualityMarks(ClassPropertyContext context) {
        context.classes.forEach((clazz, state) -> {
            if (state.failures == 0) { state.complete = true; classPropertyClassQuality(context, clazz, 7); }
            else if (classPropertyClassQuality(context, clazz) < 3) classPropertyClassQuality(context, clazz, classPropertyFailureQuality(state.failures, context.properties.size()));
        });
        boolean allSampled = context.classes.keySet().stream().allMatch(clazz -> classPropertyClassQuality(context, clazz) >= 4);
        context.propertyStates.forEach((property, state) -> {
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

    // Publish canonical mappings once before existing source/target-dependent enrichment consumes its usual DTO lists.
    private void publishClassPropertyMappings(ClassPropertyContext context) {
        context.properties.values().forEach(property -> { if (context.target) property.getTargetClasses().clear(); else property.getSourceClasses().clear(); });
        context.classes.forEach((clazz, state) -> {
            if (context.target) clazz.setIncomingPropertiesFullList(state.complete); else clazz.setOutgoingPropertiesFullList(state.complete);
            classPropertyLinks(context, clazz).forEach((name, count) -> {
                SchemaExtractorPropertyNodeInfo property = context.properties.get(name);
                if (property == null) return;
                SchemaExtractorClassNodeInfo info = new SchemaExtractorClassNodeInfo(clazz.getFullName(), count.getTripleCount(), clazz.getClassificationProperty(), clazz.getIsLiteral());
                info.setTripleCountBase(count.getTripleCountBase());
                if (context.target) property.getTargetClasses().add(info); else property.getSourceClasses().add(info);
            });
        });
        context.properties.values().forEach(property -> {
            boolean enabled = context.request.getLogNoClassesForProperty() == SchemaExtractorRequestDto.NoClassesLoggingOptions.yes
                    || !context.target && context.request.getLogNoClassesForProperty() == SchemaExtractorRequestDto.NoClassesLoggingOptions.sourcesOnly;
            if (enabled && classPropertyPropertyQuality(context, property) == 7 && (context.target ? property.getTargetClasses() : property.getSourceClasses()).isEmpty()) {
                SchemaExtractorMessage message = new SchemaExtractorMessage(INFO, context.target ? "No target class for a property" : "No source class for a property", property.getPropertyName(), null, null, null);
                message.setFunctionalityType(SchemaExtractorMessage.FunctionalityType.Struct);
                context.schema.getMessages().add(message);
            }
        });
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
        Set<String> scope = context.activeProperties != context.properties || !context.request.getIncludedProperties().isEmpty() ? context.activeProperties.keySet() : null;
        updateValueClause(query, scope, "<propertyValuesClause>", SPARQL_QUERY_BINDING_NAME_PROPERTY);
        updateValueClause(query, scope, SPARQL_QUERY_BINDING_NAME_VALUES, SPARQL_QUERY_BINDING_NAME_PROPERTY);
        return query;
    }

    // Bind an individual property without reintroducing a residual VALUES clause into pair queries.
    private SparqlQueryBuilder classPropertyPairQuery(ClassPropertyContext context, SchemaClass clazz, SchemaExtractorPropertyNodeInfo property, SchemaExtractorQueries name) {
        return classPropertyQuery(context, name).withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, clazz.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, clazz.getFullName(), clazz.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
    }

    // Centralize typed diagnostics while retaining existing schema-level message flag behavior.
    private void addExtractionMessage(Schema schema, SchemaExtractorMessage.MessageLevel level, SchemaExtractorMessage.FunctionalityType type,
                                      String entity, String related, SparqlQueryBuilder query) {
        SchemaExtractorMessage message = new SchemaExtractorMessage(level, entity, query.getQueryName(), query.getQueryString());
        message.setFunctionalityType(type);
        message.setRelatedEntity(related);
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

    // Update the appropriate source or target quality through the same shared workflow.
    private void classPropertyPropertyQuality(ClassPropertyContext context, SchemaExtractorPropertyNodeInfo property, int quality) {
        if (context.target) property.setTargetClassesOK(quality); else property.setSourceClassesOK(quality);
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

    protected boolean determineObjectTripleCountForAllPropertySources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_URL_VALUES_FOR_SOURCES, FIND_PROPERTY_URL_VALUES_FOR_SOURCES_DISTINCT);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
            if (property.getObjectTripleCount() != null && property.getObjectTripleCount() == 0) {
                sourceClass.setObjectTripleCount(0L);
            } else {
                SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), COUNT_PROPERTY_URL_VALUES_FOR_SOURCE, COUNT_PROPERTY_URL_VALUES_FOR_SOURCE_DISTINCT);
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long objectTripleCountForSource = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setObjectTripleCount(objectTripleCountForSource);
                } else {
                    schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_URL_VALUES_FOR_SOURCE.name()), CHECK_PROPERTY_URL_VALUES_FOR_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!checkQueryResponse.hasErrors()) {
                        if (checkQueryResponse.getResults().isEmpty()) {
                            sourceClass.setObjectTripleCount(0L);
                        }
                    } else {
                        schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), CHECK_PROPERTY_URL_VALUES_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                    }
                }
            }
        });
    }

    protected boolean determineDataTripleCountForAllPropertySources(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean found = false;
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES, FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES_DISTINCT);
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
            if (property.getDataTripleCount() != null && property.getDataTripleCount() == 0) {
                sourceClass.setDataTripleCount(0L);
            } else {
                SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE, COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE_DISTINCT);
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long dataTripleCountForSource = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    sourceClass.setDataTripleCount(dataTripleCountForSource);
                } else {
                    schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name()), CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse checkQueryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!checkQueryResponse.hasErrors()) {
                        if (checkQueryResponse.getResults().isEmpty()) {
                            sourceClass.setDataTripleCount(0L);
                        }
                    } else {
                        schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                    }
                }
            }
        });
    }

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
                    schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
                        schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
                                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Count information not available for property source and target pair",
                                            property.getPropertyName(), null, checkQuery.name(), checkQueryBuilder.getQueryString()));
                                    property.getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(
                                            sourceClass.getClassName(), targetClass.getClassName(), null, sourceClass.getClassificationProperty(), targetClass.getClassificationProperty()));
                                } else if (checkQueryResponse.hasErrors()) {
                                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Property source and target pair can not be checked. Source and target pair possibility is assumed for property.",
                                            property.getPropertyName(), null, checkQuery.name(), checkQueryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(INFO, null, COUNT_BLANK_INSTANCES_FOR_CLASSES.name(), queryBuilder.getQueryString()));
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
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, clazz.getFullName(), COUNT_BLANK_INSTANCES_FOR_CLASS.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(INFO, null, COUNT_SOURCE_BLANK_VALUES_FOR_PROPERTIES.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(INFO, null, COUNT_TARGET_BLANK_VALUES_FOR_PROPERTIES.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), COUNT_SOURCE_BLANK_VALUES_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), COUNT_TARGET_BLANK_VALUES_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(INFO, null, COUNT_DISTINCT_SUBJECTS_FOR_PROPERTIES.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(INFO, null, COUNT_DISTINCT_OBJECTS_FOR_PROPERTIES.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
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
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), COUNT_DISTINCT_OBJECTS_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), COUNT_DISTINCT_SUBJECTS_FOR_PROPERTY_SOURCE.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), COUNT_DISTINCT_OBJECTS_FOR_PROPERTY_TARGET.name(), queryBuilder.getQueryString()));
            }
        });
    }

    protected void determinePropertyClosedDomains(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedDomains [" + property.getPropertyName() + "]");

        // check if there is any triple with URI subject but without bound class - property level
        if (property.getSourceClasses().isEmpty()) {
            property.setIsClosedDomain((property.getTripleCount() != null && property.getTripleCount() > 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedDomain(Boolean.TRUE);
            for (String classificationProperty : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_DOMAIN_FOR_PROPERTY.name()), FIND_CLOSED_DOMAIN_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_X))) {
                    property.setIsClosedDomain(Boolean.FALSE);
                    break;
                } else if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_CLOSED_DOMAIN_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
                    if (queryResponse.getExecutionTime() > 45) {
                        property.setClosedSourceAssertedSize(-1);
                    }
                }
            }
        }
    }

    protected void determinePropertyClosedRanges(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyClosedRanges [" + property.getPropertyName() + "]");

        // check if there is any triple with URI object but without bound class
        if (property.getTargetClasses().isEmpty()) {
            property.setIsClosedRange((property.getObjectTripleCount() != null && property.getObjectTripleCount() != 0) ? Boolean.FALSE : null);
        } else {
            property.setIsClosedRange(Boolean.TRUE);
            for (String classificationProperty : request.getMainClassificationProperties()) {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_CLOSED_RANGE_FOR_PROPERTY.name()), FIND_CLOSED_RANGE_FOR_PROPERTY)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_Y))) {
                    property.setIsClosedRange(Boolean.FALSE);
                    break;
                } else if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_CLOSED_RANGE_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
                    if (queryResponse.getExecutionTime() > 45) {
                        property.setClosedTargetAssertedSize(-1);
                    }
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
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_Y))) {
                        sourceClass.setIsClosedRange(Boolean.FALSE);
                        break;
                    } else if (queryResponse.hasErrors()) {
                        schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS.name(), queryBuilder.getQueryString()));
                        if (queryResponse.getExecutionTime() > 45) {
                            sourceClass.setClosedTargetAssertedSize(-1);
                        }
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
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, classificationProperty)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                    if (!queryResponse.getResults().isEmpty() && StringUtils.isNotEmpty(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_X))) {
                        targetClass.setIsClosedDomain(Boolean.FALSE);
                        break;
                    } else if (queryResponse.hasErrors()) {
                        schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS.name(), queryBuilder.getQueryString()));
                        if (queryResponse.getExecutionTime() > 45) {
                            targetClass.setClosedSourceAssertedSize(-1);
                        }
                    }
                }
            });
        }
    }

    protected void determinePropertyDataTypes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyDataTypes [" + property.getPropertyName() + "]");
        Long tripleCountBase = null;
        if (request.getSampleLimitForDataTypeCalculation() != null && request.getSampleLimitForDataTypeCalculation() > 0
                && (property.getDataTripleCount() == null || property.getDataTripleCount() < 0 || property.getDataTripleCount() > request.getSampleLimitForDataTypeCalculation())) {
            tripleCountBase = request.getSampleLimitForDataTypeCalculation();
        }
        // find data types
        SparqlQueryBuilder queryBuilder;
        SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT, FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_DISTINCT);
        if (tripleCountBase == null) {
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        } else {
            query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS, FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS_DISTINCT);
            queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString());
        }
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
        }
        boolean hasEmptyDataType = false;
        for (QueryResult queryResult : queryResponse.getResults()) {
            String resultDataType = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(resultDataType)) {
                property.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount, tripleCountBase));
            } else {
                hasEmptyDataType = true;
            }
        }

        // find language tag
        if (hasEmptyDataType) {
            query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_LANG_STRING, FIND_PROPERTY_DATA_TYPE_LANG_STRING_DISTINCT);
            if (tripleCountBase == null) {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            } else {
                query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS, FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS_DISTINCT);
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountBase.toString());
            }
            queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
            }
            if (!queryResponse.getResults().isEmpty()) {
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (tripleCount > 0L) {
                    property.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, tripleCountBase));
                }
            }
        }
    }

    protected void determinePropertySourceDataTypes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceDataTypes [" + property.getPropertyName() + "]");

        Long tripleCountBase = null;
        if (request.getSampleLimitForDataTypeCalculation() != null && request.getSampleLimitForDataTypeCalculation() > 0
                && (property.getDataTripleCount() == null || property.getDataTripleCount() < 0 || property.getDataTripleCount() > request.getSampleLimitForDataTypeCalculation())) {
            tripleCountBase = request.getSampleLimitForDataTypeCalculation();
        }

        Long finalTripleCountBase = tripleCountBase;
        property.getSourceClasses().forEach(sourceClass -> {
            // find data types
            SparqlQueryBuilder queryBuilder;
            SchemaExtractorQueries query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE, FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_DISTINCT);
            if (finalTripleCountBase == null) {
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            } else {
                query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS, FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS_DISTINCT);
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, finalTripleCountBase.toString());
            }
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
            }
            boolean hasEmptyDataType = false;
            for (QueryResult queryResult : queryResponse.getResults()) {
                String resultDataType = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (StringUtils.isNotEmpty(resultDataType)) {
                    sourceClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(SchemaUtil.parseDataType(resultDataType), tripleCount, finalTripleCountBase));
                } else {
                    hasEmptyDataType = true;
                }
            }
            // find language tag
            if (hasEmptyDataType) {
                query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE, FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_DISTINCT);
                if (finalTripleCountBase == null) {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
                } else {
                    query = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS, FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS_DISTINCT);
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(query.name()), query)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, finalTripleCountBase.toString());
                }
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (!queryResponse.getResults().isEmpty()) {
                    Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (tripleCount > 0L) {
                        sourceClass.getDataTypes().add(new SchemaExtractorDataTypeInfo(DATA_TYPE_RDF_LANG_STRING, tripleCount, finalTripleCountBase));
                    }
                } else if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), query.name(), queryBuilder.getQueryString()));
                }
            }
        });
    }

    protected void determineFollowers(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                      @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - determinePropertyFollowers [" + property.getPropertyName() + "]");
        SchemaExtractorQueries mainQuery = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_FOLLOWERS, FIND_PROPERTY_FOLLOWERS_DISTINCT);
        SchemaExtractorQueries mainQueryLimits = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_FOLLOWERS_WITH_LIMITS, FIND_PROPERTY_FOLLOWERS_WITH_LIMITS_DISTINCT);
        Integer hasFollowersOK = executePropertyRelationsQueries(request, schema, properties, property, property.getFollowers(), SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower,
                mainQuery, mainQueryLimits, CHECK_PROPERTY_FOLLOWER_TRIPLES, CHECK_PROPERTY_FOLLOWER, CHECK_PROPERTY_FOLLOWER_TRIPLES_LIMITS);
        property.setHasFollowersOK(hasFollowersOK);
    }

    protected void determineOutgoingProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                               @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - determinePropertyOutgoingProperties [" + property.getPropertyName() + "]");
        SchemaExtractorQueries mainQuery = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_OUTGOING_PROPERTIES, FIND_PROPERTY_OUTGOING_PROPERTIES_DISTINCT);
        SchemaExtractorQueries mainQueryLimits = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS, FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS_DISTINCT);
        Integer hasOutgoingPropertiesOK = executePropertyRelationsQueries(request, schema, properties, property, property.getOutgoingProperties(), SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing,
                mainQuery, mainQueryLimits, CHECK_PROPERTY_OUTGOING_PROPERTY_TRIPLES, CHECK_PROPERTY_OUTGOING_PROPERTY, CHECK_PROPERTY_OUTGOING_PROPERTY_TRIPLES_LIMITS);
        property.setHasOutgoingPropertiesOK(hasOutgoingPropertiesOK);
    }

    protected void determineIncomingProperties(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                               @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        log.info(request.getCorrelationId() + " - determinePropertyIncomingProperties [" + property.getPropertyName() + "]");
        SchemaExtractorQueries mainQuery = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_INCOMING_PROPERTIES, FIND_PROPERTY_INCOMING_PROPERTIES_DISTINCT);
        SchemaExtractorQueries mainQueryLimits = selectQuery(request.getExactCountCalculations(), FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS, FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS_DISTINCT);
        Integer hasIncomingPropertiesOK = executePropertyRelationsQueries(request, schema, properties, property, property.getIncomingProperties(), SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming,
                mainQuery, mainQueryLimits, CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES, CHECK_PROPERTY_INCOMING_PROPERTY, CHECK_PROPERTY_INCOMING_PROPERTY_TRIPLES_LIMITS);
        property.setHasIncomingPropertiesOK(hasIncomingPropertiesOK);
    }

    /**
     * 0 - no or incomplete data
     * 1 - low limit bound
     * 2 - limit bound
     * 3 - likely ok
     * 5 - complete data
     */
    protected Integer executePropertyRelationsQueries(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                      @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                                      @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                                      @Nonnull SchemaExtractorQueries queryWithoutLimit, @Nonnull SchemaExtractorQueries queryWithLimit,
                                                      SchemaExtractorQueries queryCheckCount, SchemaExtractorQueries queryCheck, SchemaExtractorQueries queryCheckCountLimit) {

        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryWithoutLimit.name()), queryWithoutLimit)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        updateValueClause(request, properties, queryBuilder);

        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

        if (isFalse(queryResponse.hasErrors())) {
            updatedRelatedProperties(request, queryResponse, relatedProperties, linkType, null);
            return 5;
        } else {
            if (SchemaExtractorRequestDto.PropertyRelationsCheckMode.none.equals(request.getPropertyPropertyLinkCheckBackupMode())) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryWithoutLimit.name(), queryBuilder.getQueryString()));
                return 0;
            }
            if (SchemaExtractorRequestDto.PropertyRelationsCheckMode.limits.equals(request.getPropertyPropertyLinkCheckBackupMode())) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryWithoutLimit.name(), queryBuilder.getQueryString()));
                return executeLimitQueries(request, schema, properties, property, relatedProperties, linkType, queryWithLimit);
            }
            if (SchemaExtractorRequestDto.PropertyRelationsCheckMode.limitsPlusSimple.equals(request.getPropertyPropertyLinkCheckBackupMode())) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryWithoutLimit.name(), queryBuilder.getQueryString()));
                return executeLimitPlusSimpleQueries(request, schema, properties, property, relatedProperties, linkType, queryWithLimit, queryCheck);
            }
            if (SchemaExtractorRequestDto.PropertyRelationsCheckMode.detailsBase.equals(request.getPropertyPropertyLinkCheckBackupMode())) {
                schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), queryWithoutLimit.name(), queryBuilder.getQueryString()));
                return executeDetailsQueriesOption1(request, schema, properties, property, relatedProperties, linkType, queryCheckCount, queryCheck, queryCheckCountLimit);
            }
            if (SchemaExtractorRequestDto.PropertyRelationsCheckMode.details.equals(request.getPropertyPropertyLinkCheckBackupMode())) {
                schema.getMessages().add(new SchemaExtractorMessage(INFO, property.getPropertyName(), queryWithoutLimit.name(), queryBuilder.getQueryString()));
                return executeDetailsQueriesOption2(request, schema, properties, property, relatedProperties, linkType, queryCheckCount, queryCheck, queryCheckCountLimit);
            }
        }

        return 0;
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

    private void updatedRelatedProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull QueryResponse queryResponse,
                                          @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties, @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                          @Nullable Long tripleCountBase) {
        for (QueryResult queryResult : queryResponse.getResults()) {
            String otherProperty = queryResult.getValue(SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER);
            Long tripleCount = SchemaUtil.getLongValueFromString(queryResult.getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
            if (StringUtils.isNotEmpty(otherProperty) && isNotExcludedResource(otherProperty, request.getExcludedNamespaces()) && tripleCount > 0) {
                relatedProperties.add(new SchemaExtractorPropertyRelatedPropertyInfo(otherProperty, tripleCount, tripleCountBase, linkType));
            }
        }
    }

    private Integer executeLimitQueries(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                        @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                        @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                        @Nonnull SchemaExtractorQueries queryWithLimit) {
        boolean retry = true;
        Long tripleCountLimit = null;
        SparqlQueryBuilder queryBuilder = null;

        while (isTrue(retry)) {

            Long finalTripleCountLimit = tripleCountLimit;
            Long newLimit = limitsLarge.stream()
                    .filter(limit -> (finalTripleCountLimit == null || limit < finalTripleCountLimit) && (property.getTripleCount() == null || limit <= property.getTripleCount())).findFirst().orElse(null);
            if (newLimit != null) {
                tripleCountLimit = newLimit;
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryWithLimit.name()), queryWithLimit)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountLimit.toString());
                updateValueClause(request, properties, queryBuilder);
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (isFalse(queryResponse.hasErrors())) {
                    updatedRelatedProperties(request, queryResponse, relatedProperties, linkType, tripleCountLimit);
                    if (tripleCountLimit >= 10000L || (property.getTripleCount() != null && tripleCountLimit >= property.getTripleCount() * 0.1)) {
                        return 2;
                    }
                    return 1;
                }
            } else {
                retry = false;
            }
        }
        schema.getMessages().add(new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryWithLimit.name(),
                (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
        return 0;
    }

    private Integer executeLimitPlusSimpleQueries(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                  @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                                  @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                                  @Nonnull SchemaExtractorQueries queryWithLimit, @Nonnull SchemaExtractorQueries queryCheck) {

        Integer resultQuality = executeLimitQueries(request, schema, properties, property, relatedProperties, linkType, queryWithLimit);

        List<String> processedProperties = relatedProperties.stream().map(SchemaExtractorPropertyRelatedPropertyInfo::getPropertyName)
                .collect(Collectors.toList());

        // find triple count base from the regular limit queries
        Long tripleCountBase = null;
        SchemaExtractorPropertyRelatedPropertyInfo exampleProperty = relatedProperties.stream().findFirst().orElse(null);
        if (exampleProperty != null && exampleProperty.getTripleCountBase() != null) {
            tripleCountBase = exampleProperty.getTripleCountBase();
        }

        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {
            SchemaExtractorPropertyNodeInfo property2 = entry.getValue();

            // Step 0: Skip current property
            if (property2.getPropertyName().equals(property.getPropertyName())) continue;

            // Step 0: skip data type properties for incoming relations
            if (SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming.equals(linkType) && isFalse(property2.getIsObjectProperty())) {
                continue;
            }

            // Step 1: Skip properties which were already processed in regular LIMIT queries
            if (processedProperties.contains(property2.getPropertyName())) {
                continue;
            }

            // Step 2: Skip property2 if it was already processed and relationship with property was already identified (incoming/outgoing)
            SchemaExtractorPropertyRelatedPropertyInfo potentialCandidate = connectionFound(property, property2, linkType);
            if (potentialCandidate != null) {
                if (potentialCandidate.getPropertyName() != null) {
                    relatedProperties.add(potentialCandidate);
                }
                continue;
            }

            // Step 3: Execute queryCheck (lightweight existence check)
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheck.name()), queryCheck)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, property2.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (!queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                // No data exists for this pair — skip entirely
                continue;
            }

            // Step 4: Data exists — add candidate
            if (!queryResponse.hasErrors()) {
                SchemaExtractorPropertyRelatedPropertyInfo candidate = new SchemaExtractorPropertyRelatedPropertyInfo(property2.getPropertyName(), null, null, linkType);
                if (tripleCountBase != null) {
                    candidate.setTripleCount(0L);
                    candidate.setTripleCountBase(tripleCountBase);
                }
                relatedProperties.add(candidate);
            } else {
                // queryCheck itself failed with errors
                SchemaExtractorMessage checkQueryError = new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryCheck.name(), queryBuilder.getQueryString());
                schema.getMessages().add(checkQueryError);
            }
        }

        return resultQuality;
    }

    private Integer executeDetailsQueriesOption1(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                 @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                                 @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                                 @Nonnull SchemaExtractorQueries queryCheckCount, @Nonnull SchemaExtractorQueries queryCheck, @Nonnull SchemaExtractorQueries queryCheckCountLimit) {

        boolean completeData = true;
        boolean completeDataWithCheck = true;
        Integer finalResultWithLimits = null;

        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {
            SchemaExtractorPropertyNodeInfo property2 = entry.getValue();

            if (property2.getPropertyName().equals(property.getPropertyName())) continue;

            // skip data type properties for incoming relations
            if (SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming.equals(linkType) && isFalse(property2.getIsObjectProperty())) {
                continue;
            }

            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheckCount.name()), queryCheckCount)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, property2.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                if (tripleCount > 0) {
                    relatedProperties.add(new SchemaExtractorPropertyRelatedPropertyInfo(property2.getPropertyName(), tripleCount, null, linkType));
                }
            } else {
                SchemaExtractorMessage queryCheckCountError = new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryCheckCount.name(), queryBuilder.getQueryString());

                SchemaExtractorPropertyRelatedPropertyInfo potentialCandidate = connectionFound(property, property2, linkType);
                if (potentialCandidate == null) {
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheck.name()), queryCheck)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, property2.getPropertyName(), false);
                    queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                    if (!queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                        continue;
                    }
                } else if (potentialCandidate.getPropertyName() == null) {
                    // No data exists for this pair — skip entirely
                    continue;
                }

                schema.getMessages().add(queryCheckCountError);
                completeData = false;

                SchemaExtractorPropertyRelatedPropertyInfo candidate = new SchemaExtractorPropertyRelatedPropertyInfo(property2.getPropertyName(), null, null, linkType);

                if (!queryResponse.hasErrors() || potentialCandidate != null) {
                    executeDetailsQueryBlockA(request, schema, property, candidate, linkType, queryCheckCountLimit);
                    relatedProperties.add(candidate);
                } else {
                    completeDataWithCheck = false;
                    Integer resultWithLimits = executeDetailsQueryBlockB(request, schema, property, candidate, linkType, queryCheckCountLimit);
                    if (candidate.getTripleCount() != null && candidate.getTripleCount() > 0) {
                        relatedProperties.add(candidate);
                    }
                    if (finalResultWithLimits == null || resultWithLimits < finalResultWithLimits) {
                        finalResultWithLimits = resultWithLimits;
                    }
                }
            }
        }
        if (completeData) return 5;
        if (completeDataWithCheck) return 3;
        return finalResultWithLimits;
    }

    private Integer executeDetailsQueriesOption2(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                 @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaExtractorPropertyRelatedPropertyInfo> relatedProperties,
                                                 @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                                 @Nonnull SchemaExtractorQueries queryCheckCount, @Nonnull SchemaExtractorQueries queryCheck, @Nonnull SchemaExtractorQueries queryCheckCountLimit) {

        boolean completeData = true;
        boolean completeDataWithCheck = true;
        Integer finalResultWithLimits = null;

        for (Map.Entry<String, SchemaExtractorPropertyNodeInfo> entry : properties.entrySet()) {
            SchemaExtractorPropertyNodeInfo property2 = entry.getValue();

            if (property2.getPropertyName().equals(property.getPropertyName())) continue;

            // skip data type properties for incoming relations
            if (SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming.equals(linkType) && isFalse(property2.getIsObjectProperty())) {
                continue;
            }

            SchemaExtractorPropertyRelatedPropertyInfo potentialCandidate = connectionFound(property, property2, linkType);
            QueryResponse queryResponse = null;
            SparqlQueryBuilder queryBuilder = null;

            if (potentialCandidate == null) {
                // Step 1: Execute queryCheck first (lightweight existence check)
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheck.name()), queryCheck)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, property2.getPropertyName(), false);
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (!queryResponse.hasErrors() && queryResponse.getResults().isEmpty()) {
                    // No data exists for this pair — skip entirely
                    continue;
                }
            } else if (potentialCandidate.getPropertyName() == null) {
                // No data exists for this pair — skip entirely
                continue;
            }

            if ((queryResponse != null && !queryResponse.hasErrors()) || potentialCandidate != null) {
                // Step 2: Data exists — proceed with queryCheckCount to get the exact count
                SparqlQueryBuilder countQueryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheckCount.name()), queryCheckCount)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, property2.getPropertyName(), false);
                QueryResponse countQueryResponse = sparqlEndpointProcessor.read(request, countQueryBuilder);

                if (!countQueryResponse.hasErrors() && !countQueryResponse.getResults().isEmpty() && countQueryResponse.getResults().get(0) != null) {
                    Long tripleCount = SchemaUtil.getLongValueFromString(countQueryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    if (tripleCount > 0) {
                        relatedProperties.add(new SchemaExtractorPropertyRelatedPropertyInfo(property2.getPropertyName(), tripleCount, null, linkType));
                    }
                } else {
                    // queryCheckCount failed but queryCheck confirmed data exists — fall back to limit query (block A)
                    SchemaExtractorMessage queryCheckCountError = new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryCheckCount.name(), countQueryBuilder.getQueryString());
                    schema.getMessages().add(queryCheckCountError);
                    completeData = false;

                    SchemaExtractorPropertyRelatedPropertyInfo candidate = new SchemaExtractorPropertyRelatedPropertyInfo(property2.getPropertyName(), null, null, linkType);
                    executeDetailsQueryBlockA(request, schema, property, candidate, linkType, queryCheckCountLimit);
                    relatedProperties.add(candidate);
                }
            } else {
                // queryCheck itself failed with errors — fall back to verify if properties are not related (block B)
                SchemaExtractorMessage checkQueryError = new SchemaExtractorMessage(WARNING, property.getPropertyName(), queryCheck.name(), queryBuilder.getQueryString());
                schema.getMessages().add(checkQueryError);
                completeData = false;
                completeDataWithCheck = false;

                SchemaExtractorPropertyRelatedPropertyInfo candidate = new SchemaExtractorPropertyRelatedPropertyInfo(property2.getPropertyName(), null, null, linkType);
                Integer resultWithLimits = executeDetailsQueryBlockB(request, schema, property, candidate, linkType, queryCheckCountLimit);
                if (candidate.getTripleCount() != null && candidate.getTripleCount() > 0) {
                    relatedProperties.add(candidate);
                }
                if (finalResultWithLimits == null || resultWithLimits < finalResultWithLimits) {
                    finalResultWithLimits = resultWithLimits;
                }
            }
        }
        if (completeData) return 5;
        if (completeDataWithCheck) return 3;
        return finalResultWithLimits;
    }

    // Return candidate object if the connection was found
    // Return candidate object with empty property name if the connection was not found and confirmed
    // Return null when connection was not identified yet
    private SchemaExtractorPropertyRelatedPropertyInfo connectionFound(@Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorPropertyNodeInfo property2,
                                                                       @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType) {

        if (SchemaExtractorPropertyRelatedPropertyInfo.LinkType.follower.equals(linkType)) {
            return null;
        }

        List<SchemaExtractorPropertyRelatedPropertyInfo> property2Relations = new ArrayList<>();
        Integer property2RelationQuality = null;
        if (SchemaExtractorPropertyRelatedPropertyInfo.LinkType.incoming.equals(linkType)) {
            property2Relations = property2.getIncomingProperties();
            property2RelationQuality = property2.getHasIncomingPropertiesOK();
        } else if (SchemaExtractorPropertyRelatedPropertyInfo.LinkType.outgoing.equals(linkType)) {
            property2Relations = property2.getOutgoingProperties();
            property2RelationQuality = property2.getHasOutgoingPropertiesOK();
        }
        boolean connectionFound = false;
        for (SchemaExtractorPropertyRelatedPropertyInfo p : property2Relations) {
            if (p.getPropertyName().equals(property.getPropertyName())) {
                connectionFound = true;
                break;
            }
        }
        if (connectionFound) {
            SchemaExtractorPropertyRelatedPropertyInfo candidate = new SchemaExtractorPropertyRelatedPropertyInfo(
                    property2.getPropertyName(), null, null, linkType);
            return candidate;
        }
        if (property2RelationQuality != null && property2RelationQuality.equals(5)) {
            return new SchemaExtractorPropertyRelatedPropertyInfo(null, null, null, null);
        }
        return null;
    }

    private boolean executeDetailsQueryBlockA(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property,
                                              @Nonnull SchemaExtractorPropertyRelatedPropertyInfo candidate, @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                              @Nonnull SchemaExtractorQueries queryCheckCountLimit) {

        boolean retry = true;
        Long tripleCountLimit = null;
        SparqlQueryBuilder queryBuilder = null;
        List<Long> sampleLimitsReversed = Lists.reverse(limitsLarge);

        while (isTrue(retry)) {

            Long finalTripleCountLimit = tripleCountLimit;
            Long newLimit = sampleLimitsReversed.stream()
                    .filter(limit -> (finalTripleCountLimit == null || limit > finalTripleCountLimit) && (property.getTripleCount() == null || limit <= property.getTripleCount())).findFirst().orElse(null);
            if (newLimit != null) {
                tripleCountLimit = newLimit;
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheckCountLimit.name()), queryCheckCountLimit)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, candidate.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountLimit.toString());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    candidate.setTripleCount(tripleCount);
                    candidate.setTripleCountBase(tripleCountLimit);
                } else {
                    retry = false;
                }
            } else {
                retry = false;
            }
        }
        if (candidate.getTripleCount() != null) {
            if (candidate.getTripleCount() == 0) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Property " + candidate.getLinkType().name() + " statistics not available",
                        property.getPropertyName(), candidate.getPropertyName(), queryCheckCountLimit.name(),
                        (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
                return false;
            } else {
                return true;
            }
        } else {
            retry = true;
            while (isTrue(retry)) {
                Long finalTripleCountLimit = tripleCountLimit;
                Long newLimit = sampleLimitsSmall.stream()
                        .filter(limit -> (finalTripleCountLimit == null || limit < finalTripleCountLimit) && (property.getTripleCount() == null || limit <= property.getTripleCount())).findFirst().orElse(null);
                if (newLimit != null) {
                    tripleCountLimit = newLimit;
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheckCountLimit.name()), queryCheckCountLimit)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, candidate.getPropertyName(), false)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountLimit.toString());
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                    if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                        Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        candidate.setTripleCount(tripleCount);
                        candidate.setTripleCountBase(tripleCountLimit);
                        retry = false;
                    }
                } else {
                    retry = false;
                }
            }
            if (candidate.getTripleCount() == null || candidate.getTripleCount() == 0) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Property " + candidate.getLinkType().name() + " statistics not available",
                        property.getPropertyName(), candidate.getPropertyName(), queryCheckCountLimit.name(),
                        (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
                return false;
            } else {
                return true;
            }
        }
    }

    private Integer executeDetailsQueryBlockB(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property,
                                              @Nonnull SchemaExtractorPropertyRelatedPropertyInfo candidate, @Nonnull SchemaExtractorPropertyRelatedPropertyInfo.LinkType linkType,
                                              @Nonnull SchemaExtractorQueries queryCheckCountLimit) {

        boolean retry = true;
        Long tripleCountLimit = null;
        SparqlQueryBuilder queryBuilder = null;
        List<Long> sampleLimitsReversed = Lists.reverse(limitsLarge);

        while (isTrue(retry)) {

            Long finalTripleCountLimit = tripleCountLimit;
            Long newLimit = sampleLimitsReversed.stream()
                    .filter(limit -> (finalTripleCountLimit == null || limit > finalTripleCountLimit) && (property.getTripleCount() == null || limit <= property.getTripleCount())).findFirst().orElse(null);
            if (newLimit != null) {
                tripleCountLimit = newLimit;
                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheckCountLimit.name()), queryCheckCountLimit)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, candidate.getPropertyName(), false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountLimit.toString());
                QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                    Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                    candidate.setTripleCount(tripleCount);
                    candidate.setTripleCountBase(tripleCountLimit);
                } else {
                    retry = false;
                }
            } else {
                retry = false;
            }
        }
        if (candidate.getTripleCount() != null) {
            if (candidate.getTripleCount() == 0) {
                schema.getMessages().add(new SchemaExtractorMessage(INFO, "Property " + candidate.getLinkType().name() + " non-existence confirmed",
                        property.getPropertyName(), candidate.getPropertyName(), queryCheckCountLimit.name(),
                        (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
            } else {
                schema.getMessages().add(new SchemaExtractorMessage(INFO, "Property " + candidate.getLinkType().name() + " information recovered",
                        property.getPropertyName(), candidate.getPropertyName(), queryCheckCountLimit.name(),
                        (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
            }
        } else {
            retry = true;
            while (isTrue(retry)) {
                Long finalTripleCountLimit = tripleCountLimit;
                Long newLimit = sampleLimitsSmall.stream()
                        .filter(limit -> (finalTripleCountLimit == null || limit < finalTripleCountLimit) && (property.getTripleCount() == null || limit <= property.getTripleCount())).findFirst().orElse(null);
                if (newLimit != null) {
                    tripleCountLimit = newLimit;
                    queryBuilder = new SparqlQueryBuilder(request.getQueries().get(queryCheckCountLimit.name()), queryCheckCountLimit)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL, candidate.getPropertyName(), false)
                            .withContextParam(SPARQL_QUERY_BINDING_NAME_LIMIT, tripleCountLimit.toString());
                    QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                    if (!queryResponse.hasErrors() && !queryResponse.getResults().isEmpty() && queryResponse.getResults().get(0) != null) {
                        Long tripleCount = SchemaUtil.getLongValueFromString(queryResponse.getResults().get(0).getValue(SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT));
                        candidate.setTripleCount(tripleCount);
                        candidate.setTripleCountBase(tripleCountLimit);
                        retry = false;
                    }
                } else {
                    retry = false;
                }
            }

            if (candidate.getTripleCount() != null) {
                if (candidate.getTripleCount() == 0) {
                    schema.getMessages().add(new SchemaExtractorMessage(INFO, "Property " + candidate.getLinkType().name() + " non-existence confirmed",
                            property.getPropertyName(), candidate.getPropertyName(), queryCheckCountLimit.name(),
                            (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
                } else {
                    schema.getMessages().add(new SchemaExtractorMessage(INFO, "Property " + candidate.getLinkType().name() + " information recovered",
                            property.getPropertyName(), candidate.getPropertyName(), queryCheckCountLimit.name(),
                            (queryBuilder != null ? queryBuilder.getQueryString() : "No query - no applicable limit value was found")));
                }
            }
        }

        if (candidate.getTripleCount() == null) {
            return 0;
        }
        if (candidate.getTripleCountBase() >= 10000L || (property.getTripleCount() != null && candidate.getTripleCountBase() >= property.getTripleCount() * 0.1)) {
            return 2;
        }
        return 1;
    }

    protected void determinePropertySourceMinCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property,
                                                         @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMinCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MIN_CARDINALITY.name()), FIND_PROPERTY_MIN_CARDINALITY)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (!queryResponse.getResults().isEmpty()) {
                sourceClass.setMinCardinality(DEFAULT_MIN_CARDINALITY);
            } else {
                sourceClass.setMinCardinality(1);
            }
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_PROPERTY_MIN_CARDINALITY.name(), queryBuilder.getQueryString()));
                sourceClass.setMinCardinality(0);
                if (queryResponse.getExecutionTime() > 45) {
                    sourceClass.setMinCardinalityAssertionSize(-1);
                }
            }
        });
    }

    protected void determinePropertyMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo
            property, @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY.name()), FIND_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.getResults().isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_PROPERTY_MAX_CARDINALITY.name(), queryBuilder.getQueryString()));
            property.setMaxCardinality(-1);
            if (queryResponse.getExecutionTime() > 45) {
                property.setMaxCardinalityAssertionSize(-1);
            }
        }
    }

    protected void determinePropertySourceMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                         int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertySourceMaxCardinality [" + property.getPropertyName() + "]");
        property.getSourceClasses().forEach(sourceClass -> {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE.name()), FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY, sourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            if (queryResponse.getResults().isEmpty()) {
                sourceClass.setMaxCardinality(1);
            } else {
                sourceClass.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
                if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                    sourceClass.setMaxCardinality(-1);
                    if (queryResponse.getExecutionTime() > 45) {
                        sourceClass.setMaxCardinalityAssertionSize(-1);
                    }
                }
            }
        });
    }

    protected void determinePropertyInverseMaxCardinality(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request,
                                                          int totalCountOfProperties) {
        log.info(request.getCorrelationId() + " - determinePropertyInverseMaxCardinality [" + property.getPropertyName() + "]");
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name()), FIND_INVERSE_PROPERTY_MAX_CARDINALITY)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, property.getPropertyName(), false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.getResults().isEmpty()) {
            property.setMaxInverseCardinality(1);
            return;
        }
        property.setMaxInverseCardinality(DEFAULT_MAX_CARDINALITY);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_INVERSE_PROPERTY_MAX_CARDINALITY.name(), queryBuilder.getQueryString()));
            property.setMaxInverseCardinality(-1);
            if (queryResponse.getExecutionTime() > 45) {
                property.setMaxInverseCardinalityAssertionSize(-1);
            }
        }
    }

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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET.name(), queryBuilder.getQueryString()));
                targetClass.setMaxInverseCardinality(-1);
                if (queryResponse.getExecutionTime() > 45) {
                    targetClass.setMaxInverseCardinalityAssertionSize(-1);
                }
            }
            targetClass.setMaxInverseCardinality(queryResponse.getResults().isEmpty() ? 1 : DEFAULT_MAX_CARDINALITY);
        });
    }

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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING_LOW, property.getPropertyName(), FIND_INVERSE_PROPERTY_MIN_CARDINALITY.name(), queryBuilder.getQueryString()));
                targetClass.setMinInverseCardinality(0);
                if (queryResponse.getExecutionTime() > 45) {
                    targetClass.setMinInverseCardinalityAssertionSize(-1);
                }
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
                    totalCountOfProperties);
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
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_DOMAIN_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
            if (queryResponse.getExecutionTime() > 45) {
                potentialDomain.setSourcePrincipalAssertedSize(-1);
            }
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
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_RANGE_FOR_PROPERTY.name(), queryBuilder.getQueryString()));
            if (queryResponse.getExecutionTime() > 45) {
                potentialRange.setTargetPrincipalAssertedSize(-1);
            }
        }
        isRangeClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isRangeClass;
    }

    protected boolean checkRangeForSource(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass,
                                          @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialRange,
                                          @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isRangeClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_RANGE_FOR_PAIR_SOURCE.name()), CHECK_RANGE_FOR_PAIR_SOURCE)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, potentialRange.getClassName(), potentialRange.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, potentialRange.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_RANGE_FOR_PAIR_SOURCE.name(), queryBuilder.getQueryString()));
            if (queryResponse.getExecutionTime() > 45) {
                potentialRange.setTargetPrincipalAssertedSize(-1);
            }
        }
        isRangeClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isRangeClass;
    }

    protected boolean checkDomainForTarget(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass,
                                           @Nonnull SchemaExtractorPropertyLinkedClassInfo potentialDomain,
                                           @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {
        boolean isDomainClass;
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_DOMAIN_FOR_PAIR_TARGET.name()), CHECK_DOMAIN_FOR_PAIR_TARGET)
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, potentialDomain.getClassName(), potentialDomain.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, potentialDomain.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false);
        QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
        if (queryResponse.hasErrors()) {
            schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_DOMAIN_FOR_PAIR_TARGET.name(), queryBuilder.getQueryString()));
            if (queryResponse.getExecutionTime() > 45) {
                potentialDomain.setSourcePrincipalAssertedSize(-1);
            }
        }
        isDomainClass = !queryResponse.hasErrors() && queryResponse.getResults().isEmpty();
        return isDomainClass;
    }

    protected void determineImportanceIndexes(@Nonnull Schema schema, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull List<SchemaClass> classes,
                                              @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                              @Nonnull SchemaExtractorRequestDto request, int totalCountOfProperties) {

        if (isNotTrue(property.getHasDomain()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForSourceClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalSourceClasses = determinePrincipalClasses(schema, property,
                    buildAndSortPropertyLinkedClasses(property.getSourceClasses(), classes), classes, graphOfClasses, request, LinkedClassType.SOURCE, null, null,
                    totalCountOfProperties);
            mapPrincipalClasses(property.getSourceClasses(), principalSourceClasses);
        }

        if (isNotTrue(property.getHasRange()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
            log.info(request.getCorrelationId() + " - determineImportanceIndexesForTargetClasses [" + property.getPropertyName() + "]");
            List<SchemaExtractorPropertyLinkedClassInfo> principalTargetClasses = determinePrincipalClasses(schema, property,
                    buildAndSortPropertyLinkedClasses(property.getTargetClasses(), classes), classes, graphOfClasses, request, LinkedClassType.TARGET, null, null,
                    totalCountOfProperties);
            mapPrincipalClasses(property.getTargetClasses(), principalTargetClasses);
        }

        log.info(request.getCorrelationId() + " - determineImportanceIndexesForClassPairSource [" + property.getPropertyName() + "]");
        // set pair target important indexes
        property.getSourceClasses().forEach(sourceClass -> {
            if (isNotTrue(sourceClass.getHasRangeInClassPair()) || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
                List<SchemaExtractorSourceTargetInfo> pairsForSpecificSource = findPairsWithSpecificSource(property.getSourceAndTargetPairs(), sourceClass.getClassName());
                List<SchemaExtractorPropertyLinkedClassInfo> principalPairTargets = determinePrincipalClasses(schema, property,
                        buildAndSortPropertyPairTargets(pairsForSpecificSource, classes), classes, graphOfClasses, request, LinkedClassType.PAIR_SOURCE, sourceClass, null,
                        totalCountOfProperties);
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
                        totalCountOfProperties);
                pairsForSpecificTarget.forEach(linkedPair -> {
                    SchemaExtractorPropertyLinkedClassInfo pairWithIndex = principalPairSources.stream()
                            .filter(pairTarget -> pairTarget.getClassName().equals(linkedPair.getSourceClass())).findFirst().orElse(null);
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

                // if the selected important indexes mode is Basic/ClassCoverage then do not check detailed combinations
                if (SchemaExtractorRequestDto.ImportantIndexesMode.basic.equals(request.getCalculateImportanceIndexes())
                        || SchemaExtractorRequestDto.ImportantIndexesMode.classCoverage.equals(request.getCalculateImportanceIndexes())) {
                    currentClass.setImportanceIndex(index++);
                    importantClasses.add(currentClass.getClassName());
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

    protected boolean checkNewPrincipalSourceClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass,
                                                   @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                   int totalCountOfProperties) {
        boolean isPrincipalSource = false;

        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE.name()), CHECK_PRINCIPAL_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalSource = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalSource)) break;
        }
        return isPrincipalSource;
    }

    protected boolean checkNewPrincipalTargetClass(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass,
                                                   @Nonnull List<String> existingClasses,
                                                   @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequestDto request,
                                                   int totalCountOfProperties) {
        boolean isPrincipalTarget = false;
        // check the first 5 classes with the highest triple count
        List<String> classesForCustomFilter = existingClasses.size() <= 5 ? Collections.unmodifiableList(existingClasses) : Collections.unmodifiableList(existingClasses.subList(0, 5));
        for (String classificationProperty : request.getMainClassificationProperties()) {
            SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_TARGET.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET.name()), CHECK_PRINCIPAL_TARGET)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_TARGET.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalTarget = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    protected boolean checkNewPrincipalTargetClassForSource(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo sourceClass,
                                                            @Nonnull SchemaExtractorPropertyLinkedClassInfo newTargetClass,
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
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name()), CHECK_PRINCIPAL_TARGET_FOR_SOURCE)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, sourceClass.getClassName(), sourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, newTargetClass.getClassName(), newTargetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, sourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, newTargetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

                if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_TARGET_FOR_SOURCE.name(), queryBuilder.getQueryString()));
                }
            }
            isPrincipalTarget = queryResponse.hasErrors() || !queryResponse.getResults().isEmpty();
            if (isTrue(isPrincipalTarget)) break;
        }
        return isPrincipalTarget;
    }

    protected boolean checkNewPrincipalSourceClassForTarget(@Nonnull Schema schema, @Nonnull String propertyName, @Nonnull SchemaExtractorClassNodeInfo targetClass,
                                                            @Nonnull SchemaExtractorPropertyLinkedClassInfo newSourceClass,
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
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                    .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(classesForCustomFilter, classes));
            QueryResponse queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);

            // if the query fails, check additionally only the first biggest class from the list
            if (queryResponse.hasErrors()) {
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name(), queryBuilder.getQueryString()));

                queryBuilder = new SparqlQueryBuilder(request.getQueries().get(CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name()), CHECK_PRINCIPAL_SOURCE_FOR_TARGET)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL, newSourceClass.getClassName(), newSourceClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL, targetClass.getClassName(), targetClass.getIsLiteral())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE, newSourceClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET, targetClass.getClassificationProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER, classificationProperty)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL, propertyName, false)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildCustomFilterToCheckPrincipalClass(Collections.unmodifiableList(existingClasses.subList(0, 1)), classes));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
                if (queryResponse.hasErrors()) {
                    schema.getMessages().add(new SchemaExtractorMessage(WARNING, propertyName, CHECK_PRINCIPAL_SOURCE_FOR_TARGET.name(), queryBuilder.getQueryString()));
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
            property.setBlankNodeObjects(propertyData.getBlankNodeObjects());
            property.setBlankNodeSubjects(propertyData.getBlankNodeSubjects());
            property.setDistinctSubjectsCount(propertyData.getDistinctSubjectsCount());
            property.setDistinctObjectsCount(propertyData.getDistinctObjectsCount());
            property.setClosedDomain(propertyData.getIsClosedDomain());
            property.setClosedSourceAssertedSize(propertyData.getClosedSourceAssertedSize());
            property.setClosedRange(propertyData.getIsClosedRange());
            property.setClosedTargetAssertedSize(propertyData.getClosedTargetAssertedSize());
            property.setSourceClassesOK(propertyData.getSourceClassesOK());
            property.setTargetClassesOK(propertyData.getTargetClassesOK());
            property.setHasFollowersOK(propertyData.getHasFollowersOK());
            property.setHasOutgoingPropertiesOK(propertyData.getHasOutgoingPropertiesOK());
            property.setHasIncomingPropertiesOK(propertyData.getHasIncomingPropertiesOK());
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
                        .map(internalDto -> new SchemaPropertyLinkedClassDetails(
                                internalDto.getClassName(),
                                internalDto.getTripleCount(), internalDto.getTripleCountBase(), internalDto.getDataTripleCount(), internalDto.getObjectTripleCount(),
                                internalDto.getDistinctSubjectsCount(), internalDto.getDistinctObjectsCount(),
                                internalDto.getIsClosedDomain(), internalDto.getClosedSourceAssertedSize(), internalDto.getIsClosedRange(), internalDto.getClosedTargetAssertedSize(),
                                internalDto.getIsPrincipal(), internalDto.getPrincipalAssertedSize(),
                                internalDto.getMinCardinality(), internalDto.getMinCardinalityAssertionSize(), internalDto.getMaxCardinality(), internalDto.getMaxCardinalityAssertionSize(),
                                internalDto.getMinInverseCardinality(), internalDto.getMinInverseCardinalityAssertionSize(), internalDto.getMaxInverseCardinality(), internalDto.getMaxInverseCardinalityAssertionSize(),
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
                            schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Check class intersection failed. Assume no intersection",
                                    classA.getFullName(), null, CHECK_CLASS_INTERSECTION_PLAIN.name(), queryBuilder.getQueryString()));
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
                                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Check class intersection: Unable to obtain statistics",
                                        classA.getFullName(), null, checkQuery.name(), queryBuilder.getQueryString()));
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
                schema.getMessages().add(new SchemaExtractorMessage(WARNING, "Check superclass failed. Assume no superclass relation.",
                        currentClass.getFullName(), null, CHECK_SUPERCLASS.name(), queryBuilder.getQueryString()));
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
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE1, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE2, label.getLabelProperty());
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
            } else {
                SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(request.getQueries().get(FIND_LABEL_WITH_LANG.name()), FIND_LABEL_WITH_LANG)
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE1, element.getFullName())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_RESOURCE2, label.getLabelProperty())
                        .withContextParam(SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER, buildFilterWithLanguages(label.getLanguages()));
                queryResponse = sparqlEndpointProcessor.read(request, queryBuilder);
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

    private boolean calculateTargetClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull SchemaExtractorPropertyNodeInfo property) {
        if (property.getObjectTripleCount() != null && property.getObjectTripleCount() != 0)
            return true;
        if (property.getTripleCount() != null && property.getTripleCount().equals(property.getDataTripleCount()))
            return false;
        if (property.getObjectTripleCount() == null && request.getCrossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure())
            return true;
        return false;
    }

}

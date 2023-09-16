package lv.lumii.obis.schema.services.extractor.v1;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v1.*;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.extractor.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.services.extractor.v1.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
@Service
public abstract class SchemaExtractor {

    private static Comparator<String> nullSafeStringComparator = Comparator.nullsLast(String::compareToIgnoreCase);

    @Autowired
    @Setter
    @Getter
    protected SparqlEndpointProcessor sparqlEndpointProcessor;

    // =====================================================================================
    // PUBLIC methods
    // =====================================================================================

    @Nonnull
    public Schema extractSchema(@Nonnull SchemaExtractorRequestDto request) {
        Schema schema = initializeSchema(request);
        buildClasses(request, schema);
        buildDataTypeProperties(request, schema);
        buildObjectTypeProperties(request, schema);
        buildNamespaceMap(schema);
        return schema;
    }

    @Nonnull
    public Schema extractClasses(@Nonnull SchemaExtractorRequestDto request) {
        Schema schema = initializeSchema(request);
        buildClasses(request, schema);
        buildNamespaceMap(schema);
        return schema;
    }

    // =====================================================================================
    // PRIVATE methods
    // =====================================================================================

    protected Schema initializeSchema(@Nonnull SchemaExtractorRequestDto request) {
        Schema schema = new Schema();
        schema.setName((StringUtils.isNotEmpty(request.getGraphName())) ? request.getGraphName() + "_Schema" : "Schema");
        buildExtractionProperties(request, schema);
        return schema;
    }

    protected void buildClasses(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        // find all classes
        log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLASSES_WITH_INSTANCE_COUNT);
        List<SchemaClass> classes = processClasses(queryResults, request);
        log.info(request.getCorrelationId() + String.format(" - found total %d classes", classes.size()));
        schema.setClasses(classes);
        Map<String, SchemaExtractorClassNodeInfo> graphOfClasses = buildGraphOfClasses(classes);
        queryResults.clear();

        // find intersection classes
        log.info(request.getCorrelationId() + " - findIntersectionClassesAndUpdateClassNeighbors");
        findIntersectionClassesAndUpdateClassNeighbors(classes, graphOfClasses, request);

        // sort classes by neighbors and instances count (ascending)
        log.info(request.getCorrelationId() + " - sortClassesByNeighborAndInstanceCountAscending");
        graphOfClasses = sortGraphOfClassesByNeighbors(graphOfClasses);

        // find superclasses
        log.info(request.getCorrelationId() + " - calculateSuperclassRelations");
        processSuperclasses(graphOfClasses, classes, request);

        // validate and update classes for multiple inheritance cases
        log.info(request.getCorrelationId() + " - updateMultipleInheritanceSuperclasses");
        updateMultipleInheritanceSuperclasses(graphOfClasses, classes, request);
    }

    protected abstract void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes,
                                                                           @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                                           @Nonnull SchemaExtractorRequestDto request);

    protected void buildDataTypeProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        List<SchemaClass> classes = schema.getClasses();

        // find all data type properties and domain class instances count
        log.info(request.getCorrelationId() + " - findAllDataTypeProperties");
        Map<String, SchemaExtractorPropertyNodeInfo> properties = findAllDataTypeProperties(classes, request);
        log.info(request.getCorrelationId() + String.format(" - found %d data type properties", properties.size()));

        // map data type properties to domain classes
        log.info(request.getCorrelationId() + " - mapDataTypePropertiesToDomainClasses");
        mapPropertiesToSourceClasses(classes, properties);

        // data type and cardinality calculation may impact performance
        if (!SchemaExtractorRequestDto.ExtractionMode.excludeDataTypesAndCardinalities.equals(request.getMode())) {
            // find data types for attributes
            log.info(request.getCorrelationId() + " - findDataTypesForDataTypeProperties");
            processDataTypes(properties, request);
            // find min/max cardinality
            if (!SchemaExtractorRequestDto.ExtractionMode.excludeCardinalities.equals(request.getMode())) {
                log.info(request.getCorrelationId() + " - calculateCardinalitiesForDataTypeProperties");
                processCardinalities(properties, request);
            }
        }

        // fill schema object with attributes
        formatDataTypeProperties(properties, schema);
    }

    protected abstract Map<String, SchemaExtractorPropertyNodeInfo> findAllDataTypeProperties(@Nonnull List<SchemaClass> classes,
                                                                                              @Nonnull SchemaExtractorRequestDto request);

    protected void buildObjectTypeProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        List<SchemaClass> classes = schema.getClasses();

        // find all object type properties with domain-range pairs and instances count
        log.info(request.getCorrelationId() + " - findAllObjectTypeProperties");
        Map<String, SchemaExtractorPropertyNodeInfo> properties = findAllObjectTypeProperties(classes, request);
        log.info(request.getCorrelationId() + String.format(" - found %d object type properties", properties.size()));

        log.info(request.getCorrelationId() + " - validatePropertyDomainRangeMapping");
        validatePropertyDomainRangeMapping(classes, properties);

        // cardinality calculation may impact performance
        if (SchemaExtractorRequestDto.ExtractionMode.full.equals(request.getMode())) {
            // find min/max cardinality
            log.info(request.getCorrelationId() + " - calculateCardinalitiesForObjectTypeProperties");
            processCardinalities(properties, request);
        }

        // fill schema object with attributes
        formatObjectTypeProperties(properties, schema);
    }

    protected abstract Map<String, SchemaExtractorPropertyNodeInfo> findAllObjectTypeProperties(@Nonnull List<SchemaClass> classes,
                                                                                                @Nonnull SchemaExtractorRequestDto request);

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
        for (SchemaAttribute item : schema.getAttributes()) {
            namespaces.add(item.getNamespace());
        }
        for (SchemaRole item : schema.getAssociations()) {
            namespaces.add(item.getNamespace());
        }
        if (namespaces.isEmpty()) {
            return null;
        }
        Map<String, Long> namespacesWithCounts = namespaces.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        return namespacesWithCounts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    }

    protected void buildExtractionProperties(@Nonnull SchemaExtractorRequestDto request, @Nonnull Schema schema) {
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_ENDPOINT, request.getEndpointUrl()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_GRAPH_NAME, request.getGraphName()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_VERSION, request.getVersion().name()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_MODE, request.getMode().name()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDE_SYSTEM_CLASSES,
                request.getExcludeSystemClasses().toString()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDE_META_DOMAIN_CLASSES,
                request.getExcludeMetaDomainClasses().toString()));
        schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDE_PROPERTIES_WITHOUT_CLASSES,
                request.getExcludePropertiesWithoutClasses().toString()));
    }

    protected List<SchemaClass> processClasses(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequestDto request) {
        List<SchemaClass> classes = new ArrayList<>();

        for (QueryResult queryResult : queryResults) {
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

            if (className != null && !isExcludedResource(className, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())) {
                SchemaClass classEntry = new SchemaClass();
                SchemaUtil.setLocalNameAndNamespace(className, classEntry);
                classEntry.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));
                classes.add(classEntry);
            }
        }
        return classes;
    }

    protected boolean isExcludedResource(@Nonnull String resourceName, @Nonnull Boolean excludeSystemClasses, @Nonnull Boolean excludeMetaDomainClasses) {
        boolean excluded = false;
        if (isTrue(excludeSystemClasses)) {
            excluded = SchemaConstants.EXCLUDED_SYSTEM_URI_FROM_ENDPOINT.stream().anyMatch(resourceName::startsWith);
        }
        if (isFalse(excluded) && isTrue(excludeMetaDomainClasses)) {
            excluded = SchemaConstants.EXCLUDED_META_DOMAIN_URI_FROM_ENDPOINT.stream().anyMatch(resourceName::startsWith);
        }
        return excluded;
    }

    @Nonnull
    protected Map<String, SchemaExtractorClassNodeInfo> buildGraphOfClasses(@Nonnull List<SchemaClass> classes) {
        Map<String, SchemaExtractorClassNodeInfo> graphOfClasses = new HashMap<>();
        classes.forEach(c -> {
            SchemaExtractorClassNodeInfo classInfo = new SchemaExtractorClassNodeInfo();
            classInfo.setClassName(c.getFullName());
            classInfo.setTripleCount(c.getInstanceCount());
            graphOfClasses.put(c.getFullName(), classInfo);
        });
        return graphOfClasses;
    }

    protected void updateGraphOfClassesWithNeighbors(@Nonnull List<QueryResult> queryResults, @Nonnull Map<String, SchemaExtractorClassNodeInfo> graphOfClasses,
                                                     @Nonnull SchemaExtractorRequestDto request) {
        for (QueryResult queryResult : queryResults) {
            String classA = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A);
            String classB = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B);
            if (classA != null && classB != null
                    && !isExcludedResource(classA, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())
                    && !isExcludedResource(classB, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())) {
                if (graphOfClasses.containsKey(classA)) {
                    graphOfClasses.get(classA).getNeighbors().add(classB);
                }
            }
        }
    }

    // sort class neighbors by instance count (ascending)
    @Nonnull
    protected Map<String, SchemaExtractorClassNodeInfo> sortGraphOfClassesByNeighbors(@Nonnull Map<String, SchemaExtractorClassNodeInfo> classes) {
        return classes.entrySet().stream()
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

    // sort class neighbors by instance count (ascending)
    @Nonnull
    protected List<String> sortNeighborsByInstances(@Nonnull List<String> neighbors, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
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

    // sort classes by instance count (descending)
    @Nonnull
    protected List<SchemaClass> sortClassesByInstances(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph) {
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

    protected SchemaClass findClass(@Nonnull List<SchemaClass> classes, @Nullable String className) {
        return classes.stream().filter(
                schemaClass -> schemaClass.getFullName().equals(className) || schemaClass.getLocalName().equals(className))
                .findFirst().orElse(null);
    }

    protected void processSuperclasses(@Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
                                       @Nonnull SchemaExtractorRequestDto request) {

        for (Entry<String, SchemaExtractorClassNodeInfo> entry : classesGraph.entrySet()) {

            SchemaClass currentClass = findClass(classes, entry.getKey());
            if (currentClass == null || THING_NAME.equals(currentClass.getLocalName())) {
                continue;
            }

            // sort neighbor list by instance count (ascending)
            List<String> neighbors = sortNeighborsByInstances(entry.getValue().getNeighbors(), classesGraph);

            // find the class with the smallest number of instances but including all current instances
            SchemaExtractorClassNodeInfo currentClassInfo = entry.getValue();
            updateClass(currentClass, currentClassInfo, neighbors, classesGraph, classes, request);

        }
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

        List<SchemaClass> sortedClasses = sortClassesByInstances(classes, classesGraph);

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
            List<String> sortedNeighbors = sortNeighborsByInstances(notAccessibleNeighbors, classesGraph);
            updateClass(currentClass, currentClassInfo, sortedNeighbors, classesGraph, classes, request);
        }

        return accessible;
    }

    protected void updateClass(@Nonnull SchemaClass currentClass, @Nonnull SchemaExtractorClassNodeInfo currentClassInfo, @Nonnull List<String> neighbors,
                               @Nonnull Map<String, SchemaExtractorClassNodeInfo> classesGraph, List<SchemaClass> classes,
                               @Nonnull SchemaExtractorRequestDto request) {

        for (String neighbor : neighbors) {
            Long neighborInstances = classesGraph.get(neighbor).getTripleCount();
            if (neighborInstances < currentClassInfo.getTripleCount()) {
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

    protected void processAllObjectTypeProperties(@Nonnull List<QueryResult> queryResults,
                                                  @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                  @Nonnull SchemaExtractorRequestDto request) {
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            String domainClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE);
            String rangeClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_TARGET);
            String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

            if (StringUtils.isEmpty(propertyName)) {
                continue;
            }
            if (domainClass != null && isExcludedResource(domainClass, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())) {
                continue;
            }
            if (rangeClass != null && isExcludedResource(rangeClass, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())) {
                continue;
            }

            addObjectProperty(propertyName, domainClass, rangeClass, instances, properties);
        }
    }

    protected void addObjectProperty(@Nonnull String propertyName, @Nullable String domainClass, @Nullable String rangeClass,
                                     @Nonnull String instances, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        SchemaExtractorPropertyNodeInfo property = properties.get(propertyName);
        if (property == null) {
            property = new SchemaExtractorPropertyNodeInfo();
            property.setIsObjectProperty(Boolean.TRUE);
            properties.put(propertyName, property);
        }
        SchemaExtractorSourceTargetInfo domainRange = new SchemaExtractorSourceTargetInfo();
        domainRange.setValidSource(false);
        domainRange.setValidTarget(false);
        domainRange.setTripleCount(Long.valueOf(instances));
        property.getSourceAndTargetPairs().add(domainRange);

        // if property without domain and range class
        if (StringUtils.isEmpty(domainClass) && StringUtils.isEmpty(rangeClass)) {
            domainRange.setValidSource(true);
            domainRange.setValidTarget(true);
            domainRange.setSourceClass(StringUtils.EMPTY);
            domainRange.setTargetClass(StringUtils.EMPTY);
            return;
        }

        // if property without domain but with range class
        if (StringUtils.isEmpty(domainClass) && isFalse(StringUtils.isEmpty(rangeClass))) {
            domainRange.setValidSource(true);
            domainRange.setTargetClass(rangeClass);
            domainRange.setSourceClass(StringUtils.EMPTY);
            return;
        }

        // if property with domain but without range class
        if (isFalse(StringUtils.isEmpty(domainClass)) && StringUtils.isEmpty(rangeClass)) {
            domainRange.setValidTarget(true);
            domainRange.setSourceClass(domainClass);
            domainRange.setTargetClass(StringUtils.EMPTY);
            return;
        }

        domainRange.setSourceClass(domainClass);
        domainRange.setTargetClass(rangeClass);
    }

    protected void validatePropertyDomainRangeMapping(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        for (Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            if (!p.getValue().getIsObjectProperty()) {
                continue;
            }
            List<SchemaExtractorSourceTargetInfo> domainRangePairs = p.getValue().getSourceAndTargetPairs();

            for (SchemaExtractorSourceTargetInfo domainRangeEntry : domainRangePairs) {
                if (isTrue(domainRangeEntry.getValidSource()) && isTrue(domainRangeEntry.getValidTarget())) {
                    continue;
                }

                SchemaClass domainClass = findClass(classes, domainRangeEntry.getSourceClass());
                SchemaClass rangeClass = findClass(classes, domainRangeEntry.getTargetClass());

                if (domainClass == null && rangeClass == null) {
                    domainRangeEntry.setValidSource(true);
                    domainRangeEntry.setValidTarget(true);
                    continue;
                }

                boolean isRootDomain = domainClass == null || domainClass.getSuperClasses().isEmpty()
                        || domainClass.getSuperClasses().stream().noneMatch(e -> existDomainRangePairWithThisDomain(e, domainRangePairs));
                boolean isRootRange = rangeClass == null || rangeClass.getSuperClasses().isEmpty()
                        || rangeClass.getSuperClasses().stream().noneMatch(e -> existDomainRangePairWithThisRange(e, domainRangePairs));

                if (isRootDomain && isRootRange) {
                    SchemaExtractorSourceTargetInfo validDomainPair = getPropertyDomainRangePairWithDomain(domainRangeEntry, domainRangePairs, classes);
                    SchemaExtractorSourceTargetInfo validRangePair = getPropertyDomainRangePairWithRange(validDomainPair, domainRangePairs, classes);
                    validRangePair.setValidSource(true);
                    validRangePair.setValidTarget(true);
                }
            }
        }
    }

    protected boolean existDomainRangePairWithThisDomain(@Nonnull String thisDomain, @Nonnull List<SchemaExtractorSourceTargetInfo> domainRangePairs) {
        return domainRangePairs.stream().anyMatch(pair -> pair != null && thisDomain.equals(pair.getSourceClass()));
    }

    protected boolean existDomainRangePairWithThisRange(@Nonnull String thisRange, @Nonnull List<SchemaExtractorSourceTargetInfo> domainRangePairs) {
        return domainRangePairs.stream().anyMatch(pair -> pair != null && thisRange.equals(pair.getTargetClass()));
    }

    protected SchemaExtractorSourceTargetInfo getDomainRangePair(@Nonnull String domain, @Nonnull String range, @Nonnull List<SchemaExtractorSourceTargetInfo> domainRangePairs) {
        return domainRangePairs.stream()
                .filter(pair -> pair != null && domain.equals(pair.getSourceClass()) && range.equals(pair.getTargetClass()))
                .findAny().orElse(null);
    }

    protected SchemaExtractorSourceTargetInfo getPropertyDomainRangePairWithDomain(@Nonnull SchemaExtractorSourceTargetInfo domainRangeEntry,
                                                                                   @Nonnull List<SchemaExtractorSourceTargetInfo> domainRangePairs,
                                                                                   @Nonnull List<SchemaClass> classes) {

        SchemaClass domainClass = findClass(classes, domainRangeEntry.getSourceClass());
        if (domainClass == null) {
            return domainRangeEntry;
        }

        for (String subClassName : domainClass.getSubClasses()) {
            SchemaExtractorSourceTargetInfo subDomainRangeEntry = getDomainRangePair(subClassName, domainRangeEntry.getTargetClass(), domainRangePairs);
            if (subDomainRangeEntry != null && subDomainRangeEntry.getTripleCount().equals(domainRangeEntry.getTripleCount())) {
                return getPropertyDomainRangePairWithDomain(subDomainRangeEntry, domainRangePairs, classes);
            }
        }
        return domainRangeEntry;
    }

    protected SchemaExtractorSourceTargetInfo getPropertyDomainRangePairWithRange(@Nonnull SchemaExtractorSourceTargetInfo domainRangeEntry,
                                                                                  @Nonnull List<SchemaExtractorSourceTargetInfo> domainRangePairs,
                                                                                  @Nonnull List<SchemaClass> classes) {
        SchemaClass rangeClass = findClass(classes, domainRangeEntry.getTargetClass());
        if (rangeClass == null) {
            return domainRangeEntry;
        }
        for (String subClassName : rangeClass.getSubClasses()) {
            SchemaExtractorSourceTargetInfo subDomainRangeEntry = getDomainRangePair(domainRangeEntry.getSourceClass(), subClassName, domainRangePairs);
            if (subDomainRangeEntry != null && subDomainRangeEntry.getTripleCount().equals(domainRangeEntry.getTripleCount())) {
                return getPropertyDomainRangePairWithRange(subDomainRangeEntry, domainRangePairs, classes);
            }
        }
        return domainRangeEntry;
    }

    protected void processAllDataTypeProperties(@Nonnull List<QueryResult> queryResults,
                                                @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties,
                                                @Nonnull SchemaExtractorRequestDto request) {
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
            String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
            String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

            if (className != null && isExcludedResource(className, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())) {
                continue;
            }

            if (!properties.containsKey(propertyName)) {
                properties.put(propertyName, new SchemaExtractorPropertyNodeInfo());
            }
            SchemaExtractorPropertyNodeInfo property = properties.get(propertyName);
            SchemaExtractorClassNodeInfo classInfo = new SchemaExtractorClassNodeInfo();
            classInfo.setClassName(className);
            classInfo.setTripleCount(Long.valueOf(instances));
            property.getSourceClasses().add(classInfo);
        }
    }

    protected void mapPropertiesToSourceClasses(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties) {
        for (Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {

            List<String> propertySourceClasses = p.getValue().getSourceClasses()
                    .stream().map(SchemaExtractorClassNodeInfo::getClassName).collect(Collectors.toList());
            Set<String> processedClasses = new HashSet<>();

            for (SchemaExtractorClassNodeInfo classInfo : p.getValue().getSourceClasses()) {
                if (processedClasses.contains(classInfo.getClassName())) {
                    continue;
                }
                SchemaClass sourceClass = findClass(classes, classInfo.getClassName());
                if (sourceClass == null) {
                    continue;
                }

                boolean processNow = sourceClass.getSuperClasses().isEmpty()
                        || sourceClass.getSuperClasses().stream().noneMatch(propertySourceClasses::contains);

                if (processNow) {
                    addSourceClassToProperty(classInfo, p, classes);
                    processedClasses.add(classInfo.getClassName());
                    if (!sourceClass.getSubClasses().isEmpty()) {
                        for (String sourceSubClass : sourceClass.getSubClasses()) {
                            if (propertySourceClasses.contains(sourceSubClass)) {
                                processedClasses.add(sourceSubClass);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void addSourceClassToProperty(@Nonnull SchemaExtractorClassNodeInfo classInfo, @Nonnull Entry<String, SchemaExtractorPropertyNodeInfo> property,
                                            @Nonnull List<SchemaClass> classes) {
        String propertySource = findPropertyClass(classInfo, property.getKey(), property.getValue().getSourceClasses(), classes);
        if (!SchemaUtil.isEmpty(propertySource)) {
            property.getValue().getSourceAndTargetPairs().add(new SchemaExtractorSourceTargetInfo(propertySource));
        }
    }

    protected String findPropertyClass(@Nonnull SchemaExtractorClassNodeInfo classWithMaxCount, @Nonnull String propertyName,
                                       @Nonnull List<SchemaExtractorClassNodeInfo> assignedClasses, @Nonnull List<SchemaClass> classes) {
        SchemaClass rootClass = findClass(classes, classWithMaxCount.getClassName());
        if (rootClass == null) {
            log.error("Error - cannot find property domain or range class - propertyName [" + propertyName + "], targetClassName [" + classWithMaxCount.getClassName() + "]");
            return null;
        }
        for (String subClassName : rootClass.getSubClasses()) {
            for (SchemaExtractorClassNodeInfo info : assignedClasses) {
                if (info != null && info.getClassName() != null && info.getClassName().equals(subClassName)
                        && info.getTripleCount() != null
                        && info.getTripleCount().equals(classWithMaxCount.getTripleCount())) {
                    return findPropertyClass(info, propertyName, assignedClasses, classes);
                }
            }
        }
        return rootClass.getFullName();
    }

    protected void processDataTypes(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequestDto request) {
        for (Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            if (p.getValue().getIsObjectProperty()) {
                continue;
            }
            SchemaExtractorPropertyNodeInfo property = p.getValue();

            String query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, p.getKey());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE.name(), query);
            if (queryResults.isEmpty() || queryResults.size() > 1) {
                property.setDataType(DATA_TYPE_XSD_DEFAULT);
                continue;
            }
            String resultDataType = queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
            if (SchemaUtil.isEmpty(resultDataType)) {
                property.setDataType(DATA_TYPE_XSD_DEFAULT);
            } else {
                property.setDataType(SchemaUtil.parseDataType(resultDataType));
            }
        }
    }

    protected void processCardinalities(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequestDto request) {
        for (Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            SchemaExtractorPropertyNodeInfo property = p.getValue();
            setMaxCardinality(p.getKey(), property, request);
            setMinCardinality(p.getKey(), property, request);
        }
    }

    protected void setMaxCardinality(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        if (property.getSourceAndTargetPairs().isEmpty() || (property.getSourceAndTargetPairs().size() == 1 && property.getSourceAndTargetPairs().get(0).getSourceClass() == null)) {
            property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
            return;
        }
        String query = SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.name(), query);
        if (queryResults.isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
    }

    protected void setMinCardinality(@Nonnull String propertyName, @Nonnull SchemaExtractorPropertyNodeInfo property, @Nonnull SchemaExtractorRequestDto request) {
        if (property.getSourceAndTargetPairs().isEmpty() || (property.getSourceAndTargetPairs().size() == 1 && property.getSourceAndTargetPairs().get(0).getSourceClass() == null)) {
            property.setMinCardinality(DEFAULT_MIN_CARDINALITY);
            return;
        }
        Integer minCardinality = 1;
        List<QueryResult> queryResults;
        Set<String> uniqueDomains;
        if (isTrue(property.getIsObjectProperty())) {
            uniqueDomains = property.getSourceAndTargetPairs().stream().filter(p -> isTrue(p.getValidSource()) && isTrue(p.getValidTarget()))
                    .map(SchemaExtractorSourceTargetInfo::getSourceClass).collect(Collectors.toSet());
        } else {
            uniqueDomains = property.getSourceAndTargetPairs().stream().map(SchemaExtractorSourceTargetInfo::getSourceClass).collect(Collectors.toSet());
        }
        for (String domain : uniqueDomains) {
            if (DEFAULT_MIN_CARDINALITY.equals(minCardinality)) {
                break;
            }
            String query = SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.getSparqlQuery().
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
                    replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domain);
            queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.name(), query);
            if (!queryResults.isEmpty()) {
                minCardinality = DEFAULT_MIN_CARDINALITY;
            }
        }
        property.setMinCardinality(minCardinality);
    }

    protected void formatDataTypeProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        for (Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            SchemaExtractorPropertyNodeInfo propertyNodeInfo = p.getValue();
            if (isFalse(p.getValue().getIsObjectProperty())) {
                SchemaAttribute attribute = new SchemaAttribute();
                SchemaUtil.setLocalNameAndNamespace(p.getKey(), attribute);
                attribute.setMinCardinality(propertyNodeInfo.getMinCardinality());
                attribute.setMaxCardinality(propertyNodeInfo.getMaxCardinality());
                Set<String> uniqueDomains = propertyNodeInfo.getSourceAndTargetPairs().stream().map(SchemaExtractorSourceTargetInfo::getSourceClass).collect(Collectors.toSet());
                attribute.getSourceClasses().addAll(uniqueDomains);
                attribute.setType(p.getValue().getDataType());
                if (attribute.getType() == null || attribute.getType().trim().equals("")) {
                    attribute.setType(DATA_TYPE_XSD_DEFAULT);
                }
                schema.getAttributes().add(attribute);
            }
        }
    }

    protected void formatObjectTypeProperties(@Nonnull Map<String, SchemaExtractorPropertyNodeInfo> properties, @Nonnull Schema schema) {
        for (Entry<String, SchemaExtractorPropertyNodeInfo> p : properties.entrySet()) {
            SchemaExtractorPropertyNodeInfo propertyNodeInfo = p.getValue();
            if (p.getValue().getIsObjectProperty()) {
                SchemaRole role = new SchemaRole();
                SchemaUtil.setLocalNameAndNamespace(p.getKey(), role);
                role.setMinCardinality(propertyNodeInfo.getMinCardinality());
                role.setMaxCardinality(propertyNodeInfo.getMaxCardinality());
                propertyNodeInfo.getSourceAndTargetPairs().forEach(pair -> {
                    if (isTrue(pair.getValidSource()) && isTrue(pair.getValidTarget())
                            && isFalse(isDuplicatePair(role.getClassPairs(), pair.getSourceClass(), pair.getTargetClass()))) {
                        role.getClassPairs().add(new ClassPair(pair.getSourceClass(), pair.getTargetClass()));
                    }
                });
                schema.getAssociations().add(role);
            }
        }
    }

    protected boolean isDuplicatePair(@Nonnull List<ClassPair> pairs, @Nullable String domain, @Nullable String range) {
        return pairs.stream().anyMatch(p -> p != null
                && ((p.getSourceClass() == null && domain == null) || (p.getSourceClass() != null && p.getSourceClass().equals(domain)))
                && ((p.getTargetClass() == null && range == null) || (p.getTargetClass() != null && p.getTargetClass().equals(range)))
        );
    }

}

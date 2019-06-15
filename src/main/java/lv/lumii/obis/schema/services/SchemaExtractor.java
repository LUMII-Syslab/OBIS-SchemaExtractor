package lv.lumii.obis.schema.services;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;
import static lv.lumii.obis.schema.services.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Slf4j
@Service
public class SchemaExtractor {

	private static Comparator<String> nullSafeStringComparator = Comparator.nullsLast(String::compareToIgnoreCase);

	@Autowired @Setter @Getter
	private SparqlEndpointProcessor sparqlEndpointProcessor;

	// =====================================================================================
	// PUBLIC methods
	// =====================================================================================

	@Nonnull
	public Schema extractSchema(@Nonnull SchemaExtractorRequest request){
		Schema schema = initializeSchema(request);
		buildClasses(request, schema);
		buildProperties(request, schema);
		buildNamespaceMap(request, schema);
		return schema;
	}

	@Nonnull
	public Schema extractClasses(@Nonnull SchemaExtractorRequest request){
		Schema schema = initializeSchema(request);
		buildClasses(request, schema);
		buildNamespaceMap(request, schema);
		return schema;
	}

	// =====================================================================================
	// PRIVATE methods
	// =====================================================================================

	private Schema initializeSchema(@Nonnull SchemaExtractorRequest request){
		Schema schema = new Schema();
		schema.setName((StringUtils.isNotEmpty(request.getGraphName())) ? request.getGraphName() + "_Schema" : "Schema");
		buildExtractionProperties(request, schema);
		return schema;
	}

	private void buildClasses(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema){
		// find all classes
		log.info(request.getCorrelationId() + " - findAllClassesWithInstanceCount");
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_CLASSES_WITH_INSTANCE_COUNT);
		List<SchemaClass> classes = processClasses(queryResults, request);
		schema.setClasses(classes);
		Map<String, SchemaClassNodeInfo> graphOfClasses = buildGraphOfClasses(classes);
		queryResults.clear();

		// find intersection classes
		log.info(request.getCorrelationId() + " - findIntersectionClassesAndUpdateClassNeighbors");
		queryResults = sparqlEndpointProcessor.read(request, FIND_INTERSECTION_CLASSES);
		updateGraphOfClassesWithNeighbors(queryResults, graphOfClasses, request);
		queryResults.clear();

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

	private void buildProperties(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema){
		List<SchemaClass> classes = schema.getClasses();

		// find all properties (attributes + associations) and domain class instances count
		log.info(request.getCorrelationId() + " - findAllProperties");
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_ALL_PROPERTIES);
		Map<String, SchemaPropertyNodeInfo> properties = processAllProperties(queryResults, request);
		queryResults.clear();

		// find associations and range class instances count
		log.info(request.getCorrelationId() + " - findAssociations");
		queryResults = sparqlEndpointProcessor.read(request, FIND_OBJECT_PROPERTIES_WITH_RANGE);
		processAssociations(queryResults, properties);
		queryResults.clear();

		// map properties to domain classes
		log.info(request.getCorrelationId() + " - mapPropertiesToDomainClasses");
		mapPropertiesToDomainClasses(classes, properties);

		// map properties to range classes
		log.info(request.getCorrelationId() + " - mapPropertiesToRangeClasses");
		mapPropertiesToRangeClasses(classes, properties, request);

		// remove duplicate domain-range pairs
		log.info(request.getCorrelationId() + " - normalizePropertyDomainRangeMapping");
		normalizePropertyDomainRangeMapping(classes, properties);

		// data type and cardinality calculation may impact performance
		if(!SchemaExtractorRequest.ExtractionMode.simple.equals(request.getMode())){
			// find data types for attributes
			log.info(request.getCorrelationId() + " - findDataTypesForAttributes");
			processDataTypes(properties, request);
			// find min/max cardinality
			if(!SchemaExtractorRequest.ExtractionMode.data.equals(request.getMode())){
				log.info(request.getCorrelationId() + " - calculateCardinalities");
				processCardinalities(properties, request);
			}
		}

		// fill schema object with attributes and associations
		formatProperties(properties, schema);
	}

	private void buildNamespaceMap(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema){
		String mainNamespace = findMainNamespace(schema);
		if(StringUtils.isNotEmpty(mainNamespace)){
			schema.setDefaultNamespace(mainNamespace);
			schema.getPrefixes().add(new NamespacePrefixEntry(SchemaConstants.DEFAULT_NAMESPACE_PREFIX, mainNamespace));
		}
	}

	@Nullable
	private String findMainNamespace(@Nonnull Schema schema){
		List<String> namespaces = new ArrayList<>();
		for(SchemaClass item: schema.getClasses()){
			namespaces.add(item.getNamespace());
		}
		for(SchemaAttribute item: schema.getAttributes()){
			namespaces.add(item.getNamespace());
		}
		for(SchemaRole item: schema.getAssociations()){
			namespaces.add(item.getNamespace());
		}
		if(namespaces.isEmpty()){
			return null;
		}
		Map<String, Long> namespacesWithCounts = namespaces.stream()
				.collect(Collectors.groupingBy(e -> e, Collectors.counting()));
		return namespacesWithCounts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
	}

	private void buildExtractionProperties(@Nonnull SchemaExtractorRequest request, @Nonnull Schema schema){
		schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_MODE, request.getMode().name()));
		schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDE_SYSTEM_CLASSES,
				request.getExcludeSystemClasses().toString()));
		schema.getParameters().add(new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDE_META_DOMAIN_CLASSES,
				request.getExcludeMetaDomainClasses().toString()));
	}

	private List<SchemaClass> processClasses(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequest request){
		List<SchemaClass> classes = new ArrayList<>();

		for(QueryResult queryResult: queryResults){
			String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
			String instancesCountStr = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

			if(className != null && !isExcludedResource(className, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())){
				SchemaClass classEntry = new SchemaClass();
				setLocalNameAndNamespace(className, classEntry);

				Long instanceCount = 0L;
				if(instancesCountStr != null){
					try {
						instanceCount = Long.valueOf(instancesCountStr);
					} catch (NumberFormatException e){
						// do nothing
					}
				}
				classEntry.setInstanceCount(instanceCount);

				classes.add(classEntry);
			}
		}
		return classes;
	}

	private boolean isExcludedResource(@Nonnull String resourceName, @Nonnull Boolean excludeSystemClasses, @Nonnull Boolean excludeMetaDomainClasses) {
		boolean excluded = false;
		if(isTrue(excludeSystemClasses)){
			excluded = SchemaConstants.EXCLUDED_SYSTEM_URI_FROM_ENDPOINT.stream().anyMatch(resourceName::startsWith);
		}
		if(isFalse(excluded) && isTrue(excludeMetaDomainClasses)){
			excluded = SchemaConstants.EXCLUDED_META_DOMAIN_URI_FROM_ENDPOINT.stream().anyMatch(resourceName::startsWith);
		}
		return excluded;
	}

	private void setLocalNameAndNamespace(@Nonnull String fullName, @Nonnull SchemaEntity entity){
		String localName = fullName;
		String namespace = "";

		int localNameIndex = fullName.lastIndexOf("#");
		if(localNameIndex == -1){
			localNameIndex = fullName.lastIndexOf("/");
		}
		if(localNameIndex != -1 && localNameIndex < fullName.length()){
			localName = fullName.substring(localNameIndex + 1);
			namespace = fullName.substring(0, localNameIndex + 1);
		}

		entity.setLocalName(localName);
		entity.setFullName(fullName);
		entity.setNamespace(namespace);
	}

	@Nonnull
	private Map<String, SchemaClassNodeInfo> buildGraphOfClasses(@Nonnull List<SchemaClass> classes) {
		Map<String, SchemaClassNodeInfo> graphOfClasses = new HashMap<>();
		classes.forEach(c -> {
			SchemaClassNodeInfo classInfo = new SchemaClassNodeInfo();
			classInfo.setClassName(c.getFullName());
			classInfo.setInstanceCount(c.getInstanceCount());
			graphOfClasses.put(c.getFullName(), classInfo);
		});
		return graphOfClasses;
	}

	private void updateGraphOfClassesWithNeighbors(@Nonnull List<QueryResult> queryResults, @Nonnull Map<String, SchemaClassNodeInfo> graphOfClasses,
												   @Nonnull SchemaExtractorRequest request){
		for(QueryResult queryResult: queryResults){
			String classA = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A);
			String classB = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B);
			if(classA != null && classB != null
					&& !isExcludedResource(classA, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())
					&& !isExcludedResource(classB, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())){
				if(graphOfClasses.containsKey(classA)){
					graphOfClasses.get(classA).getNeighbors().add(classB);
				}
			}
		}
	}

	// sort class neighbors by instance count (ascending)
	@Nonnull
	private Map<String, SchemaClassNodeInfo> sortGraphOfClassesByNeighbors(@Nonnull Map<String, SchemaClassNodeInfo> classes){
		return classes.entrySet().stream()
				.sorted((o1, o2) -> {
					Integer o1Size = o1.getValue().getNeighbors().size();
					Integer o2Size = o2.getValue().getNeighbors().size();
					int compareResult = o1Size.compareTo(o2Size);
					if(compareResult != 0){
						return compareResult;
					}
					compareResult = o1.getValue().getInstanceCount().compareTo(o2.getValue().getInstanceCount());
					if(compareResult != 0){
						return compareResult;
					} else {
						return nullSafeStringComparator.compare(o1.getValue().getClassName(), o2.getValue().getClassName());
					}
				})
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
	}

	// sort class neighbors by instance count (ascending)
	@Nonnull
	private List<String> sortNeighborsByInstances(@Nonnull List<String> neighbors, @Nonnull Map<String, SchemaClassNodeInfo> classesGraph){
		return neighbors.stream()
				.sorted((o1, o2) -> {
					Long neighborInstances1 = classesGraph.get(o1).getInstanceCount();
					Long neighborInstances2 = classesGraph.get(o2).getInstanceCount();
					int compareResult = neighborInstances1.compareTo(neighborInstances2);
					if(compareResult == 0){
						return nullSafeStringComparator.compare(classesGraph.get(o1).getClassName(), classesGraph.get(o2).getClassName());
					} else {
						return compareResult;
					}
				})
				.collect(Collectors.toList());
	}
	
	// sort classes by instance count (descending)
	@Nonnull
	private List<SchemaClass> sortClassesByInstances(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaClassNodeInfo> classesGraph){
		return classes.stream()
				.sorted((o1, o2) -> {
					Long neighborInstances1 = classesGraph.get(o1.getFullName()).getInstanceCount();
					Long neighborInstances2 = classesGraph.get(o2.getFullName()).getInstanceCount();
					int compareResult = neighborInstances2.compareTo(neighborInstances1);
					if(compareResult == 0){
						return nullSafeStringComparator.compare(o2.getFullName(), o1.getFullName());
					} else {
						return compareResult;
					}
				})
				.collect(Collectors.toList());
	}

	private SchemaClass findClass(@Nonnull List<SchemaClass> classes, @Nonnull String className){
		return classes.stream().filter(
				schemaClass -> schemaClass.getFullName().equals(className) || schemaClass.getLocalName().equals(className))
				.findFirst().orElse(null);
	}

	private void processSuperclasses(@Nonnull Map<String, SchemaClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
									 @Nonnull SchemaExtractorRequest request){
		
		for(Entry<String, SchemaClassNodeInfo> entry: classesGraph.entrySet()){
			
			SchemaClass currentClass = findClass(classes, entry.getKey());			
			if(currentClass == null || THING_NAME.equals(currentClass.getLocalName())){
				continue;
			}
			
			// sort neighbor list by instance count (ascending)
			List<String> neighbors = sortNeighborsByInstances(entry.getValue().getNeighbors(), classesGraph);
			
			// find the class with the smallest number of instances but including all current instances
			SchemaClassNodeInfo currentClassInfo = entry.getValue();
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
	private void updateMultipleInheritanceSuperclasses(@Nonnull Map<String, SchemaClassNodeInfo> classesGraph, @Nonnull List<SchemaClass> classes,
													   @Nonnull SchemaExtractorRequest request){

		List<SchemaClass> sortedClasses = sortClassesByInstances(classes, classesGraph);

		for(SchemaClass currentClass: sortedClasses){

			// 1. exclude THING class
			if(THING_NAME.equals(currentClass.getLocalName()) || currentClass.getSuperClasses().isEmpty()){
				continue;
			}
			SchemaClass superClass = findClass(classes, currentClass.getSuperClasses().get(0));
			if(THING_NAME.equals(superClass.getLocalName())){
				continue;
			}

			// 2. one of the neighbors is THING, so no need to perform additional validation
			// because correct assignment was selected in processSuperclasses method
			SchemaClassNodeInfo classInfo = classesGraph.get(currentClass.getFullName());
			if(classInfo.getNeighbors().size() <= 2){
				continue;
			}

			// 3. validate whether all neighbors are accessible
			int maxCounter = classInfo.getNeighbors().size() + 1;
			boolean accessible = validateAllNeighbors(currentClass, classInfo, classes, classesGraph, request);
			while(!accessible && maxCounter != 0){
				maxCounter--;
				accessible = validateAllNeighbors(currentClass, classInfo, classes, classesGraph, request);
			}
		}
	}
	
	private boolean validateAllNeighbors(@Nonnull SchemaClass currentClass, @Nonnull SchemaClassNodeInfo currentClassInfo, @Nonnull List<SchemaClass> classes,
										 @Nonnull Map<String, SchemaClassNodeInfo> classesGraph, @Nonnull SchemaExtractorRequest request){
		boolean accessible = true;
		List<String> notAccessibleNeighbors = new ArrayList<>();
		for(String neighbor: currentClassInfo.getNeighbors()){
			SchemaClass neighborClass = findClass(classes, neighbor);
			if(THING_NAME.equals(neighborClass.getLocalName())){
				continue;
			}
			boolean isAccessibleSuperClass = isClassAccessibleFromSuperclasses(currentClass.getFullName(), neighbor, classes);
			boolean isAccessibleSubClass = isClassAccessibleFromSubclasses(currentClass.getFullName(), neighbor, classes);
			if(!isAccessibleSuperClass && !isAccessibleSubClass){
				accessible = false;
				notAccessibleNeighbors.add(neighbor);
			}
		}
		if(!accessible){
			List<String> sortedNeighbors = sortNeighborsByInstances(notAccessibleNeighbors, classesGraph);
			updateClass(currentClass, currentClassInfo, sortedNeighbors, classesGraph, classes, request);
		}
		
		return accessible;
	}
	
	private void updateClass(@Nonnull SchemaClass currentClass, @Nonnull SchemaClassNodeInfo currentClassInfo, @Nonnull List<String> neighbors,
							 @Nonnull Map<String, SchemaClassNodeInfo> classesGraph, List<SchemaClass> classes,
							 @Nonnull SchemaExtractorRequest request){
		
		for(String neighbor: neighbors){
			Long neighborInstances = classesGraph.get(neighbor).getInstanceCount();
			if(neighborInstances < currentClassInfo.getInstanceCount()){
				continue;
			}
			String query = SchemaExtractorQueries.CHECK_SUPERCLASS.getSparqlQuery();
			query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, currentClass.getFullName());
			query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B, neighbor);
			List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.CHECK_SUPERCLASS.name(), query);
			if(!queryResults.isEmpty()){
				continue;
			}
			SchemaClass superClass = findClass(classes, neighbor);
			if(!hasCyclicDependency(currentClass, superClass, classes)){
				currentClass.getSuperClasses().add(neighbor);
				if(superClass != null){
					superClass.getSubClasses().add(currentClass.getFullName());
				}
				break;
			}
		}
	}

	private boolean hasCyclicDependency(@Nonnull SchemaClass currentClass, @Nullable SchemaClass newClass, @Nonnull List<SchemaClass> classes){
		if(newClass == null){
			return false;
		}
		if(currentClass.getSuperClasses().contains(newClass.getFullName()) || currentClass.getSubClasses().contains(newClass.getFullName())){
			return true;
		}
		return isClassAccessibleFromSuperclasses(newClass.getFullName(), currentClass.getFullName(), classes);
	}
	
	private boolean isClassAccessibleFromSuperclasses(@Nonnull String currentClass, @Nonnull String neighbor, @Nonnull List<SchemaClass> classes){
		if(currentClass.equals(neighbor)){
			return true;
		}
		boolean accessible = false;
		SchemaClass current = findClass(classes, currentClass);
		for(String superClass: current.getSuperClasses()){
			accessible = isClassAccessibleFromSuperclasses(superClass, neighbor, classes);
			if(accessible){
				break;
			}
		}
		return accessible;
	}
	
	private boolean isClassAccessibleFromSubclasses(@Nonnull String currentClass, @Nonnull String neighbor, @Nonnull List<SchemaClass> classes){
		if(currentClass.equals(neighbor)){
			return true;
		}
		boolean accessible = false;
		SchemaClass current = findClass(classes, currentClass);
		for(String subClass: current.getSubClasses()){
			accessible = isClassAccessibleFromSubclasses(subClass, neighbor, classes);
			if(accessible){
				break;
			}
		}
		return accessible;
	}

	@Nonnull
	private Map<String, SchemaPropertyNodeInfo> processAllProperties(@Nonnull List<QueryResult> queryResults, @Nonnull SchemaExtractorRequest request){
		Map<String, SchemaPropertyNodeInfo> properties = new HashMap<>();
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
			String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
			String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

			if(isExcludedResource(className, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())){
				continue;
			}

			if(!properties.containsKey(propertyName)){
				properties.put(propertyName, new SchemaPropertyNodeInfo());
			}
			SchemaPropertyNodeInfo property = properties.get(propertyName);
			SchemaClassNodeInfo classInfo = new SchemaClassNodeInfo();
			classInfo.setClassName(className);
			classInfo.setInstanceCount(Long.valueOf(instances));
			property.getDomainClasses().add(classInfo);
		}
		return properties;
	}

	private void processAssociations(@Nonnull List<QueryResult> queryResults, @Nonnull Map<String, SchemaPropertyNodeInfo> properties){
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
			String className = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS);
			String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

			if(properties.containsKey(propertyName)){
				SchemaPropertyNodeInfo property = properties.get(propertyName);
				property.setIsObjectProperty(Boolean.TRUE);
				SchemaClassNodeInfo classInfo = new SchemaClassNodeInfo();
				classInfo.setClassName(className);
				classInfo.setInstanceCount(Long.valueOf(instances));
				property.getRangeClasses().add(classInfo);
			}
		}
	}
	
	private void mapPropertiesToDomainClasses(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaPropertyNodeInfo> properties){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){

			List<String> propertyDomainClasses = p.getValue().getDomainClasses()
					.stream().map(SchemaClassNodeInfo::getClassName).collect(Collectors.toList());
			Set<String> processedClasses = new HashSet<>();

			for(SchemaClassNodeInfo classInfo: p.getValue().getDomainClasses()){
				SchemaClass domainClass = findClass(classes, classInfo.getClassName());
				if(domainClass == null || processedClasses.contains(classInfo.getClassName())){
					continue;
				}

				boolean processNow = domainClass.getSuperClasses().isEmpty()
						|| domainClass.getSuperClasses().stream().noneMatch(propertyDomainClasses::contains);

				if(processNow){
					addDomainClassToProperty(classInfo, p, classes);
					processedClasses.add(classInfo.getClassName());
					if(!domainClass.getSubClasses().isEmpty()){
						for(String domainSubClass: domainClass.getSubClasses()){
							if(propertyDomainClasses.contains(domainSubClass)){
								processedClasses.add(domainSubClass);
							}
						}
					}
				}
			}
		}
	}

	private void addDomainClassToProperty(@Nonnull SchemaClassNodeInfo classInfo, @Nonnull Entry<String, SchemaPropertyNodeInfo> property,
										  @Nonnull List<SchemaClass> classes){
		String propertyDomain = findPropertyClass(classInfo, property.getKey(), property.getValue().getDomainClasses(), classes);
		if(!SchemaUtil.isEmpty(propertyDomain)){
			property.getValue().getDomainRangePairs().add(new SchemaDomainRangeInfo(propertyDomain));
		}
	}

	private void mapPropertiesToRangeClasses(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaPropertyNodeInfo> properties,
											 @Nonnull SchemaExtractorRequest request){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			if(!p.getValue().getIsObjectProperty()){
				continue;
			}

			List<String> propertyRangeClasses = p.getValue().getRangeClasses()
					.stream().map(SchemaClassNodeInfo::getClassName).collect(Collectors.toList());
			Set<String> processedClasses = new HashSet<>();

			for(SchemaClassNodeInfo classInfo: p.getValue().getRangeClasses()){
				SchemaClass rangeClass = findClass(classes, classInfo.getClassName());
				if(rangeClass == null || processedClasses.contains(classInfo.getClassName())){
					continue;
				}

				boolean processNow = rangeClass.getSuperClasses().isEmpty()
						|| rangeClass.getSuperClasses().stream().noneMatch(propertyRangeClasses::contains);

				if(processNow){
					addRangeClassToProperty(classInfo, p, classes, request);
					processedClasses.add(classInfo.getClassName());
					if(!rangeClass.getSubClasses().isEmpty()){
						for(String rangeSubClass: rangeClass.getSubClasses()){
							if(propertyRangeClasses.contains(rangeSubClass)){
								processedClasses.add(rangeSubClass);
							}
						}
					}
				}
			}
		}
	}

	private void addRangeClassToProperty(@Nonnull SchemaClassNodeInfo classInfo, @Nonnull Entry<String, SchemaPropertyNodeInfo> property,
										 @Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request){
		String propertyRange = findPropertyClass(classInfo, property.getKey(), property.getValue().getRangeClasses(), classes);
		if(!SchemaUtil.isEmpty(propertyRange)){
			List<SchemaDomainRangeInfo> domainRangePairs = property.getValue().getDomainRangePairs();
			if(domainRangePairs.isEmpty()){
				property.getValue().getDomainRangePairs().add(new SchemaDomainRangeInfo(null, propertyRange));
			} else if(domainRangePairs.size() == 1){
				SchemaDomainRangeInfo domainRangeInfo = domainRangePairs.get(0);
				domainRangeInfo.setRangeClass(propertyRange);
			} else {
				Set<String> uniqueDomains = domainRangePairs.stream().map(SchemaDomainRangeInfo::getDomainClass).collect(Collectors.toSet());
				uniqueDomains.forEach(domain -> {
					if(isFalse(existDomainRangeEntry(domain, propertyRange, domainRangePairs)) &&
							checkDomainRangeMapping(domain, propertyRange, property.getKey(), request)){
						createNewOrUpdateDomainRangeEntry(domain, propertyRange, domainRangePairs);
					}
				});
			}
		}
	}

	private boolean existDomainRangeEntry(@Nonnull String domainClass, @Nonnull String rangeClass, @Nonnull List<SchemaDomainRangeInfo> domainRangePairs){
		return domainRangePairs.stream()
				.anyMatch(pair -> domainClass.equals(pair.getDomainClass()) && rangeClass.equals(pair.getRangeClass()));
	}

	private void createNewOrUpdateDomainRangeEntry(@Nonnull String domainClass, @Nonnull String rangeClass, @Nonnull List<SchemaDomainRangeInfo> domainRangePairs){

		SchemaDomainRangeInfo domainRangeInfo = domainRangePairs.stream()
				.filter(pair -> domainClass.equals(pair.getDomainClass()) && pair.getRangeClass() == null)
				.findFirst().orElse(null);
		if(domainRangeInfo == null){
			domainRangeInfo = new SchemaDomainRangeInfo(domainClass);
			domainRangePairs.add(domainRangeInfo);
		}
		domainRangeInfo.setRangeClass(rangeClass);
	}

	private boolean checkDomainRangeMapping(@Nonnull String domainClass, @Nonnull String rangeClass, @Nonnull String property,
											@Nonnull SchemaExtractorRequest request){
		String query = SchemaExtractorQueries.CHECK_PROPERTY_DOMAIN_RANGE_MAPPING.getSparqlQuery();
		query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_A, domainClass);
		query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B, rangeClass);
		query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, property);
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.CHECK_PROPERTY_DOMAIN_RANGE_MAPPING.name(), query);
		return isFalse(queryResults.isEmpty());
	}

	private void normalizePropertyDomainRangeMapping(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaPropertyNodeInfo> properties){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			if(!p.getValue().getIsObjectProperty()) {
				continue;
			}
			List<SchemaDomainRangeInfo> domainRangePairs = p.getValue().getDomainRangePairs();
			domainRangePairs.removeIf(entry -> shouldRemoveDomainRangePair(entry, domainRangePairs, classes));
		}
	}

	private boolean shouldRemoveDomainRangePair(@Nonnull SchemaDomainRangeInfo domainRangeEntry, @Nonnull List<SchemaDomainRangeInfo> domainRangePairs,
												@Nonnull List<SchemaClass> classes){

		SchemaClass domainClass = findClass(classes, domainRangeEntry.getDomainClass());
		SchemaClass rangeClass = findClass(classes, domainRangeEntry.getRangeClass());
		if(domainClass == null || rangeClass == null){
			return true;
		}

		// check removal cases for classPairs (A,B) and (C,D)
		for(SchemaDomainRangeInfo item: domainRangePairs){

			// case: A=C and subClassOf(B,D)
			if(domainClass.getFullName().equalsIgnoreCase(item.getDomainClass())
					&& rangeClass.getSuperClasses().contains(item.getRangeClass())){
				return true;
			}

			// case: subClassOf(A,C)  and B=D
			if(domainClass.getSuperClasses().contains(item.getDomainClass())
					&& rangeClass.getFullName().equalsIgnoreCase(item.getRangeClass())){
				return true;
			}

			// case: subClassOf(A,C) and subClassOf(B,D)
			if(domainClass.getSuperClasses().contains(item.getDomainClass())
					&& rangeClass.getSuperClasses().contains(item.getRangeClass())){
				return true;
			}

		}
		return false;
	}
	
	private String findPropertyClass(@Nonnull SchemaClassNodeInfo classWithMaxCount, @Nonnull String propertyName,
									 @Nonnull List<SchemaClassNodeInfo> assignedClasses, @Nonnull List<SchemaClass> classes){
		SchemaClass rootClass = findClass(classes, classWithMaxCount.getClassName());
		if(rootClass == null){
			log.error("Error - cannot find property domain or range class - propertyName [" + propertyName + "], targetClassName [" + classWithMaxCount.getClassName() + "]");
			return null;
		}
		for(String subClassName: rootClass.getSubClasses()){
			for(SchemaClassNodeInfo info: assignedClasses){
				if(info.getClassName().equals(subClassName) 
						&& info.getInstanceCount().equals(classWithMaxCount.getInstanceCount())){
					return findPropertyClass(info, propertyName, assignedClasses, classes);
				}
			}
		}
		return rootClass.getFullName();
	}
	
	private void processDataTypes(@Nonnull Map<String, SchemaPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequest request){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			if(p.getValue().getIsObjectProperty()){
				continue;
			}
			SchemaPropertyNodeInfo property = p.getValue();	
			
			String query = SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, p.getKey());
			List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_DATA_TYPE.name(), query);
			if(queryResults.isEmpty() || queryResults.size() > 1){
				property.setDataType(DATA_TYPE_XSD_DEFAULT);
				continue;
			}
			String resultDataType = queryResults.get(0).get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_DATA_TYPE);
			if(SchemaUtil.isEmpty(resultDataType)){
				property.setDataType(DATA_TYPE_XSD_DEFAULT);
			} else {
				property.setDataType(SchemaUtil.parseDataType(resultDataType));
			}
		}
	}
	
	private void processCardinalities(@Nonnull Map<String, SchemaPropertyNodeInfo> properties, @Nonnull SchemaExtractorRequest request){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			SchemaPropertyNodeInfo property = p.getValue();			
			setMaxCardinality(p.getKey(), property, request);
			setMinCardinality(p.getKey(), property, request);
		}
	}
	
	private void setMaxCardinality(@Nonnull String propertyName, @Nonnull SchemaPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request){
		String query = SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.getSparqlQuery().replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName);
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MAX_CARDINALITY.name(), query);
		if(queryResults.isEmpty()){
			property.setMaxCardinality(1);
			return;
		}
		property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
	}
	
	private void setMinCardinality(@Nullable String propertyName, @Nullable SchemaPropertyNodeInfo property, @Nonnull SchemaExtractorRequest request){
		if(propertyName == null || property == null || property.getDomainClasses().isEmpty()){
			return;
		}
		Integer minCardinality = 1;
		List<QueryResult> queryResults;
		Set<String> uniqueDomains = property.getDomainRangePairs().stream().map(SchemaDomainRangeInfo::getDomainClass).collect(Collectors.toSet());
		for(String domain: uniqueDomains){
			if(DEFAULT_MIN_CARDINALITY.equals(minCardinality)){
				break;
			}
			String query = SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.getSparqlQuery().
					replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY, propertyName).
					replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS, domain);
			queryResults = sparqlEndpointProcessor.read(request, SchemaExtractorQueries.FIND_PROPERTY_MIN_CARDINALITY.name(), query);
			if(!queryResults.isEmpty()){
				minCardinality = DEFAULT_MIN_CARDINALITY;
			}
		}
		property.setMinCardinality(minCardinality);
	}
	
	private void formatProperties(@Nonnull Map<String, SchemaPropertyNodeInfo> properties, @Nonnull Schema schema){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			SchemaPropertyNodeInfo propertyNodeInfo = p.getValue();
			if(p.getValue().getIsObjectProperty()){
				SchemaRole role = new SchemaRole();
				setLocalNameAndNamespace(p.getKey(), role);
				role.setMinCardinality(propertyNodeInfo.getMinCardinality());
				role.setMaxCardinality(propertyNodeInfo.getMaxCardinality());
				propertyNodeInfo.getDomainRangePairs().forEach(pair -> {
					role.getClassPairs().add(new ClassPair(pair.getDomainClass(), pair.getRangeClass()));
				});
				schema.getAssociations().add(role);
			} else {
				SchemaAttribute attribute = new SchemaAttribute();
				setLocalNameAndNamespace(p.getKey(), attribute);
				attribute.setMinCardinality(propertyNodeInfo.getMinCardinality());
				attribute.setMaxCardinality(propertyNodeInfo.getMaxCardinality());
				propertyNodeInfo.getDomainRangePairs().forEach(pair -> {
					attribute.getSourceClasses().add(pair.getDomainClass());
				});
				attribute.setType(p.getValue().getDataType());
				if(attribute.getType() == null || attribute.getType().trim().equals("")){
					attribute.setType(DATA_TYPE_XSD_DEFAULT);
				}
				schema.getAttributes().add(attribute);
			}			
		}
	}

}

package lv.lumii.obis.schema.services;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import lv.lumii.obis.schema.services.dto.SchemaClassNodeInfo;
import lv.lumii.obis.schema.services.dto.SchemaDomainRangeInfo;
import lv.lumii.obis.schema.services.dto.SchemaPropertyNodeInfo;
import lv.lumii.obis.schema.services.dto.QueryResult;
import lv.lumii.obis.schema.model.ClassPair;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaAttribute;
import lv.lumii.obis.schema.model.SchemaClass;
import lv.lumii.obis.schema.model.SchemaEntity;
import lv.lumii.obis.schema.model.SchemaRole;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;

public class SchemaExtractor {

	public Schema extractSchema(String sparqlEndpointUrl, String graphName, String mode){
		
		SparqlEndpointProcessor sparqlEndpointProcessor = new SparqlEndpointProcessor(sparqlEndpointUrl, graphName);
		
		logStartMessage(sparqlEndpointUrl, graphName);
		
		Schema schema = new Schema();
		schema.setName((graphName != null) ? graphName + "_Schema" : "Schema");
		
		// find all classes
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(SchemaExtractorQueries.FIND_ALL_CLASSES);
		List<SchemaClass> classes = processClasses(queryResults);
		schema.setClasses(classes);
		queryResults.clear();	
		
		// find intersection classes
		queryResults = sparqlEndpointProcessor.read(SchemaExtractorQueries.FIND_INTERSECTION_CLASSES);
		Map<String, SchemaClassNodeInfo> classesGraph = buildClassGraph(queryResults);
		queryResults.clear();
		addMissingClasses(classes, classesGraph);
		
		// find instances counts for all classes
		queryResults = sparqlEndpointProcessor.read(SchemaExtractorQueries.FIND_INSTANCES_COUNT);
		processInstanceCount(queryResults, classesGraph);
		queryResults.clear();
		
		// sort classes by neighbors and instances count (ascending)
		classesGraph = sortClassesByNeighbors(classesGraph);
		
		// find superclasses
		processSuperclasses(classesGraph, classes, sparqlEndpointProcessor);
		
		// validate and update classes
		validateAndNormalizeSuperclasses(classesGraph, classes, sparqlEndpointProcessor);
		
		// find all properties (attributes + associations) and domain class instances count
		queryResults = sparqlEndpointProcessor.read(SchemaExtractorQueries.FIND_ALL_PROPERTIES);
		Map<String, SchemaPropertyNodeInfo> properties = processAllProperties(queryResults);
		queryResults.clear();
		
		// find associations and range class instances count
		queryResults = sparqlEndpointProcessor.read(SchemaExtractorQueries.FIND_OBJECT_PROPERTIES_WITH_RANGE);
		processAssociations(queryResults, properties);
		queryResults.clear();
		
		// map properties to domain classes
		mapPropertiesToDomainClasses(classes, properties);
		
		// map properties to range classes
		mapPropertiesToRangeClasses(classes, properties, sparqlEndpointProcessor);
		
		// data type and cardinality calculation may impact performance
		if(!EXTRACT_MODE_SIMPLE.equalsIgnoreCase(mode)){
			// find data types for attributes
			processDataTypes(properties, sparqlEndpointProcessor);		
			// find min/max cardinalities
			if(!EXTRACT_MODE_DATA.equalsIgnoreCase(mode)){
				processCardinalities(properties, sparqlEndpointProcessor);
			}
		}
		
		// fill schema object with attributes and associations
		formatProperties(properties, schema);
		
		// find main namespace
		schema.setDefaultNamespace(findMainNamespace(schema));
		schema.setMultipleNamespaces(false);

		logEndMessage();
		
		return schema;
	}
	
	private List<SchemaClass> processClasses(List<QueryResult> queryResults){
		List<SchemaClass> classes = new ArrayList<>();
		for(QueryResult queryResult: queryResults){
			String value = queryResult.get(SchemaExtractorQueries.BINDING_NAME_CLASS);
			if(value != null){
				SchemaClass c = new SchemaClass();
				setLocalNameAndNamespace(value, c);
				classes.add(c);
			}
		}
		return classes;
	}

	private Map<String, SchemaClassNodeInfo> buildClassGraph(List<QueryResult> queryResults){
		Map<String, SchemaClassNodeInfo> classes = new HashMap<>();
		for(QueryResult queryResult: queryResults){
			String classA = queryResult.get(SchemaExtractorQueries.BINDING_NAME_CLASS_A);
			String classB = queryResult.get(SchemaExtractorQueries.BINDING_NAME_CLASS_B);
			if(classA != null && classB != null){
				if(!classes.containsKey(classA)){
					classes.put(classA, new SchemaClassNodeInfo());
				}
				classes.get(classA).getNeighbors().add(classB);
			}
		}
		return classes;
	}
	
	private void addMissingClasses(List<SchemaClass> classes, Map<String, SchemaClassNodeInfo> classesGraph) {
		classes.forEach(c -> {
			if(!classesGraph.containsKey(c.getFullName())) {
				classesGraph.put(c.getFullName(), new SchemaClassNodeInfo());
			}
		});
	}
	
	private void processInstanceCount(List<QueryResult> queryResults, Map<String, SchemaClassNodeInfo> classes){
		for(QueryResult queryResult: queryResults){
			String className = queryResult.get(SchemaExtractorQueries.BINDING_NAME_CLASS);
			String instancesCount = queryResult.get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);
			if(className != null && instancesCount != null){
				SchemaClassNodeInfo classInfo = classes.get(className);
				if(classInfo != null){
					try {
						classInfo.setInstanceCount(Integer.valueOf(instancesCount));
					} catch (Exception e){
						classInfo.setInstanceCount(0);
					}
				}
			}
		}
	}
	
	private Map<String, SchemaPropertyNodeInfo> processAllProperties(List<QueryResult> queryResults){
		Map<String, SchemaPropertyNodeInfo> properties = new HashMap<>();
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaExtractorQueries.BINDING_NAME_PROPERTY);
			String className = queryResult.get(SchemaExtractorQueries.BINDING_NAME_CLASS);
			String instances = queryResult.get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);

			if(!properties.containsKey(propertyName)){
				properties.put(propertyName, new SchemaPropertyNodeInfo());
			}
			SchemaPropertyNodeInfo property = properties.get(propertyName);
			SchemaClassNodeInfo classInfo = new SchemaClassNodeInfo();
			classInfo.setClassName(className);
			classInfo.setInstanceCount(Integer.valueOf(instances));
			property.getDomainClasses().add(classInfo);
		}
		return properties;
	}
	
	private void processAssociations(List<QueryResult> queryResults, Map<String, SchemaPropertyNodeInfo> properties){
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaExtractorQueries.BINDING_NAME_PROPERTY);
			String className = queryResult.get(SchemaExtractorQueries.BINDING_NAME_CLASS);
			String instances = queryResult.get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);
			
			if(properties.containsKey(propertyName)){
				SchemaPropertyNodeInfo property = properties.get(propertyName);
				property.setIsObjectProperty(Boolean.TRUE);
				SchemaClassNodeInfo classInfo = new SchemaClassNodeInfo();
				classInfo.setClassName(className);
				classInfo.setInstanceCount(Integer.valueOf(instances));
				property.getRangeClasses().add(classInfo);
			}			
		}
	}
	
	private Map<String, SchemaClassNodeInfo> sortClassesByNeighbors(Map<String, SchemaClassNodeInfo> classes){
		return classes.entrySet().stream()
				.sorted((o1, o2) -> {
					Integer o1Size = o1.getValue().getNeighbors().size();
					Integer o2Size = o2.getValue().getNeighbors().size();
					int compareResult = o1Size.compareTo(o2Size);
					if(compareResult == 0) {
						compareResult = o1.getValue().getInstanceCount().compareTo(o2.getValue().getInstanceCount());
					}
					if(compareResult == 0){
						compareResult = compareClassNames(o1.getValue().getClassName(), o2.getValue().getClassName());
					}
					return compareResult;
				})
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
	}

	// sort class neighbors by instance count (ascending)
	private List<String> sortNeighborsByInstances(List<String> neighbors, Map<String, SchemaClassNodeInfo> classesGraph){
		return neighbors.stream()
				.sorted((o1, o2) -> {
					Integer neighborInstances1 = classesGraph.get(o1).getInstanceCount();
					Integer neighborInstances2 = classesGraph.get(o2).getInstanceCount();
					int compareResult = neighborInstances1.compareTo(neighborInstances2);
					if(compareResult == 0){
						compareResult = compareClassNames(classesGraph.get(o1).getClassName(), classesGraph.get(o2).getClassName());
					}
					return compareResult;
				})
				.collect(Collectors.toList());
	}
	
	// sort classes by instance count (descending)
	private List<SchemaClass> sortClassesByInstances(List<SchemaClass> classes, Map<String, SchemaClassNodeInfo> classesGraph){
		return classes.stream()
				.sorted((o1, o2) -> {
					Integer neighborInstances1 = classesGraph.get(o1.getFullName()).getInstanceCount();
					Integer neighborInstances2 = classesGraph.get(o2.getFullName()).getInstanceCount();
					int compareResult = neighborInstances2.compareTo(neighborInstances1);
					if(compareResult == 0){
						compareResult = compareClassNames(o2.getFullName(), o1.getFullName());
					}
					return compareResult;
				})
				.collect(Collectors.toList());
	}

	private int compareClassNames(String className1, String className2){
		if((className1 == null && className2 == null) || (className1 != null && className2 == null)){
			return 1;
		}
		if(className1 == null){
			return 0;
		}
		return className1.compareTo(className2);
	}
	
	private void processSuperclasses(Map<String, SchemaClassNodeInfo> classesGraph, List<SchemaClass> classes, SparqlEndpointProcessor sparqlEndpointProcessor){
		
		for(Entry<String, SchemaClassNodeInfo> entry: classesGraph.entrySet()){
			
			SchemaClass currentClass = findClass(classes, entry.getKey());			
			if(currentClass == null || THING_NAME.equals(currentClass.getLocalName())){
				continue;
			}
			
			// sort neighbor list by instance count (ascending)
			List<String> neighbors = sortNeighborsByInstances(entry.getValue().getNeighbors(),classesGraph);
			
			// find the class with the smallest number of instances but including all current instances
			SchemaClassNodeInfo currentClassInfo = entry.getValue();
			updateClass(currentClass, currentClassInfo, neighbors, classesGraph, classes, sparqlEndpointProcessor);
			
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
	private void validateAndNormalizeSuperclasses(Map<String, SchemaClassNodeInfo> classesGraph, List<SchemaClass> classes,
			SparqlEndpointProcessor sparqlEndpointProcessor){

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
			boolean accessible = validateAllNeighbors(currentClass, classInfo, classes, classesGraph, sparqlEndpointProcessor);
			while(!accessible && maxCounter != 0){
				maxCounter--;
				accessible = validateAllNeighbors(currentClass, classInfo, classes, classesGraph, sparqlEndpointProcessor);
			}
		}
	}
	
	private boolean validateAllNeighbors(SchemaClass currentClass, SchemaClassNodeInfo currentClassInfo, List<SchemaClass> classes,
			Map<String, SchemaClassNodeInfo> classesGraph, SparqlEndpointProcessor sparqlEndpointProcessor){
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
			updateClass(currentClass, currentClassInfo, sortedNeighbors, classesGraph, classes, sparqlEndpointProcessor);
		}
		
		return accessible;
	}
	
	private void updateClass(SchemaClass currentClass, SchemaClassNodeInfo currentClassInfo, List<String> neighbors, 
			Map<String, SchemaClassNodeInfo> classesGraph, List<SchemaClass> classes, SparqlEndpointProcessor sparqlEndpointProcessor){
		
		for(String neighbor: neighbors){
			int neighborInstances = classesGraph.get(neighbor).getInstanceCount();
			if(neighborInstances < currentClassInfo.getInstanceCount()){
				continue;
			}
			String query = SchemaExtractorQueries.CHECK_SUPERCLASS;
			query = query.replace("?" + SchemaExtractorQueries.BINDING_NAME_CLASS_A, "<" + currentClass.getFullName() +">");
			query = query.replace("?" + SchemaExtractorQueries.BINDING_NAME_CLASS_B, "<" + neighbor +">");
			List<QueryResult> queryResults = sparqlEndpointProcessor.read(query);
			if(queryResults == null || queryResults.isEmpty()){
				continue;
			}
			String instances = queryResults.get(0).get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);
			SchemaClass superClass = findClass(classes, neighbor);
			if(Integer.valueOf(instances) == 0 && !hasCyclicDependency(currentClass, superClass, classes)){
				currentClass.getSuperClasses().add(neighbor);
				if(superClass != null){
					superClass.getSubClasses().add(currentClass.getFullName());
				}
				break;
			}
		}
	}

	private boolean hasCyclicDependency(SchemaClass currentClass, SchemaClass newClass, List<SchemaClass> classes){
		if(newClass == null){
			return false;
		}
		if(currentClass.getSuperClasses().contains(newClass.getFullName()) || currentClass.getSubClasses().contains(newClass.getFullName())){
			return true;
		}
		return isClassAccessibleFromSuperclasses(newClass.getFullName(), currentClass.getFullName(), classes);
	}
	
	private boolean isClassAccessibleFromSuperclasses(String currentClass, String neighbor, List<SchemaClass> classes){
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
	
	private boolean isClassAccessibleFromSubclasses(String currentClass, String neighbor, List<SchemaClass> classes){
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
	
	private SchemaClass findClass(List<SchemaClass> classes, String className){
		return classes.stream().filter(
				schemaClass -> schemaClass.getFullName().equals(className) || schemaClass.getLocalName().equals(className))
				.findFirst().orElse(null);
	}
	
	private void setLocalNameAndNamespace(String fullName, SchemaEntity entity){
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
	
	private void mapPropertiesToDomainClasses(List<SchemaClass> classes, Map<String, SchemaPropertyNodeInfo> properties){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			SchemaClassNodeInfo classWithMaxCount = null;
			int maxCount = 0;
			for(SchemaClassNodeInfo classInfo: p.getValue().getDomainClasses()){
				SchemaClass domainClass = findClass(classes, classInfo.getClassName());
				if(domainClass != null && domainClass.getSubClasses().isEmpty() && domainClass.getSuperClasses().isEmpty()){
					addDomainClassToProperty(classInfo, p, classes);
				} else if(classInfo.getInstanceCount() > maxCount){
					maxCount = classInfo.getInstanceCount();
					classWithMaxCount = classInfo;
				}
			}
			if(classWithMaxCount != null){
				addDomainClassToProperty(classWithMaxCount, p, classes);
			}
		}
	}

	private void mapPropertiesToRangeClasses(List<SchemaClass> classes, Map<String, SchemaPropertyNodeInfo> properties, SparqlEndpointProcessor sparqlEndpointProcessor){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			if(!p.getValue().getIsObjectProperty()){
				continue;
			}
			SchemaClassNodeInfo classWithMaxCount = null;
			int maxCount = 0;
			for(SchemaClassNodeInfo classInfo: p.getValue().getRangeClasses()){
				SchemaClass rangeClass = findClass(classes, classInfo.getClassName());
				if(rangeClass != null && rangeClass.getSubClasses().isEmpty() && rangeClass.getSuperClasses().isEmpty()){
					addRangeClassToProperty(classInfo, p, classes, sparqlEndpointProcessor);
				} else if(classInfo.getInstanceCount() > maxCount){
					maxCount = classInfo.getInstanceCount();
					classWithMaxCount = classInfo;
				}
			}
			if(classWithMaxCount != null){
				addRangeClassToProperty(classWithMaxCount, p, classes, sparqlEndpointProcessor);
			}
		}
	}

	private void addDomainClassToProperty(SchemaClassNodeInfo classInfo, Entry<String, SchemaPropertyNodeInfo> property, List<SchemaClass> classes){
		String propertyDomain = findPropertyClass(classInfo, property.getKey(), property.getValue().getDomainClasses(), classes);
		if(!SchemaUtil.isEmpty(propertyDomain)){
			property.getValue().getDomainRangePairs().add(new SchemaDomainRangeInfo(propertyDomain));
		}
	}

	private void addRangeClassToProperty(SchemaClassNodeInfo classInfo, Entry<String, SchemaPropertyNodeInfo> property, List<SchemaClass> classes,
										 SparqlEndpointProcessor sparqlEndpointProcessor){
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
					if(checkDomainRangeMapping(domain, propertyRange, property.getKey(), sparqlEndpointProcessor)){
						createNewOrUpdateDomainRangeEntry(domain, propertyRange, domainRangePairs);
					}
				});
			}
		}
	}

	private void createNewOrUpdateDomainRangeEntry(String domainClass, String rangeClass, List<SchemaDomainRangeInfo> domainRangePairs){
		SchemaDomainRangeInfo domainRangeInfo = domainRangePairs.stream()
				.filter(pair -> domainClass.equals(pair.getDomainClass()) && pair.getRangeClass() == null)
				.findFirst().orElse(null);
		if(domainRangeInfo == null){
			domainRangeInfo = new SchemaDomainRangeInfo(domainClass);
			domainRangePairs.add(domainRangeInfo);
		}
		domainRangeInfo.setRangeClass(rangeClass);
	}

	private boolean checkDomainRangeMapping(String domainClass, String rangeClass, String property, SparqlEndpointProcessor sparqlEndpointProcessor){
		String query = SchemaExtractorQueries.CHECK_DOMAIN_RANGE_MAPPING;
		query = query.replace(SchemaExtractorQueries.BINDING_NAME_CLASS_A, domainClass);
		query = query.replace(SchemaExtractorQueries.BINDING_NAME_CLASS_B, rangeClass);
		query = query.replace(SchemaExtractorQueries.BINDING_NAME_PROPERTY, property);
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(query);
		if(queryResults == null || queryResults.isEmpty()){
			return false;
		}
		String instances = queryResults.get(0).get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);
		return Integer.valueOf(instances) != 0;
	}
	
	private String findPropertyClass(SchemaClassNodeInfo classWithMaxCount, String propertyName, List<SchemaClassNodeInfo> assignedClasses, List<SchemaClass> classes){
		SchemaClass rootClass = findClass(classes, classWithMaxCount.getClassName());
		if(rootClass == null){
			logErrorMessage("propertyDomainOrRange", propertyName, classWithMaxCount.getClassName());
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
	
	private void processDataTypes(Map<String, SchemaPropertyNodeInfo> properties, SparqlEndpointProcessor sparqlEndpointProcessor){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			if(p.getValue().getIsObjectProperty()){
				continue;
			}
			SchemaPropertyNodeInfo property = p.getValue();	
			
			String query = SchemaExtractorQueries.FIND_DATA_TYPE.replace(
					SchemaExtractorQueries.BINDING_NAME_PROPERTY, p.getKey());
			List<QueryResult> queryResults = sparqlEndpointProcessor.read(query);
			if(queryResults == null || queryResults.isEmpty() || queryResults.size() > 1){
				property.setDataType(DATA_TYPE_XSD_DEFAULT);
				continue;
			}
			String resultDataType = queryResults.get(0).get(SchemaExtractorQueries.BINDING_NAME_DATA_TYPE);
			if(SchemaUtil.isEmpty(resultDataType)){
				property.setDataType(DATA_TYPE_XSD_DEFAULT);
			} else {
				property.setDataType(SchemaUtil.parseDataType(resultDataType));
			}
		}
	}
	
	private void processCardinalities(Map<String, SchemaPropertyNodeInfo> properties, SparqlEndpointProcessor sparqlEndpointProcessor){
		for(Entry<String, SchemaPropertyNodeInfo> p: properties.entrySet()){
			SchemaPropertyNodeInfo property = p.getValue();			
			setMaxCardinality(p.getKey(), property, sparqlEndpointProcessor);
			setMinCardinality(p.getKey(), property, sparqlEndpointProcessor);
		}
	}
	
	private void setMaxCardinality(String propertyName, SchemaPropertyNodeInfo property, SparqlEndpointProcessor sparqlEndpointProcessor){
		String query = SchemaExtractorQueries.FIND_MAX_CARDINALITY.replace(
				SchemaExtractorQueries.BINDING_NAME_PROPERTY, propertyName);
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(query);
		if(queryResults == null || queryResults.isEmpty()){
			property.setMaxCardinality(null);
			return;
		}
		boolean allEqual = true;
		String patternValue = queryResults.get(0).get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);
		for (QueryResult item : queryResults) {
		    if(!item.get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT).equals(patternValue)){
		    	 allEqual = false;
		    	 break;
		    }
		}
		if(allEqual){
			property.setMaxCardinality(Integer.valueOf(patternValue));
		} else {
			property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
		}
	}
	
	private void setMinCardinality(String propertyName, SchemaPropertyNodeInfo property, SparqlEndpointProcessor sparqlEndpointProcessor){
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
			String query = SchemaExtractorQueries.FIND_MIN_CARDINALITY.
					replace(SchemaExtractorQueries.BINDING_NAME_PROPERTY, propertyName).
					replace(SchemaExtractorQueries.BINDING_NAME_CLASS, domain);
			queryResults = sparqlEndpointProcessor.read(query);
			if(queryResults == null || queryResults.isEmpty()){
				continue;
			}
			String instances = queryResults.get(0).get(SchemaExtractorQueries.BINDING_NAME_INSTANCES_COUNT);
			if(Integer.valueOf(instances) != 0){
				minCardinality = DEFAULT_MIN_CARDINALITY;
			}
		}
		property.setMinCardinality(minCardinality);
	}
	
	private void formatProperties(Map<String, SchemaPropertyNodeInfo> properties, Schema schema){
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
	
	private String findMainNamespace(Schema schema){
		Map<String, String> namespaces = new HashMap<>();
		for(SchemaClass item: schema.getClasses()){
			namespaces.put(item.getNamespace(), item.getNamespace());
		}
		for(SchemaAttribute item: schema.getAttributes()){
			namespaces.put(item.getNamespace(), item.getNamespace());
		}
		for(SchemaRole item: schema.getAssociations()){
			namespaces.put(item.getNamespace(), item.getNamespace());
		}
		if(namespaces.size() == 1){
			return namespaces.keySet().iterator().next();
		}
		return null;
	}

	private void logStartMessage(String... args){
		System.out.println("Starting to read schema from URL [" + args[0] + "], grapName [" + args[1]  + "]");
	}
	private void logEndMessage(){
		System.out.println("Schema extraction completed");
	}
	private void logErrorMessage(String... args){
		System.out.println("Error - null " + args[0] + " - sourceItem [" + args[1] + "], targetItem [" + args[2] + "]");
	}

}

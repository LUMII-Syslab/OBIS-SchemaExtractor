package lv.lumii.obis.schema.services;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lv.lumii.obis.schema.model.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import lv.lumii.obis.schema.services.dto.SchemaCardinalityInfo;
import lv.lumii.obis.schema.services.dto.SchemaCardinalityInfo.CardinalityType;
import org.semanticweb.owlapi.search.EntitySearcher;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;

public class OwlOntologyReader {

	public Schema readOwlOntology(String filePath) {
		try {
			InputStream in = new FileInputStream(filePath);
			return readOwlOntology(in);
		} catch (FileNotFoundException e){
			throw new IllegalArgumentException("File: " + filePath + " not found");
		}
	}
	
	public Schema readOwlOntology(InputStream inputStream) {
		OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
		OWLOntology ontology = null;
		try {
			ontology = manager.loadOntologyFromOntologyDocument(inputStream);
		} catch (OWLOntologyCreationException e) {
			e.printStackTrace();
		}
		if(ontology == null || ontology.isEmpty() || ontology.classesInSignature().count() <= 0) {
			return null;
		}
		return processOwlOntology(ontology, manager);		
	}
	
	private Schema processOwlOntology(OWLOntology ontology, OWLOntologyManager manager) {
		IRI ontologyIRI = ontology.getOntologyID().getOntologyIRI().orElse(null);
		
		Schema schema = new Schema();		
		schema.setName((ontologyIRI != null) ? ontologyIRI.getIRIString() : null);
		processPrefixes(ontology, schema);
		processAnnotations(ontology.annotations(), schema);

		Map<String, SchemaClass> classesMap = new HashMap<>();
		Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap = new HashMap<>();
		
		processClasses(ontology, schema, classesMap, cardinalitiesMap, manager);
		processDataTypeProperties(ontology, schema, classesMap, cardinalitiesMap);
		processObjectTypeProperties(ontology, schema, classesMap, cardinalitiesMap);
		processInverseObjectTypeProperties(ontology, schema);

		processMultipleNamespaces(schema);
		
		return schema;
	}
	
	private void processPrefixes(OWLOntology ontology, Schema schema) {
		OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
		if (format != null && Boolean.TRUE.equals(format.isPrefixOWLDocumentFormat())) {
			schema.setNamespaceMap(format.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap());
		}
	}

	private void processMultipleNamespaces(Schema schema){
		if(schema.getUsedNamespacePrefixMap().size() <= 1){
			schema.setMultipleNamespaces(false);
		} else {
			schema.setMultipleNamespaces(true);
		}
	}
	
	private void processClasses(OWLOntology ontology, Schema schema, Map<String, SchemaClass> classesMap, 
			Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap, OWLOntologyManager manager) {
		
		SchemaClass thingClass = createThingClass(ontology, classesMap, manager, schema);
		
		List<OWLClass> owlClasses = ontology.classesInSignature().filter(
				c -> !isExcludedClass(c)).collect(Collectors.toList());
		if(owlClasses == null || owlClasses.isEmpty()) {
			return;
		}
		
		for(OWLClass clazz: owlClasses) {
			IRI currentClassIri = clazz.getIRI();
			String currentClassFullName = currentClassIri.toString();
			SchemaClass currentClass = classesMap.get(currentClassFullName);
			if(currentClass == null){
				currentClass = new SchemaClass();
				setLocalNameAndNamespace(currentClassIri, currentClass, schema);
				classesMap.put(currentClassFullName, currentClass);
			}
			
			// process superclasses
			boolean hasRealSuperClass = false;
			List<IRI> superClasses = ontology.axioms(AxiomType.SUBCLASS_OF)
					.filter(axiom -> isValidSubClass(axiom, currentClassFullName))
					.map(axiom -> axiom.getSuperClass().asOWLClass().getIRI())
					.collect(Collectors.toList());
			for(IRI superClassIri: superClasses) {
				addSuperClass(superClassIri, currentClass, classesMap, schema);
				hasRealSuperClass = true;
			}		
			if(!hasRealSuperClass){
				thingClass.getSubClasses().add(currentClassFullName);
				currentClass.getSuperClasses().add(THING_URI);
			}
			
			// process cardinalities
			getCardinalities(ontology, clazz, cardinalitiesMap);

			processAnnotations(EntitySearcher.getAnnotations(clazz, ontology), currentClass);

		}
		
		schema.setClasses(new ArrayList<>(classesMap.values()));
	}
	
	private SchemaClass createThingClass(OWLOntology ontology, Map<String, SchemaClass> classesMap, 
			OWLOntologyManager manager, Schema schema) {
		OWLClass thing = ontology.classesInSignature().filter(OWLClassExpression::isOWLThing).findFirst().orElse(null);
		if(thing == null) {
			thing = manager.getOWLDataFactory().getOWLClass(IRI.create(THING_URI));
		}
		SchemaClass resultThing = new SchemaClass();
		setLocalNameAndNamespace(thing.getIRI(), resultThing, schema);
		processAnnotations(EntitySearcher.getAnnotations(thing, ontology), resultThing);
		classesMap.put(THING_URI, resultThing);
		return resultThing;
	}
	
	private boolean isExcludedClass(OWLClass clazz) {
		return clazz.isAnonymous() || clazz.isOWLThing() || EXCLUDED_URI.contains(clazz.getIRI().toString());
	}
	
	private boolean isValidSubClass(OWLSubClassOfAxiom axiom, String currentClassIri) {
		return (axiom.getSubClass() instanceof OWLClass)
				&& (axiom.getSuperClass() instanceof OWLClass)
				&& currentClassIri.equals(axiom.getSubClass().asOWLClass().getIRI().toString());
	}
	
	private void addSuperClass(IRI superClassIri, SchemaClass subClass, Map<String, SchemaClass> classesMap, Schema schema) {
		SchemaClass superClass = classesMap.get(superClassIri.toString());
		if(superClass == null) {
			superClass = new SchemaClass();
			setLocalNameAndNamespace(superClassIri, superClass, schema);
			classesMap.put(superClassIri.toString(), superClass);
		}
		superClass.getSubClasses().add(subClass.getFullName());
		subClass.getSuperClasses().add(superClassIri.toString());
	}
	
	private void getCardinalities(OWLOntology ontology, OWLClass clazz, Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap) {
		for(OWLAxiom axiom : ontology.axioms(clazz).collect( Collectors.toSet())) {		
			
			axiom.accept(new OWLObjectVisitor() {
				
				public void visit(OWLSubClassOfAxiom subClassAxiom) {
					subClassAxiom.getSuperClass().accept( new OWLObjectVisitor() {

						public void visit(OWLObjectExactCardinality exactCardinalityAxiom) {
							fillCardinality(exactCardinalityAxiom, CardinalityType.EXACT_CARDINALITY, cardinalitiesMap);
						}
						public void visit(OWLDataExactCardinality exactCardinalityAxiom) {
							fillCardinality(exactCardinalityAxiom, CardinalityType.EXACT_CARDINALITY, cardinalitiesMap);
						}

						public void visit(OWLObjectMinCardinality minCardinalityAxiom) {
							fillCardinality(minCardinalityAxiom, CardinalityType.MIN_CARDINALITY, cardinalitiesMap);
						}
						public void visit(OWLDataMinCardinality minCardinalityAxiom) {
							fillCardinality(minCardinalityAxiom, CardinalityType.MIN_CARDINALITY, cardinalitiesMap);
						}

						public void visit(OWLObjectMaxCardinality maxCardinalityAxiom) {
							fillCardinality(maxCardinalityAxiom, CardinalityType.MAX_CARDINALITY, cardinalitiesMap);
						}
						public void visit(OWLDataMaxCardinality maxCardinalityAxiom) {
							fillCardinality(maxCardinalityAxiom, CardinalityType.MAX_CARDINALITY, cardinalitiesMap);
						}
					});
				}
			});

		}
	}
	
	private void fillCardinality(OWLCardinalityRestriction<?> restriction, CardinalityType cardinalityType,
			Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap) {
		if(!(restriction.getProperty() instanceof OWLProperty)) {
			return;
		}
		String property = ((OWLProperty)restriction.getProperty()).toStringID();
		int cardinality = restriction.getCardinality();
		
		List<SchemaCardinalityInfo> cardinalities = new ArrayList<>();
		if(cardinalitiesMap.containsKey(property)) {
			cardinalities = cardinalitiesMap.get(property);
		} else {
			cardinalitiesMap.put(property, cardinalities);
		}
		cardinalities.add(new SchemaCardinalityInfo(cardinalityType, cardinality));
	}
	
	private void processDataTypeProperties(OWLOntology ontology, Schema schema, Map<String, SchemaClass> classesMap,
			Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap) {
		
		List<OWLDataPropertyDomainAxiom> domains = ontology.axioms(AxiomType.DATA_PROPERTY_DOMAIN).collect(Collectors.toList());
		List<OWLDataPropertyRangeAxiom> ranges = ontology.axioms(AxiomType.DATA_PROPERTY_RANGE).collect(Collectors.toList());
		List<OWLDataProperty> dataProperties = ontology.dataPropertiesInSignature().collect(Collectors.toList());
		
		for(OWLDataProperty property: dataProperties) {
			String propertyName = property.getIRI().toString();
			SchemaAttribute dataProperty = new SchemaAttribute();
			
			setLocalNameAndNamespace(property.getIRI(), dataProperty, schema);
			setDomain(dataProperty, propertyName, domains, classesMap);
			setRangeType(dataProperty, propertyName, ranges);
			setCardinalities(dataProperty, propertyName, cardinalitiesMap);
			processAnnotations(EntitySearcher.getAnnotations(property, ontology), dataProperty);

			schema.getAttributes().add(dataProperty);
		}
	}
	
	private void setDomain(SchemaAttribute dataProperty, String propertyName, List<OWLDataPropertyDomainAxiom> domains, Map<String, SchemaClass> classesMap) {
		OWLDataPropertyDomainAxiom domainAxiom = domains.stream().filter(d -> propertyName.equals(
				d.getProperty().asOWLDataProperty().getIRI().toString())).findFirst().orElse(null);
		if(isValidDomainClass(domainAxiom)) {
			String domainUri = domainAxiom.getDomain().asOWLClass().getIRI().toString();
			if(classesMap.containsKey(domainUri)) {
				dataProperty.getSourceClasses().add(domainUri);
			}
		}
		if(dataProperty.getSourceClasses().isEmpty()) {
			dataProperty.getSourceClasses().add(THING_URI);
		}
	}
	
	private void setRangeType(SchemaAttribute dataProperty, String propertyName, List<OWLDataPropertyRangeAxiom> ranges) {
		OWLDataPropertyRangeAxiom rangeAxiom = ranges.stream().filter(d -> propertyName.equals(
				d.getProperty().asOWLDataProperty().getIRI().toString())).findFirst().orElse(null);
		if(isValidSimpleRangeType(rangeAxiom)) {
			String parsedDataType = parseDataType(rangeAxiom.getRange().asOWLDatatype().getIRI().toString());
			dataProperty.setType(parsedDataType);
		} else if(isValidLookupRangeType(rangeAxiom)){
			List<String> lookups = ((OWLDataOneOf)rangeAxiom.getRange()).values().map(OWLLiteral::getLiteral).collect(Collectors.toList());
			if(lookups != null && !lookups.isEmpty()){
				String lookupString = String.join(";", lookups);
				dataProperty.setRangeLookupValues(lookupString);
			}
		}
		if(dataProperty.getType() == null) {
			dataProperty.setType(DEFAULT_XSD_DATA_TYPE);
		}
	}
	
	private void setCardinalities(SchemaProperty property, String propertyName, Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap) {
		if(!cardinalitiesMap.containsKey(propertyName)){
			return;
		}

		Integer exactCardinality = cardinalitiesMap.get(propertyName).stream()
				.filter(c -> CardinalityType.EXACT_CARDINALITY.equals(c.getCardinalityType()))
				.findFirst().orElse(new SchemaCardinalityInfo(CardinalityType.EXACT_CARDINALITY, null))
				.getCardinality();
		if(exactCardinality != null && exactCardinality > 0) {
			property.setMinCardinality(exactCardinality);
			property.setMaxCardinality(exactCardinality);
			return;
		}

		Integer minCardinality = cardinalitiesMap.get(propertyName).stream()
				.filter(c -> CardinalityType.MIN_CARDINALITY.equals(c.getCardinalityType()))
				.findFirst().orElse(new SchemaCardinalityInfo(CardinalityType.MIN_CARDINALITY, null))
				.getCardinality();
		if(minCardinality != null) {
			property.setMinCardinality(minCardinality);
		}		

		Integer maxCardinality = cardinalitiesMap.get(propertyName).stream()
				.filter(c -> CardinalityType.MAX_CARDINALITY.equals(c.getCardinalityType()))
				.findFirst().orElse(new SchemaCardinalityInfo(CardinalityType.MAX_CARDINALITY, null))
				.getCardinality();
		if(maxCardinality != null) {
			property.setMaxCardinality(maxCardinality);
		}

	}

	private void processObjectTypeProperties(OWLOntology ontology, Schema schema, Map<String, SchemaClass> classesMap,
			Map<String, List<SchemaCardinalityInfo>> cardinalitiesMap) {
		
		List<OWLObjectPropertyDomainAxiom> domains = ontology.axioms(AxiomType.OBJECT_PROPERTY_DOMAIN).collect(Collectors.toList());
		List<OWLObjectPropertyRangeAxiom> ranges = ontology.axioms(AxiomType.OBJECT_PROPERTY_RANGE).collect(Collectors.toList());
		List<OWLObjectProperty> objectProperties = ontology.objectPropertiesInSignature().collect(Collectors.toList());
		
		for(OWLObjectProperty property: objectProperties) {
			String propertyName = property.getIRI().toString();
			
			SchemaRole objectProperty = new SchemaRole();
			setLocalNameAndNamespace(property.getIRI(), objectProperty, schema);
			
			ClassPair classPair = new ClassPair();
			
			setDomain(propertyName, domains, classesMap, classPair);
			setRange(propertyName, ranges, classesMap, classPair);
			setCardinalities(objectProperty, propertyName, cardinalitiesMap);
			processAnnotations(EntitySearcher.getAnnotations(property, ontology), objectProperty);
			
			objectProperty.getClassPairs().add(classPair);
			schema.getAssociations().add(objectProperty);
		}
	}

	private void processInverseObjectTypeProperties(OWLOntology ontology, Schema schema) {
		List<OWLInverseObjectPropertiesAxiom> inverseProperties = ontology.axioms(AxiomType.INVERSE_OBJECT_PROPERTIES).collect(Collectors.toList());
		for(OWLInverseObjectPropertiesAxiom inverse: inverseProperties){
			OWLObjectPropertyExpression first = inverse.getFirstProperty();
			OWLObjectPropertyExpression second = inverse.getSecondProperty();
			if(first == null || first.getNamedProperty() == null || second == null || second.getNamedProperty() == null){
				continue;
			}
			SchemaRole firstRole = schema.getAssociations().stream()
					.filter(r -> r.getFullName().equalsIgnoreCase(first.getNamedProperty().getIRI().toString()))
					.findFirst().orElse(null);
			SchemaRole secondRole = schema.getAssociations().stream()
					.filter(r -> r.getFullName().equalsIgnoreCase(second.getNamedProperty().getIRI().toString()))
					.findFirst().orElse(null);
			if(firstRole == null || secondRole == null){
				continue;
			}
			firstRole.setInverseProperty(secondRole);
			secondRole.setInverseProperty(firstRole);
		}
	}

	private void setDomain(String propertyName, List<OWLObjectPropertyDomainAxiom> domains,
			Map<String, SchemaClass> classesMap, ClassPair classPair) {
		OWLObjectPropertyDomainAxiom domainAxiom = domains.stream().filter(d -> propertyName.equals(
				d.getProperty().asOWLObjectProperty().getIRI().toString())).findFirst().orElse(null);
		if(isValidDomainClass(domainAxiom)) {
			String domainUri = domainAxiom.getDomain().asOWLClass().getIRI().toString();
			if(classesMap.containsKey(domainUri)) {
				classPair.setSourceClass(domainUri);
			}
		}
		if(classPair.getSourceClass() == null) {
			classPair.setSourceClass(THING_URI);
		}
	}
	
	private void setRange(String propertyName, List<OWLObjectPropertyRangeAxiom> ranges, 
			Map<String, SchemaClass> classesMap, ClassPair classPair) {
		OWLObjectPropertyRangeAxiom rangeAxiom = ranges.stream().filter(d -> propertyName.equals(
				d.getProperty().asOWLObjectProperty().getIRI().toString())).findFirst().orElse(null);
		if(isValidRangeClass(rangeAxiom)) {
			String rangeUri = rangeAxiom.getRange().asOWLClass().getIRI().toString();
			if(classesMap.containsKey(rangeUri)) {
				classPair.setTargetClass(rangeUri);
			}
		}
		if(classPair.getTargetClass() == null) {
			classPair.setTargetClass(THING_URI);
		}
	}
	
	private boolean isValidDomainClass(OWLPropertyDomainAxiom<?> axiom) {
		return axiom != null && axiom.getDomain() != null && axiom.getDomain().isOWLClass();
	}
	private boolean isValidSimpleRangeType(OWLDataPropertyRangeAxiom axiom) {
		return axiom != null && axiom.getRange() != null && axiom.getRange().isOWLDatatype();
	}
	private boolean isValidLookupRangeType(OWLDataPropertyRangeAxiom axiom) {
		return axiom != null && axiom.getRange() != null && (axiom.getRange() instanceof OWLDataOneOf);
	}
	private boolean isValidRangeClass(OWLObjectPropertyRangeAxiom axiom) {
		return axiom != null && axiom.getRange() != null && axiom.getRange().isOWLClass();
	}
	
	private void setLocalNameAndNamespace(IRI entityIri, SchemaEntity entity, Schema schema){
		entity.setLocalName(entityIri.getShortForm());
		entity.setFullName(entityIri.toString());
		entity.setNamespace(entityIri.getNamespace());

		updateResourceNames(entityIri, schema);
	}

	private void updateResourceNames(IRI entityIri, Schema schema){
		// update used namespace prefixes
		schema.addUsedNamespace(entityIri.getNamespace());
		// update unique local name count
		Integer count = 0;
		Map<String, Integer> resourceNames = schema.getResourceNames();
		if(resourceNames.containsKey(entityIri.getShortForm())){
			count = resourceNames.get(entityIri.getShortForm());
		}
		resourceNames.put(entityIri.getShortForm(), ++count);
	}
	
	private String parseDataType(String dataType){
		String resultDataType = dataType;
		
		int lastIndex = dataType.lastIndexOf("#");
		if(lastIndex == -1){
			lastIndex = dataType.lastIndexOf("/");
		}
		if(lastIndex != -1 && lastIndex < dataType.length()){
			resultDataType = dataType.substring(lastIndex + 1);
		}
		
		if(!resultDataType.startsWith("xsd") && dataType.startsWith(XSD_NAMESPACE)) {
			resultDataType = "xsd_" + resultDataType;
		}
		
		return resultDataType;
	}

	private void processAnnotations(Stream<OWLAnnotation> annotations, AnnotationElement target){
		annotations.forEach(a -> {
			if (a != null && a.getProperty() != null && a.getValue() != null) {
				String key = a.getProperty().toStringID();
				String value = a.getValue().toString();
				if(a.getValue() != null && a.getValue().asLiteral().isPresent()){
					value = a.getValue().asLiteral().get().getLiteral();
				}
				target.addAnnotation(key, value);
			}
		});
	}

}

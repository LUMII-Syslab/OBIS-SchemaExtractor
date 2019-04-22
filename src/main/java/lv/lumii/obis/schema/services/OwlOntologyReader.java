package lv.lumii.obis.schema.services;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import lv.lumii.obis.schema.services.dto.SchemaCardinalityInfo;
import lv.lumii.obis.schema.services.dto.SchemaCardinalityInfo.CardinalityType;
import org.semanticweb.owlapi.search.EntitySearcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static lv.lumii.obis.schema.constants.SchemaConstants.*;

public class OwlOntologyReader {

	@Nullable
	public Schema readOwlOntology(@Nonnull InputStream inputStream) {
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

	@Nonnull
	private Schema processOwlOntology(@Nonnull OWLOntology ontology, @Nonnull OWLOntologyManager manager) {
		IRI ontologyIRI = ontology.getOntologyID().getOntologyIRI().orElse(null);
		
		Schema schema = new Schema();		
		schema.setName((ontologyIRI != null) ? ontologyIRI.getIRIString() : null);
		processPrefixes(ontology, schema);
		processAnnotations(ontology.annotations(), schema);

		Map<String, SchemaClass> classesMap = new HashMap<>();
		Map<String, List<SchemaCardinalityInfo>> cardinalityMap = new HashMap<>();
		
		processClasses(ontology, schema, classesMap, cardinalityMap, manager);
		processDataTypeProperties(ontology, schema, classesMap, cardinalityMap);
		processAnnotationProperties(ontology, schema, classesMap, cardinalityMap);
		processObjectTypeProperties(ontology, schema, classesMap, cardinalityMap);
		processInverseObjectTypeProperties(ontology, schema);

		processMultipleNamespaces(schema);
		
		return schema;
	}
	
	private void processPrefixes(@Nonnull OWLOntology ontology, @Nonnull Schema schema) {
		OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
		if (format != null && Boolean.TRUE.equals(format.isPrefixOWLDocumentFormat())) {
			schema.setNamespaceMap(format.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap());
		}
	}

	private void processMultipleNamespaces(@Nonnull Schema schema){
		if(schema.getUsedNamespacePrefixMap().size() <= 1){
			schema.setMultipleNamespaces(false);
		} else {
			schema.setMultipleNamespaces(true);
		}
	}
	
	private void processClasses(@Nonnull OWLOntology ontology, @Nonnull Schema schema, @Nonnull Map<String, SchemaClass> classesMap,
								@Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap, @Nonnull OWLOntologyManager manager) {
		
		List<OWLClass> owlClasses = ontology.classesInSignature().collect(Collectors.toList());
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
			List<IRI> superClasses = ontology.axioms(AxiomType.SUBCLASS_OF)
					.filter(axiom -> isValidSubClass(axiom, currentClassFullName))
					.map(axiom -> axiom.getSuperClass().asOWLClass().getIRI())
					.collect(Collectors.toList());
			for(IRI superClassIri: superClasses) {
				addSuperClass(superClassIri, currentClass, classesMap, schema);
			}
			
			getCardinalities(ontology, clazz, cardinalityMap);
			processAnnotations(EntitySearcher.getAnnotations(clazz, ontology), currentClass);
			processInstanceCount(currentClass);
			processOrderIndex(currentClass);
		}
		
		schema.setClasses(new ArrayList<>(classesMap.values()));
	}
	
	private boolean isValidSubClass(@Nonnull OWLSubClassOfAxiom axiom, @Nonnull String currentClassIri) {
		return (axiom.getSubClass() instanceof OWLClass)
				&& (axiom.getSuperClass() instanceof OWLClass)
				&& currentClassIri.equals(axiom.getSubClass().asOWLClass().getIRI().toString());
	}
	
	private void addSuperClass(@Nonnull IRI superClassIri, @Nonnull SchemaClass subClass, @Nonnull Map<String, SchemaClass> classesMap, @Nonnull Schema schema) {
		SchemaClass superClass = classesMap.get(superClassIri.toString());
		if(superClass == null) {
			superClass = new SchemaClass();
			setLocalNameAndNamespace(superClassIri, superClass, schema);
			classesMap.put(superClassIri.toString(), superClass);
		}
		superClass.getSubClasses().add(subClass.getFullName());
		subClass.getSuperClasses().add(superClassIri.toString());
	}
	
	private void getCardinalities(@Nonnull OWLOntology ontology, @Nonnull OWLClass clazz, @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
		for(OWLAxiom axiom : ontology.axioms(clazz).collect( Collectors.toSet())) {		
			
			axiom.accept(new OWLObjectVisitor() {
				
				public void visit(OWLSubClassOfAxiom subClassAxiom) {
					subClassAxiom.getSuperClass().accept( new OWLObjectVisitor() {

						public void visit(OWLObjectExactCardinality exactCardinalityAxiom) {
							fillCardinality(exactCardinalityAxiom, CardinalityType.EXACT_CARDINALITY, cardinalityMap);
						}
						public void visit(OWLDataExactCardinality exactCardinalityAxiom) {
							fillCardinality(exactCardinalityAxiom, CardinalityType.EXACT_CARDINALITY, cardinalityMap);
						}

						public void visit(OWLObjectMinCardinality minCardinalityAxiom) {
							fillCardinality(minCardinalityAxiom, CardinalityType.MIN_CARDINALITY, cardinalityMap);
						}
						public void visit(OWLDataMinCardinality minCardinalityAxiom) {
							fillCardinality(minCardinalityAxiom, CardinalityType.MIN_CARDINALITY, cardinalityMap);
						}

						public void visit(OWLObjectMaxCardinality maxCardinalityAxiom) {
							fillCardinality(maxCardinalityAxiom, CardinalityType.MAX_CARDINALITY, cardinalityMap);
						}
						public void visit(OWLDataMaxCardinality maxCardinalityAxiom) {
							fillCardinality(maxCardinalityAxiom, CardinalityType.MAX_CARDINALITY, cardinalityMap);
						}
					});
				}
			});

		}
	}
	
	private void fillCardinality(@Nonnull OWLCardinalityRestriction<?> restriction, @Nonnull CardinalityType cardinalityType,
								 @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
		if(!(restriction.getProperty() instanceof OWLProperty)) {
			return;
		}
		String property = ((OWLProperty)restriction.getProperty()).toStringID();
		int cardinality = restriction.getCardinality();
		
		List<SchemaCardinalityInfo> cardinalities = new ArrayList<>();
		if(cardinalityMap.containsKey(property)) {
			cardinalities = cardinalityMap.get(property);
		} else {
			cardinalityMap.put(property, cardinalities);
		}
		cardinalities.add(new SchemaCardinalityInfo(cardinalityType, cardinality));
	}
	
	private void processDataTypeProperties(@Nonnull OWLOntology ontology, @Nonnull Schema schema, @Nonnull Map<String, SchemaClass> classesMap,
										   @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
		
		List<OWLDataPropertyDomainAxiom> domains = ontology.axioms(AxiomType.DATA_PROPERTY_DOMAIN).collect(Collectors.toList());
		List<OWLDataPropertyRangeAxiom> ranges = ontology.axioms(AxiomType.DATA_PROPERTY_RANGE).collect(Collectors.toList());
		List<OWLDataProperty> dataProperties = ontology.dataPropertiesInSignature().collect(Collectors.toList());
		
		for(OWLDataProperty property: dataProperties) {
			String propertyName = property.getIRI().toString();
			SchemaAttribute dataProperty = new SchemaAttribute();
			
			setLocalNameAndNamespace(property.getIRI(), dataProperty, schema);
			setDomain(dataProperty, propertyName, domains, classesMap);
			setRangeType(dataProperty, propertyName, ranges);
			setCardinalities(dataProperty, propertyName, cardinalityMap);
			processAnnotations(EntitySearcher.getAnnotations(property, ontology), dataProperty);

			schema.getAttributes().add(dataProperty);
		}
	}

	private void processAnnotationProperties(@Nonnull OWLOntology ontology, @Nonnull Schema schema, @Nonnull Map<String, SchemaClass> classesMap,
											 @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {

		List<OWLAnnotationPropertyDomainAxiom> domains = ontology.axioms(AxiomType.ANNOTATION_PROPERTY_DOMAIN).collect(Collectors.toList());
		List<OWLAnnotationPropertyRangeAxiom> ranges = ontology.axioms(AxiomType.ANNOTATION_PROPERTY_RANGE).collect(Collectors.toList());
		List<OWLAnnotationProperty> annotationProperties = ontology.annotationPropertiesInSignature().collect(Collectors.toList());

		for(OWLAnnotationProperty property: annotationProperties) {
			String propertyName = property.getIRI().toString();
			SchemaAttribute annotationProperty = new SchemaAttribute();

			setLocalNameAndNamespace(property.getIRI(), annotationProperty, schema);
			setAnnotationDomain(annotationProperty, propertyName, domains, classesMap);
			setAnnotationRangeType(annotationProperty, propertyName, ranges);
			setCardinalities(annotationProperty, propertyName, cardinalityMap);
			processAnnotations(EntitySearcher.getAnnotations(property, ontology), annotationProperty);

			schema.getAttributes().add(annotationProperty);
		}
	}

	private void setAnnotationDomain(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName,
									 @Nonnull List<OWLAnnotationPropertyDomainAxiom> domains, @Nonnull Map<String, SchemaClass> classesMap) {
		OWLAnnotationPropertyDomainAxiom domainAxiom = domains.stream()
				.filter(d -> propertyName.equals(d.getProperty().asOWLAnnotationProperty().getIRI().toString()))
				.findFirst().orElse(null);
		if(domainAxiom != null && domainAxiom.getDomain() != null) {
			String domainUri = domainAxiom.getDomain().getIRIString();
			if(classesMap.containsKey(domainUri)) {
				dataProperty.getSourceClasses().add(domainUri);
			}
		}
	}

	private void setAnnotationRangeType(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName, @Nonnull List<OWLAnnotationPropertyRangeAxiom> ranges) {
		OWLAnnotationPropertyRangeAxiom rangeAxiom = ranges.stream()
				.filter(d -> propertyName.equals(d.getProperty().asOWLDataProperty().getIRI().toString()))
				.findFirst().orElse(null);
		if(rangeAxiom != null && rangeAxiom.getRange() != null) {
			String parsedDataType = SchemaUtil.parseDataType(rangeAxiom.getRange().getIRIString());
			dataProperty.setType(parsedDataType);
		}
		if(dataProperty.getType() == null) {
			dataProperty.setType(DATA_TYPE_XSD_DEFAULT);
		}
	}
	
	private void setDomain(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName, @Nonnull List<OWLDataPropertyDomainAxiom> domains,
						   @Nonnull Map<String, SchemaClass> classesMap) {
		OWLDataPropertyDomainAxiom domainAxiom = domains.stream()
				.filter(d -> propertyName.equals(d.getProperty().asOWLDataProperty().getIRI().toString()))
				.findFirst().orElse(null);
		if(isValidDomainClass(domainAxiom)) {
			String domainUri = domainAxiom.getDomain().asOWLClass().getIRI().toString();
			if(classesMap.containsKey(domainUri)) {
				dataProperty.getSourceClasses().add(domainUri);
			}
		}
	}
	
	private void setRangeType(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName, @Nonnull List<OWLDataPropertyRangeAxiom> ranges) {
		OWLDataPropertyRangeAxiom rangeAxiom = ranges.stream()
				.filter(d -> propertyName.equals(d.getProperty().asOWLDataProperty().getIRI().toString()))
				.findFirst().orElse(null);
		if(isValidSimpleRangeType(rangeAxiom)) {
			String parsedDataType = SchemaUtil.parseDataType(rangeAxiom.getRange().asOWLDatatype().getIRI().toString());
			dataProperty.setType(parsedDataType);
		} else if(isValidLookupRangeType(rangeAxiom)){
			List<String> lookups = ((OWLDataOneOf)rangeAxiom.getRange()).values().map(OWLLiteral::getLiteral).collect(Collectors.toList());
			if(lookups != null && !lookups.isEmpty()){
				String lookupString = String.join(";", lookups);
				dataProperty.setRangeLookupValues(lookupString);
			}
		}
	}
	
	private void setCardinalities(@Nonnull SchemaProperty property, @Nonnull String propertyName, @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
		if(!cardinalityMap.containsKey(propertyName)){
			return;
		}

		Integer exactCardinality = cardinalityMap.get(propertyName).stream()
				.filter(c -> CardinalityType.EXACT_CARDINALITY.equals(c.getCardinalityType()))
				.findFirst().orElse(new SchemaCardinalityInfo(CardinalityType.EXACT_CARDINALITY, null))
				.getCardinality();
		if(exactCardinality != null && exactCardinality > 0) {
			property.setMinCardinality(exactCardinality);
			property.setMaxCardinality(exactCardinality);
			return;
		}

		Integer minCardinality = cardinalityMap.get(propertyName).stream()
				.filter(c -> CardinalityType.MIN_CARDINALITY.equals(c.getCardinalityType()))
				.findFirst().orElse(new SchemaCardinalityInfo(CardinalityType.MIN_CARDINALITY, null))
				.getCardinality();
		if(minCardinality != null) {
			property.setMinCardinality(minCardinality);
		}		

		Integer maxCardinality = cardinalityMap.get(propertyName).stream()
				.filter(c -> CardinalityType.MAX_CARDINALITY.equals(c.getCardinalityType()))
				.findFirst().orElse(new SchemaCardinalityInfo(CardinalityType.MAX_CARDINALITY, null))
				.getCardinality();
		if(maxCardinality != null) {
			property.setMaxCardinality(maxCardinality);
		}

	}

	private void processObjectTypeProperties(@Nonnull OWLOntology ontology, @Nonnull Schema schema, @Nonnull Map<String, SchemaClass> classesMap,
											 @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
		
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
			setCardinalities(objectProperty, propertyName, cardinalityMap);
			processAnnotations(EntitySearcher.getAnnotations(property, ontology), objectProperty);
			
			objectProperty.getClassPairs().add(classPair);
			schema.getAssociations().add(objectProperty);
		}
	}

	private void processInverseObjectTypeProperties(@Nonnull OWLOntology ontology, @Nonnull Schema schema) {
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

	private void setDomain(@Nonnull String propertyName, @Nonnull List<OWLObjectPropertyDomainAxiom> domains,
						   @Nonnull Map<String, SchemaClass> classesMap, ClassPair classPair) {
		OWLObjectPropertyDomainAxiom domainAxiom = domains.stream().filter(d -> propertyName.equals(
				d.getProperty().asOWLObjectProperty().getIRI().toString())).findFirst().orElse(null);
		if(isValidDomainClass(domainAxiom)) {
			String domainUri = domainAxiom.getDomain().asOWLClass().getIRI().toString();
			if(classesMap.containsKey(domainUri)) {
				classPair.setSourceClass(domainUri);
			}
		}
	}
	
	private void setRange(@Nonnull String propertyName, @Nonnull List<OWLObjectPropertyRangeAxiom> ranges,
						  @Nonnull Map<String, SchemaClass> classesMap, ClassPair classPair) {
		OWLObjectPropertyRangeAxiom rangeAxiom = ranges.stream().filter(d -> propertyName.equals(
				d.getProperty().asOWLObjectProperty().getIRI().toString())).findFirst().orElse(null);
		if(isValidRangeClass(rangeAxiom)) {
			String rangeUri = rangeAxiom.getRange().asOWLClass().getIRI().toString();
			if(classesMap.containsKey(rangeUri)) {
				classPair.setTargetClass(rangeUri);
			}
		}
	}
	
	private boolean isValidDomainClass(@Nullable OWLPropertyDomainAxiom<?> axiom) {
		return axiom != null && axiom.getDomain() != null && axiom.getDomain().isOWLClass();
	}
	private boolean isValidSimpleRangeType(@Nullable OWLDataPropertyRangeAxiom axiom) {
		return axiom != null && axiom.getRange() != null && axiom.getRange().isOWLDatatype();
	}
	private boolean isValidLookupRangeType(@Nullable OWLDataPropertyRangeAxiom axiom) {
		return axiom != null && axiom.getRange() != null && (axiom.getRange() instanceof OWLDataOneOf);
	}
	private boolean isValidRangeClass(@Nullable OWLObjectPropertyRangeAxiom axiom) {
		return axiom != null && axiom.getRange() != null && axiom.getRange().isOWLClass();
	}
	
	private void setLocalNameAndNamespace(@Nonnull IRI entityIri, @Nonnull SchemaEntity entity, @Nonnull Schema schema){
		entity.setLocalName(entityIri.getShortForm());
		entity.setFullName(entityIri.toString());
		entity.setNamespace(entityIri.getNamespace());

		updateResourceNames(entityIri, schema);
	}

	private void updateResourceNames(@Nonnull IRI entityIri, @Nonnull Schema schema){
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
	
	private void processAnnotations(@Nonnull Stream<OWLAnnotation> annotations, @Nonnull AnnotationElement target){
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

	private void processInstanceCount(@Nonnull SchemaClass schemaClass) {
		Map.Entry<String, String> instanceCountEntry = schemaClass.getAnnotations().entrySet().stream()
				.filter(entry -> SchemaConstants.ANNOTATION_INSTANCE_COUNT.equalsIgnoreCase(entry.getKey()))
				.findFirst().orElse(null);
		if (instanceCountEntry != null && instanceCountEntry.getValue() != null) {
			Long instanceCount = null;
			try {
				instanceCount = Long.valueOf(instanceCountEntry.getValue());
			} catch (NumberFormatException e) {
				// do nothing
			}
			if (instanceCount != null) {
				schemaClass.setInstanceCount(instanceCount);
			}
		}
	}

	private void processOrderIndex(@Nonnull SchemaClass schemaClass) {
		Map.Entry<String, String> orderIndexEntry = schemaClass.getAnnotations().entrySet().stream()
				.filter(entry -> SchemaConstants.ANNOTATION_ORDER_INDEX.equalsIgnoreCase(entry.getKey()))
				.findFirst().orElse(null);
		if (orderIndexEntry != null && orderIndexEntry.getValue() != null) {
			Long orderIndex = null;
			try {
				orderIndex = Long.valueOf(orderIndexEntry.getValue());
			} catch (NumberFormatException e) {
				// do nothing
			}
			if (orderIndex != null) {
				schemaClass.setOrderIndex(orderIndex);
			}
		}
	}

}

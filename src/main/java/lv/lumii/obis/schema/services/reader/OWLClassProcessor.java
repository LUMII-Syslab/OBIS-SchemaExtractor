package lv.lumii.obis.schema.services.reader;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.AnnotationEntry;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaClass;
import lv.lumii.obis.schema.services.reader.dto.SchemaCardinalityInfo;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OWLClassProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData) {
        List<OWLClass> owlClasses = inputOntology.classesInSignature().collect(Collectors.toList());
        if (owlClasses.isEmpty()) {
            log.info("No OWLCLasses defined in the ontology");
            return;
        }

        Map<String, SchemaClass> classesMap = processingData.getClassesMap();
        Map<String, List<SchemaCardinalityInfo>> cardinalityMap = processingData.getCardinalityMap();

        owlClasses.stream().filter(Objects::nonNull).forEach(clazz -> {

            // initialize current class instance
            String currentClassFullName = clazz.getIRI().toString();
            final SchemaClass currentClass;
            if (classesMap.containsKey(currentClassFullName)) {
                currentClass = classesMap.get(currentClassFullName);
            } else {
                currentClass = new SchemaClass();
                setSchemaEntityNameAndNamespace(clazz.getIRI(), currentClass, resultSchema);
                classesMap.put(currentClassFullName, currentClass);
            }

            // process superclasses
            EntitySearcher.getSuperClasses(clazz, inputOntology)
                    .map(this::extractSuperClasses)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList())
                    .forEach(superClassIri -> addSuperClass(superClassIri, currentClass, classesMap, resultSchema));

            // update cardinality restriction map
            extractCardinalityAxioms(inputOntology, clazz, cardinalityMap);

            // process annotations
            setAnnotations(EntitySearcher.getAnnotations(clazz, inputOntology), currentClass);
            extractInstanceCount(currentClass);
            extractOrderIndex(currentClass);
        });

        resultSchema.setClasses(new ArrayList<>(classesMap.values()));
    }

    @Nonnull
    private List<IRI> extractSuperClasses(@Nonnull OWLClassExpression classExpression) {
        List<IRI> superClasses = new ArrayList<>();
        extractOWLClassFromExpression(classExpression, superClasses);
        return superClasses;
    }

    private void addSuperClass(@Nonnull IRI superClassIri, @Nonnull SchemaClass subClass, @Nonnull Map<String, SchemaClass> classesMap, @Nonnull Schema schema) {
        SchemaClass superClass = classesMap.get(superClassIri.toString());
        if (superClass == null) {
            superClass = new SchemaClass();
            setSchemaEntityNameAndNamespace(superClassIri, superClass, schema);
            classesMap.put(superClassIri.toString(), superClass);
        }
        superClass.getSubClasses().add(subClass.getFullName());
        subClass.getSuperClasses().add(superClassIri.toString());
    }

    private void extractInstanceCount(@Nonnull SchemaClass schemaClass) {
        AnnotationEntry instanceCountEntry = schemaClass.getAnnotations().stream()
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

    private void extractOrderIndex(@Nonnull SchemaClass schemaClass) {
        AnnotationEntry orderIndexEntry = schemaClass.getAnnotations().stream()
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

    private void extractCardinalityAxioms(@Nonnull OWLOntology ontology, @Nonnull OWLClass clazz, @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
        for (OWLAxiom axiom : ontology.axioms(clazz).collect(Collectors.toSet())) {

            axiom.accept(new OWLObjectVisitor() {

                public void visit(OWLSubClassOfAxiom subClassAxiom) {
                    subClassAxiom.getSuperClass().accept(new OWLObjectVisitor() {

                        public void visit(OWLObjectExactCardinality exactCardinalityAxiom) {
                            fillCardinality(exactCardinalityAxiom, SchemaCardinalityInfo.CardinalityType.EXACT_CARDINALITY, cardinalityMap);
                        }

                        public void visit(OWLDataExactCardinality exactCardinalityAxiom) {
                            fillCardinality(exactCardinalityAxiom, SchemaCardinalityInfo.CardinalityType.EXACT_CARDINALITY, cardinalityMap);
                        }

                        public void visit(OWLObjectMinCardinality minCardinalityAxiom) {
                            fillCardinality(minCardinalityAxiom, SchemaCardinalityInfo.CardinalityType.MIN_CARDINALITY, cardinalityMap);
                        }

                        public void visit(OWLDataMinCardinality minCardinalityAxiom) {
                            fillCardinality(minCardinalityAxiom, SchemaCardinalityInfo.CardinalityType.MIN_CARDINALITY, cardinalityMap);
                        }

                        public void visit(OWLObjectMaxCardinality maxCardinalityAxiom) {
                            fillCardinality(maxCardinalityAxiom, SchemaCardinalityInfo.CardinalityType.MAX_CARDINALITY, cardinalityMap);
                        }

                        public void visit(OWLDataMaxCardinality maxCardinalityAxiom) {
                            fillCardinality(maxCardinalityAxiom, SchemaCardinalityInfo.CardinalityType.MAX_CARDINALITY, cardinalityMap);
                        }
                    });
                }
            });

        }
    }

    private void fillCardinality(@Nonnull OWLCardinalityRestriction<?> restriction, @Nonnull SchemaCardinalityInfo.CardinalityType cardinalityType,
                                 @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
        if (!(restriction.getProperty() instanceof OWLProperty)) {
            return;
        }
        String property = ((OWLProperty) restriction.getProperty()).toStringID();
        int cardinality = restriction.getCardinality();

        List<SchemaCardinalityInfo> cardinalities = new ArrayList<>();
        if (cardinalityMap.containsKey(property)) {
            cardinalities = cardinalityMap.get(property);
        } else {
            cardinalityMap.put(property, cardinalities);
        }
        cardinalities.add(new SchemaCardinalityInfo(cardinalityType, cardinality));
    }

}

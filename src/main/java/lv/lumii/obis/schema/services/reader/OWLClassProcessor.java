package lv.lumii.obis.schema.services.reader;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.model.v1.Schema;
import lv.lumii.obis.schema.model.v1.SchemaClass;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.common.dto.SchemaCardinalityInfo;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderProcessingData;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OWLClassProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull OWLOntologyReaderProcessingData processingData,
                        @Nonnull OWLOntologyReaderRequest readerRequest) {
        List<OWLClass> owlClasses = inputOntology.classesInSignature().collect(Collectors.toList());
        if (owlClasses.isEmpty()) {
            log.info("No OWLCLasses defined in the ontology");
            return;
        }

        Map<String, SchemaClass> classesMap = processingData.getClassesMap();
        Map<String, List<SchemaCardinalityInfo>> cardinalityMap = processingData.getCardinalityMap();

        owlClasses.stream().filter(c -> c != null && !isExcludedResource(c.getIRI(), readerRequest.getExcludedNamespaces())).forEach(clazz -> {

            // initialize current class instance
            final SchemaClass currentClass = getOrCreateSchemaClass(clazz.getIRI(), classesMap);

            // process superclasses
            EntitySearcher.getSuperClasses(clazz, inputOntology)
                    .map(this::extractSuperClasses)
                    .flatMap(Collection::stream)
                    .filter(iri -> iri != null && !isExcludedResource(iri, readerRequest.getExcludedNamespaces()))
                    .collect(Collectors.toList())
                    .forEach(superClassIRI -> addSuperClass(superClassIRI, currentClass, classesMap));

            // process equivalent classes
            EntitySearcher.getEquivalentClasses(clazz, inputOntology)
                    .map(this::extractEquivalentClass)
                    .filter(iri -> iri != null && !isExcludedResource(iri, readerRequest.getExcludedNamespaces()))
                    .collect(Collectors.toList())
                    .forEach(equivalentClassIRI -> addEquivalentClass(equivalentClassIRI, currentClass, classesMap));

            // update cardinality restriction map
            extractCardinalityAxioms(inputOntology, clazz, cardinalityMap);

            // process annotations
            setAnnotations(EntitySearcher.getAnnotations(clazz, inputOntology), currentClass);
        });

        log.info("Processed {} OWL classes", classesMap.size());
        resultSchema.setClasses(new ArrayList<>(classesMap.values()));
    }

    @Nonnull
    private SchemaClass getOrCreateSchemaClass(@Nonnull IRI classIRI, @Nonnull Map<String, SchemaClass> classesMap) {
        SchemaClass clazz = classesMap.get(classIRI.toString());
        if (clazz == null) {
            clazz = new SchemaClass();
            setSchemaElementNameAndNamespace(classIRI, clazz);
            classesMap.put(classIRI.toString(), clazz);
        }
        return clazz;
    }

    @Nonnull
    private List<IRI> extractSuperClasses(@Nonnull OWLClassExpression classExpression) {
        List<IRI> superClasses = new ArrayList<>();
        extractOWLClassFromExpression(classExpression, superClasses);
        return superClasses;
    }

    @Nullable
    private IRI extractEquivalentClass(@Nonnull OWLClassExpression classExpression) {
        return classExpression.isOWLClass() ? classExpression.asOWLClass().getIRI() : null;
    }

    private void addSuperClass(@Nonnull IRI superClassIRI, @Nonnull SchemaClass subClass, @Nonnull Map<String, SchemaClass> classesMap) {
        SchemaClass superClass = getOrCreateSchemaClass(superClassIRI, classesMap);
        superClass.getSubClasses().add(subClass.getFullName());
        subClass.getSuperClasses().add(superClass.getFullName());
    }

    private void addEquivalentClass(@Nonnull IRI equivalentClassIRI, @Nonnull SchemaClass currentClass, @Nonnull Map<String, SchemaClass> classesMap) {
        SchemaClass equivalentClass = getOrCreateSchemaClass(equivalentClassIRI, classesMap);
        equivalentClass.getEquivalentClasses().add(currentClass.getFullName());
        currentClass.getEquivalentClasses().add(equivalentClass.getFullName());
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

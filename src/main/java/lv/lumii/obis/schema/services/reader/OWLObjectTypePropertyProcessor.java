package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.v1.ClassPair;
import lv.lumii.obis.schema.model.v1.Schema;
import lv.lumii.obis.schema.model.v1.SchemaClass;
import lv.lumii.obis.schema.model.v1.SchemaRole;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderProcessingData;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OWLObjectTypePropertyProcessor extends OWLPropertyProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull OWLOntologyReaderProcessingData processingData,
                        @Nonnull OWLOntologyReaderRequest readerRequest) {
        List<OWLObjectPropertyDomainAxiom> domains = inputOntology.axioms(AxiomType.OBJECT_PROPERTY_DOMAIN).collect(Collectors.toList());
        List<OWLObjectPropertyRangeAxiom> ranges = inputOntology.axioms(AxiomType.OBJECT_PROPERTY_RANGE).collect(Collectors.toList());
        List<OWLObjectProperty> objectProperties = inputOntology.objectPropertiesInSignature()
                .filter(p -> p != null && !isExcludedResource(p.getIRI(), readerRequest.getExcludedNamespaces()))
                .collect(Collectors.toList());

        for (OWLObjectProperty property : objectProperties) {
            String propertyName = property.getIRI().toString();

            SchemaRole objectProperty = new SchemaRole();
            setSchemaElementNameAndNamespace(property.getIRI(), objectProperty);

            ClassPair classPair = new ClassPair();

            setDomain(propertyName, domains, processingData.getClassesMap(), classPair);
            setRange(propertyName, ranges, processingData.getClassesMap(), classPair);
            setCardinalities(objectProperty, propertyName, processingData.getCardinalityMap());
            setAnnotations(EntitySearcher.getAnnotations(property, inputOntology), objectProperty);

            objectProperty.getClassPairs().add(classPair);
            resultSchema.getAssociations().add(objectProperty);
        }
    }

    private void setDomain(@Nonnull String propertyName, @Nonnull List<OWLObjectPropertyDomainAxiom> domains,
                           @Nonnull Map<String, SchemaClass> classesMap, ClassPair classPair) {
        OWLObjectPropertyDomainAxiom domainAxiom = domains.stream().filter(d -> propertyName.equals(
                d.getProperty().asOWLObjectProperty().getIRI().toString())).findFirst().orElse(null);
        if (isValidDomainClass(domainAxiom)) {
            String domainUri = domainAxiom.getDomain().asOWLClass().getIRI().toString();
            if (classesMap.containsKey(domainUri)) {
                classPair.setSourceClass(domainUri);
            }
        }
    }

    private void setRange(@Nonnull String propertyName, @Nonnull List<OWLObjectPropertyRangeAxiom> ranges,
                          @Nonnull Map<String, SchemaClass> classesMap, ClassPair classPair) {
        OWLObjectPropertyRangeAxiom rangeAxiom = ranges.stream().filter(d -> propertyName.equals(
                d.getProperty().asOWLObjectProperty().getIRI().toString())).findFirst().orElse(null);
        if (isValidRangeClass(rangeAxiom)) {
            String rangeUri = rangeAxiom.getRange().asOWLClass().getIRI().toString();
            if (classesMap.containsKey(rangeUri)) {
                classPair.setTargetClass(rangeUri);
            }
        }
    }

    private boolean isValidDomainClass(@Nullable OWLPropertyDomainAxiom<?> axiom) {
        return axiom != null && axiom.getDomain() != null && axiom.getDomain().isOWLClass();
    }

    private boolean isValidRangeClass(@Nullable OWLObjectPropertyRangeAxiom axiom) {
        return axiom != null && axiom.getRange() != null && axiom.getRange().isOWLClass();
    }

}

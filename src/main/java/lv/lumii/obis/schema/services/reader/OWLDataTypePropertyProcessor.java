package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaAttribute;
import lv.lumii.obis.schema.model.SchemaClass;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OWLDataTypePropertyProcessor extends OWLPropertyProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData) {
        List<OWLDataPropertyDomainAxiom> domains = inputOntology.axioms(AxiomType.DATA_PROPERTY_DOMAIN).collect(Collectors.toList());
        List<OWLDataPropertyRangeAxiom> ranges = inputOntology.axioms(AxiomType.DATA_PROPERTY_RANGE).collect(Collectors.toList());
        List<OWLDataProperty> dataProperties = inputOntology.dataPropertiesInSignature().collect(Collectors.toList());

        for (OWLDataProperty property : dataProperties) {
            String propertyName = property.getIRI().toString();
            SchemaAttribute dataProperty = new SchemaAttribute();

            setSchemaElementNameAndNamespace(property.getIRI(), dataProperty);
            setDomain(dataProperty, propertyName, domains, processingData.getClassesMap());
            setRangeType(dataProperty, propertyName, ranges);
            setCardinalities(dataProperty, propertyName, processingData.getCardinalityMap());
            setAnnotations(EntitySearcher.getAnnotations(property, inputOntology), dataProperty);

            resultSchema.getAttributes().add(dataProperty);
        }
    }

    private void setDomain(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName, @Nonnull List<OWLDataPropertyDomainAxiom> domains,
                           @Nonnull Map<String, SchemaClass> classesMap) {
        domains.stream()
                .filter(d -> propertyName.equals(d.getProperty().asOWLDataProperty().getIRI().toString()))
                .forEach(domainAxiom -> {
                    if (isValidDomainClass(domainAxiom)) {
                        String domainUri = domainAxiom.getDomain().asOWLClass().getIRI().toString();
                        if (classesMap.containsKey(domainUri)) {
                            dataProperty.getSourceClasses().add(domainUri);
                        }
                    }
                });

    }

    private void setRangeType(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName, @Nonnull List<OWLDataPropertyRangeAxiom> ranges) {
        OWLDataPropertyRangeAxiom rangeAxiom = ranges.stream()
                .filter(d -> propertyName.equals(d.getProperty().asOWLDataProperty().getIRI().toString()))
                .findFirst().orElse(null);
        if (isValidSimpleRangeType(rangeAxiom)) {
            String parsedDataType = SchemaUtil.parseDataType(rangeAxiom.getRange().asOWLDatatype().getIRI().toString());
            dataProperty.setType(parsedDataType);
        } else if (isValidLookupRangeType(rangeAxiom)) {
            List<String> lookups = ((OWLDataOneOf) rangeAxiom.getRange()).values().map(OWLLiteral::getLiteral).collect(Collectors.toList());
            if (lookups != null && !lookups.isEmpty()) {
                String lookupString = String.join(";", lookups);
                dataProperty.setRangeLookupValues(lookupString);
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

}

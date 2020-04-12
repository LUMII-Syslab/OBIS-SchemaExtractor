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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.DATA_TYPE_XSD_DEFAULT;

@Service
public class OWLAnnotationPropertyProcessor extends OWLPropertyProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData) {
        List<OWLAnnotationPropertyDomainAxiom> domains = inputOntology.axioms(AxiomType.ANNOTATION_PROPERTY_DOMAIN).collect(Collectors.toList());
        List<OWLAnnotationPropertyRangeAxiom> ranges = inputOntology.axioms(AxiomType.ANNOTATION_PROPERTY_RANGE).collect(Collectors.toList());
        List<OWLAnnotationProperty> annotationProperties = inputOntology.annotationPropertiesInSignature().collect(Collectors.toList());

        for (OWLAnnotationProperty property : annotationProperties) {
            String propertyName = property.getIRI().toString();
            SchemaAttribute annotationProperty = new SchemaAttribute();

            setSchemaEntityNameAndNamespace(property.getIRI(), annotationProperty, resultSchema);
            setAnnotationDomain(annotationProperty, propertyName, domains, processingData.getClassesMap());
            setAnnotationRangeType(annotationProperty, propertyName, ranges);
            setCardinalities(annotationProperty, propertyName, processingData.getCardinalityMap());
            setAnnotations(EntitySearcher.getAnnotations(property, inputOntology), annotationProperty);

            resultSchema.getAttributes().add(annotationProperty);
        }
    }

    private void setAnnotationDomain(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName,
                                     @Nonnull List<OWLAnnotationPropertyDomainAxiom> domains, @Nonnull Map<String, SchemaClass> classesMap) {

        domains.stream()
                .filter(d -> d != null && propertyName.equals(d.getProperty().asOWLAnnotationProperty().getIRI().toString()))
                .forEach(domainAxiom -> {
                    if (domainAxiom.getDomain() != null) {
                        String domainUri = domainAxiom.getDomain().getIRIString();
                        if (classesMap.containsKey(domainUri)) {
                            dataProperty.getSourceClasses().add(domainUri);
                        }
                    }
                });
    }

    private void setAnnotationRangeType(@Nonnull SchemaAttribute dataProperty, @Nonnull String propertyName, @Nonnull List<OWLAnnotationPropertyRangeAxiom> ranges) {
        OWLAnnotationPropertyRangeAxiom rangeAxiom = ranges.stream()
                .filter(d -> propertyName.equals(d.getProperty().asOWLDataProperty().getIRI().toString()))
                .findFirst().orElse(null);
        if (rangeAxiom != null && rangeAxiom.getRange() != null) {
            String parsedDataType = SchemaUtil.parseDataType(rangeAxiom.getRange().getIRIString());
            dataProperty.setType(parsedDataType);
        }
        if (dataProperty.getType() == null) {
            dataProperty.setType(DATA_TYPE_XSD_DEFAULT);
        }
    }

}

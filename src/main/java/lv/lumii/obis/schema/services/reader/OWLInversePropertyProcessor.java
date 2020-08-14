package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaRole;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderProcessingData;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OWLInversePropertyProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull OWLOntologyReaderProcessingData processingData,
                        @Nonnull OWLOntologyReaderRequest readerRequest) {
        List<OWLInverseObjectPropertiesAxiom> inverseProperties = inputOntology.axioms(AxiomType.INVERSE_OBJECT_PROPERTIES).collect(Collectors.toList());
        for (OWLInverseObjectPropertiesAxiom inverse : inverseProperties) {
            OWLObjectPropertyExpression first = inverse.getFirstProperty();
            OWLObjectPropertyExpression second = inverse.getSecondProperty();
            if (first == null || first.getNamedProperty() == null || isExcludedResource(first.getNamedProperty().getIRI(), readerRequest.getExcludedNamespaces())
                    || second == null || second.getNamedProperty() == null || isExcludedResource(second.getNamedProperty().getIRI(), readerRequest.getExcludedNamespaces())) {
                continue;
            }
            SchemaRole firstRole = resultSchema.getAssociations().stream()
                    .filter(r -> r.getFullName().equalsIgnoreCase(first.getNamedProperty().getIRI().toString()))
                    .findFirst().orElse(null);
            SchemaRole secondRole = resultSchema.getAssociations().stream()
                    .filter(r -> r.getFullName().equalsIgnoreCase(second.getNamedProperty().getIRI().toString()))
                    .findFirst().orElse(null);
            if (firstRole == null || secondRole == null) {
                continue;
            }
            firstRole.setInverseProperty(secondRole.getFullName());
            secondRole.setInverseProperty(firstRole.getFullName());
        }
    }

}

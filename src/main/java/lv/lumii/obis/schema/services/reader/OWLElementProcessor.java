package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.AnnotationElement;
import lv.lumii.obis.schema.model.AnnotationEntry;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaEntity;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Stream;

public interface OWLElementProcessor {

    void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData);


    default void extractOWLClassFromExpression(@Nonnull OWLClassExpression classExpression, @Nonnull List<IRI> listOfClassIRIs) {
        switch (classExpression.getClassExpressionType()) {
            case OWL_CLASS:
                listOfClassIRIs.add(classExpression.asOWLClass().getIRI());
                break;
            case OBJECT_INTERSECTION_OF:
                classExpression.asConjunctSet().forEach(intersectionExpression -> extractOWLClassFromExpression(intersectionExpression, listOfClassIRIs));
                break;
            case OBJECT_UNION_OF:
                classExpression.asDisjunctSet().forEach(unionExpression -> extractOWLClassFromExpression(unionExpression, listOfClassIRIs));
                break;
            default:
                // do nothing if the expression type is unknown or not supported
                break;
        }
    }

    default void setSchemaEntityNameAndNamespace(@Nonnull IRI entityIri, @Nonnull SchemaEntity entity, @Nonnull Schema schema) {
        entity.setLocalName(entityIri.getShortForm());
        entity.setFullName(entityIri.toString());
        entity.setNamespace(entityIri.getNamespace());
    }

    default void setAnnotations(@Nonnull Stream<OWLAnnotation> annotations, @Nonnull AnnotationElement target) {
        annotations.forEach(a -> {
            if (a != null && a.getProperty() != null && a.getValue() != null) {
                String key = a.getProperty().toStringID();
                String value = a.getValue().toString();
                if (a.getValue() != null && a.getValue().asLiteral().isPresent()) {
                    value = a.getValue().asLiteral().get().getLiteral();
                }
                target.getAnnotations().add(new AnnotationEntry(key, value));
            }
        });
    }
}

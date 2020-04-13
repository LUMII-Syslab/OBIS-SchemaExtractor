package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.AnnotationElement;
import lv.lumii.obis.schema.model.AnnotationEntry;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaElement;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
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

    default void setSchemaElementNameAndNamespace(@Nonnull IRI owlElementIRI, @Nonnull SchemaElement schemaElement) {
        schemaElement.setLocalName(owlElementIRI.getShortForm());
        schemaElement.setFullName(owlElementIRI.toString());
        schemaElement.setNamespace(owlElementIRI.getNamespace());
    }

    default void setAnnotations(@Nonnull Stream<OWLAnnotation> annotations, @Nonnull AnnotationElement target) {
        annotations
                .filter(Objects::nonNull)
                .filter(annotation -> annotation.getProperty() != null && annotation.getValue() != null)
                .forEach(annotation -> {
                    String key = annotation.getProperty().toStringID();
                    String value = annotation.getValue().toString();
                    if (annotation.getValue().asLiteral().isPresent()) {
                        value = annotation.getValue().asLiteral().get().getLiteral();
                    }
                    target.getAnnotations().add(new AnnotationEntry(key, value));
                });
    }

    @Nullable
    default AnnotationEntry findAnnotation(@Nonnull List<AnnotationEntry> annotations, @Nonnull String annotationKey) {
        return annotations.stream()
                .filter(entry -> annotationKey.equalsIgnoreCase(entry.getKey()))
                .findFirst().orElse(null);
    }

    @Nullable
    default Long extractAnnotationLongValue(@Nullable AnnotationEntry annotationEntry) {
        if (annotationEntry == null || annotationEntry.getValue() == null) {
            return null;
        }
        Long longValue = null;
        try {
            longValue = Long.valueOf(annotationEntry.getValue());
        } catch (NumberFormatException e) {
           // do nothing
        }
        return longValue;
    }
}

package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaClass;
import lv.lumii.obis.schema.model.SchemaElement;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.reader.dto.AnnotationInfo;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.apache.commons.lang3.StringUtils;
import org.semanticweb.owlapi.model.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public interface OWLElementProcessor {

    void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData,
                 @Nonnull OWLOntologyReaderRequest readerRequest);


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
                // do nothing if the OWL class expression type is unknown or not supported
                break;
        }
    }

    default void setSchemaElementNameAndNamespace(@Nonnull IRI owlElementIRI, @Nonnull SchemaElement schemaElement) {
        schemaElement.setLocalName(owlElementIRI.getShortForm());
        schemaElement.setFullName(owlElementIRI.toString());
        schemaElement.setNamespace(owlElementIRI.getNamespace());
    }

    default void setAnnotations(@Nonnull Stream<OWLAnnotation> annotations, @Nonnull SchemaElement target) {
        annotations
                .filter(Objects::nonNull)
                .filter(annotation -> annotation.getProperty() != null && annotation.getValue() != null)
                .forEach(annotation -> {
                    // process rdfs:label
                    if (annotation.getProperty().isLabel()) {
                        AnnotationInfo annotationInfo = createAnnotationLiteralDTO(annotation);
                        if (annotationInfo != null) {
                            target.addLabel(annotationInfo.getKey(), annotationInfo.getValue());
                        }
                    }
                    // process rdfs:comment
                    if (annotation.getProperty().isComment()) {
                        AnnotationInfo annotationInfo = createAnnotationLiteralDTO(annotation);
                        if (annotationInfo != null) {
                            target.addComment(annotationInfo.getKey(), annotationInfo.getValue());
                        }
                    }
                    // process orderIndex and instanceCount for SchemaClass
                    if (target instanceof SchemaClass) {
                        if (SchemaConstants.ANNOTATION_ORDER_INDEX.equalsIgnoreCase(annotation.getProperty().getIRI().getIRIString())) {
                            ((SchemaClass) target).setOrderIndex(extractAnnotationLongValue(annotation));
                        } else if (SchemaConstants.ANNOTATION_INSTANCE_COUNT.equalsIgnoreCase(annotation.getProperty().getIRI().getIRIString())) {
                            ((SchemaClass) target).setInstanceCount(extractAnnotationLongValue(annotation));
                        }
                    }
                });
    }

    @Nullable
    default AnnotationInfo createAnnotationLiteralDTO(@Nonnull OWLAnnotation annotation) {
        if (annotation.getValue() instanceof OWLLiteral) {
            OWLLiteral literal = (OWLLiteral) annotation.getValue();
            String lang = StringUtils.isNotEmpty(literal.getLang()) ? literal.getLang() : "";
            if (StringUtils.isNotEmpty(literal.getLiteral())) {
                return new AnnotationInfo(lang, literal.getLiteral());
            }
        }
        return null;
    }

    @Nullable
    default Long extractAnnotationLongValue(@Nonnull OWLAnnotation annotation) {
        if (annotation.getValue() == null || !(annotation.getValue() instanceof OWLLiteral)) {
            return null;
        }
        Long longValue = null;
        try {
            longValue = Long.valueOf(((OWLLiteral) annotation.getValue()).getLiteral());
        } catch (NumberFormatException e) {
            // do nothing
        }
        return longValue;
    }

    default boolean isExcludedResource(@Nullable IRI iri, @Nullable List<String> excludedResources) {
        return excludedResources != null && iri != null && excludedResources.contains(iri.getNamespace());
    }
}

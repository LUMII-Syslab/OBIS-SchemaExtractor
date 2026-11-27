package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorMessage {

    public enum MessageLevel {ERROR, WARNING, WARNING_HIGH, WARNING_LOW, MESSAGE, NOTE, INFO, OK}

    public enum FunctionalityType {
        STRUCT, DISTINCT, COUNT, STRUCT_SUB, BLANKS, URL_LITERAL,
        STRUCT_INSTANCE, STRUCT_PP, STRUCT_PAIRS,
        CLOSED_SOURCE_TARGET, DATATYPE, DOMAIN_RANGE, IMPORTANCE_INDEX,
        CARDINALITY, INSTANCE_NAMESPACES, LABELS, INVALID_URI
    }

    private MessageLevel messageLevel;
    private FunctionalityType functionalityType;
    private String message;
    private String note;
    private String entity;
    private String relatedEntity;
    private String relatedEntity2;
    private String queryName;
    private String query;

    public SchemaExtractorMessage(MessageLevel messageLevel, String entity, String queryName, String query) {
        this.messageLevel = messageLevel;
        this.entity = entity;
        this.queryName = queryName;
        this.query = query;
    }

    public SchemaExtractorMessage(MessageLevel messageLevel, String message, String entity, String relatedEntity, String queryName, String query) {
        this(messageLevel, entity, queryName, query);
        this.message = message;
        this.relatedEntity = relatedEntity;
    }
}

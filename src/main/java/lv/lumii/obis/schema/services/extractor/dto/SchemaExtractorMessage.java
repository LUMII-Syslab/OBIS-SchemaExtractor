package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorMessage {

    public enum MessageLevel {ERROR, WARNING, WARNING_HIGH, WARNING_LOW, INFO, OK}

    private MessageLevel messageLevel;
    private String message;
    private String entity;
    private String relatedEntity;
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

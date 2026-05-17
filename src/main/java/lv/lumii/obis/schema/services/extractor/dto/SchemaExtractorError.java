package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

@Setter
@Getter
public class SchemaExtractorError {

    public enum ErrorLevel {ERROR, WARNING, WARNING_LOW, INFO, OK}

    private ErrorLevel errorLevel;
    private String message;
    private String entity;
    private String relatedEntity;
    private String queryName;
    private String query;

    public SchemaExtractorError(ErrorLevel errorLevel, String entity, String queryName, String query) {
        this.errorLevel = errorLevel;
        this.entity = entity;
        this.queryName = queryName;
        this.query = query;
    }

    public SchemaExtractorError(ErrorLevel errorLevel, String message, String entity, String relatedEntity, String queryName,  String query) {
        this(errorLevel, entity, queryName, query);
        this.message = message;
        this.relatedEntity = relatedEntity;
    }

    @Override
    public String toString() {
        return errorLevel + " [" + entity + "] - " + queryName + (StringUtils.isNotEmpty(message) ? " - " + message : "") + " - " + query;
    }
}

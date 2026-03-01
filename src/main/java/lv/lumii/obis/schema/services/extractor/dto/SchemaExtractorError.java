package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

@Setter
@Getter
public class SchemaExtractorError {

    public enum ErrorLevel {ERROR, WARNING, INFO, OK}

    private ErrorLevel errorLevel;
    private String entity;
    private String queryName;
    private String message;
    private String query;

    public SchemaExtractorError(ErrorLevel errorLevel, String entity, String queryName, String query) {
        this.errorLevel = errorLevel;
        this.entity = entity;
        this.queryName = queryName;
        this.query = query;
    }

    public SchemaExtractorError(ErrorLevel errorLevel, String entity, String queryName, String message, String query) {
        this(errorLevel, entity, queryName, query);
        this.message = message;
    }

    @Override
    public String toString() {
        return errorLevel + " [" + entity + "] - " + queryName + (StringUtils.isNotEmpty(message) ? " - " + message : "") + " - " + query;
    }
}

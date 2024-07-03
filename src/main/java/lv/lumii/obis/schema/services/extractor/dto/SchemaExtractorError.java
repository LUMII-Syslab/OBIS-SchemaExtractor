package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorError {

    public enum ErrorLevel {ERROR, WARNING, INFO, OK}

    private ErrorLevel errorLevel;
    private String entity;
    private String queryName;
    private String query;

    public SchemaExtractorError(ErrorLevel errorLevel, String entity, String queryName, String query) {
        this.errorLevel = errorLevel;
        this.entity = entity;
        this.queryName = queryName;
        this.query = query;
    }

    @Override
    public String toString() {
        return errorLevel + " [" + entity + "] - " + queryName + " - " + query;
    }
}

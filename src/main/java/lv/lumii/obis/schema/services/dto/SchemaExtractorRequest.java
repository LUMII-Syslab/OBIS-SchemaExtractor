package lv.lumii.obis.schema.services.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaExtractorRequest {

    public enum ExtractionMode {simple, data};

    private String endpointUrl;
    private String graphName;
    private ExtractionMode mode;
    private Boolean logEnabled = Boolean.FALSE;
    private Boolean excludeSystemClasses = Boolean.TRUE;
    private Boolean excludeMetaDomainClasses = Boolean.FALSE;

}

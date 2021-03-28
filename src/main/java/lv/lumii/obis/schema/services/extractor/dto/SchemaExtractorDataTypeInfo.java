package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorDataTypeInfo {

    private String dataType;
    private Long tripleCount;

    public SchemaExtractorDataTypeInfo(String dataType, Long tripleCount) {
        this.dataType = dataType;
        this.tripleCount = tripleCount;
    }
}

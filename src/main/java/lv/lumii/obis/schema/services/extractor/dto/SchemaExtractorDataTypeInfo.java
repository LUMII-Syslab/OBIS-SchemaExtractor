package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorDataTypeInfo {

    private String dataType;
    private Long tripleCount;
    private Long tripleCountBase;

    public SchemaExtractorDataTypeInfo(String dataType, Long tripleCount, Long tripleCountBase) {
        this.dataType = dataType;
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
    }
}

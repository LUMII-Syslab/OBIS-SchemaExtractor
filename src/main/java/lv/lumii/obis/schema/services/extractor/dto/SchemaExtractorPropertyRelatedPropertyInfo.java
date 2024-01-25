package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorPropertyRelatedPropertyInfo {

    private String propertyName;
    private Long tripleCount;
    private Long tripleCountBase;

    public SchemaExtractorPropertyRelatedPropertyInfo(String propertyName, Long tripleCount, Long tripleCountBase) {
        this.propertyName = propertyName;
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
    }
}

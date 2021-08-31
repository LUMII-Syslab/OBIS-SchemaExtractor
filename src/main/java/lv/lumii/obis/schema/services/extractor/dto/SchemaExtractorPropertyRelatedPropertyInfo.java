package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorPropertyRelatedPropertyInfo {

    private String propertyName;
    private Long tripleCount;

    public SchemaExtractorPropertyRelatedPropertyInfo(String propertyName, Long tripleCount) {
        this.propertyName = propertyName;
        this.tripleCount = tripleCount;
    }
}

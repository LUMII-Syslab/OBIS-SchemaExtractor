package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorRequestedPropertyDto {

    private String propertyName;
    private String instanceCount;

    public SchemaExtractorRequestedPropertyDto(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public String toString() {
        return propertyName;
    }
}

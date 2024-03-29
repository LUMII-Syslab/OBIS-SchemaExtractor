package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
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

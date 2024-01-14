package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class SchemaExtractorRequestedClassDto {

    private String className;
    private String instanceCount;

    public SchemaExtractorRequestedClassDto(String className) {
        this.className = className;
    }

    @Override
    public String toString() {
        return className;
    }
}

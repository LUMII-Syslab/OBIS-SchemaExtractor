package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class SchemaExtractorIntersectionClassDto {

    private String className;
    private Long instanceCount;

    public SchemaExtractorIntersectionClassDto(String className) {
        this.className = className;
    }
}

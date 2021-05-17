package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorPropertyLinkedClassInfo {

    private String className;
    private Long classTotalTripleCount;
    private Long propertyTripleCount;
    private Integer importanceIndex;

}

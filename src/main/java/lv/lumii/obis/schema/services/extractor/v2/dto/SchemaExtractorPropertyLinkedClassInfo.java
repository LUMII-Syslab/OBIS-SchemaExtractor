package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

@Setter
@Getter
public class SchemaExtractorPropertyLinkedClassInfo {

    private String className;
    private Long classTotalTripleCount;
    private Long propertyTripleCount;
    private Integer importanceIndex;

    @Nonnull
    public Long getClassTotalTripleCount() {
        if (classTotalTripleCount == null) {
            classTotalTripleCount = 0L;
        }
        return classTotalTripleCount;
    }

    @Nonnull
    public Long getPropertyTripleCount() {
        if (propertyTripleCount == null) {
            propertyTripleCount = 0L;
        }
        return propertyTripleCount;
    }

    @Nonnull
    public Integer getImportanceIndex() {
        if (importanceIndex == null) {
            importanceIndex = 0;
        }
        return importanceIndex;
    }
}

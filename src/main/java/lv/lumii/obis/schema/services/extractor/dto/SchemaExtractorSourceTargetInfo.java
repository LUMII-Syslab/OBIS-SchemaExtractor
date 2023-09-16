package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaExtractorSourceTargetInfo {

    private String sourceClass;
    private String targetClass;

    private Long tripleCount;

    private Boolean validSource;
    private Boolean validTarget;

    private Boolean isPrincipalSource;
    private Boolean isPrincipalTarget;

    private Integer sourceImportanceIndex;
    private Integer targetImportanceIndex;

    private String classificationPropertyForSource;
    private String classificationPropertyForTarget;

    public SchemaExtractorSourceTargetInfo(){}

    public SchemaExtractorSourceTargetInfo(String sourceClass){
        this.sourceClass = sourceClass;
    }

    public SchemaExtractorSourceTargetInfo(String sourceClass, String targetClass, Long tripleCount) {
        this.sourceClass = sourceClass;
        this.targetClass = targetClass;
        this.tripleCount = tripleCount;
    }

    public SchemaExtractorSourceTargetInfo(String sourceClass, String targetClass, Long tripleCount, String classificationPropertyForSource, String classificationPropertyForTarget) {
        this.sourceClass = sourceClass;
        this.targetClass = targetClass;
        this.tripleCount = tripleCount;
        this.classificationPropertyForSource = classificationPropertyForSource;
        this.classificationPropertyForTarget = classificationPropertyForTarget;
    }
}

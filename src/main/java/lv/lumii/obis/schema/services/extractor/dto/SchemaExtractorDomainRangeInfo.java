package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaExtractorDomainRangeInfo {

    private String domainClass;
    private String rangeClass;

    private Long tripleCount;

    private Boolean validDomain;
    private Boolean validRange;

    private Integer sourceImportanceIndex;
    private Integer targetImportanceIndex;

    private String classificationPropertyForDomain;
    private String classificationPropertyForRange;

    public SchemaExtractorDomainRangeInfo(){}

    public SchemaExtractorDomainRangeInfo(String domainClass){
        this.domainClass = domainClass;
    }

    public SchemaExtractorDomainRangeInfo(String domainClass, String rangeClass, Long tripleCount) {
        this.domainClass = domainClass;
        this.rangeClass = rangeClass;
        this.tripleCount = tripleCount;
    }

    public SchemaExtractorDomainRangeInfo(String domainClass, String rangeClass, Long tripleCount, String classificationPropertyForDomain, String classificationPropertyForRange) {
        this.domainClass = domainClass;
        this.rangeClass = rangeClass;
        this.tripleCount = tripleCount;
        this.classificationPropertyForDomain = classificationPropertyForDomain;
        this.classificationPropertyForRange = classificationPropertyForRange;
    }
}

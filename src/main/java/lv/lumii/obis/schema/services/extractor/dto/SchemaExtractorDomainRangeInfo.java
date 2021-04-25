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

    public SchemaExtractorDomainRangeInfo(){}

    public SchemaExtractorDomainRangeInfo(String domainClass){
        this.domainClass = domainClass;
    }

    public SchemaExtractorDomainRangeInfo(String domainClass, String rangeClass, Long tripleCount) {
        this.domainClass = domainClass;
        this.rangeClass = rangeClass;
        this.tripleCount = tripleCount;
    }
}

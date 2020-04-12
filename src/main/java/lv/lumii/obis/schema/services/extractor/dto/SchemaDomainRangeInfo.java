package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaDomainRangeInfo {

    private String domainClass;
    private String rangeClass;

    private Long instanceCount;

    private Boolean validDomain;
    private Boolean validRange;

    public SchemaDomainRangeInfo(){}

    public SchemaDomainRangeInfo(String domainClass){
        this.domainClass = domainClass;
    }
}

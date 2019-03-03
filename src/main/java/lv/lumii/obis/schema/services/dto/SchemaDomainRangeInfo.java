package lv.lumii.obis.schema.services.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaDomainRangeInfo {

    private String domainClass;
    private String rangeClass;

    public SchemaDomainRangeInfo(String domainClass){
        this.domainClass = domainClass;
    }

    public SchemaDomainRangeInfo(String domainClass, String rangeClass){
        this.domainClass = domainClass;
        this.rangeClass = rangeClass;
    }
}

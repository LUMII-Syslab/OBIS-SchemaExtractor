package lv.lumii.obis.schema.services.dto;

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

    public String getDomainClass() {
        return domainClass;
    }

    public void setDomainClass(String domainClass) {
        this.domainClass = domainClass;
    }

    public String getRangeClass() {
        return rangeClass;
    }

    public void setRangeClass(String rangeClass) {
        this.rangeClass = rangeClass;
    }
}

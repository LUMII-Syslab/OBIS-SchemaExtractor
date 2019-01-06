package lv.lumii.obis.schema.services.dto;

import java.util.ArrayList;
import java.util.List;

public class SchemaPropertyNodeInfo {

	private Boolean isObjectProperty = Boolean.FALSE;
	private String dataType;
	private Integer minCardinality;
	private Integer maxCardinality;
	private List<SchemaDomainRangeInfo> domainRangePairs;
	private List<SchemaClassNodeInfo> domainClasses;
	private List<SchemaClassNodeInfo> rangeClasses;

	public List<SchemaDomainRangeInfo> getDomainRangePairs() {
		if(domainRangePairs == null){
			domainRangePairs = new ArrayList<>();
		}
		return domainRangePairs;
	}

	public List<SchemaClassNodeInfo> getDomainClasses() {
		if(domainClasses == null){
			domainClasses = new ArrayList<>();
		}
		return domainClasses;
	}

	public List<SchemaClassNodeInfo> getRangeClasses() {
		if(rangeClasses == null){
			rangeClasses = new ArrayList<>();
		}
		return rangeClasses;
	}

	public Boolean getIsObjectProperty() {
		return isObjectProperty;
	}

	public void setIsObjectProperty(Boolean objectProperty) {
		isObjectProperty = objectProperty;
	}

	public String getDataType() {
		return dataType;
	}

	public void setDataType(String dataType) {
		this.dataType = dataType;
	}

	public Integer getMinCardinality() {
		return minCardinality;
	}

	public void setMinCardinality(Integer minCardinality) {
		this.minCardinality = minCardinality;
	}

	public Integer getMaxCardinality() {
		return maxCardinality;
	}

	public void setMaxCardinality(Integer maxCardinality) {
		this.maxCardinality = maxCardinality;
	}

}

package lv.lumii.obis.schema.services.dto;

import java.util.ArrayList;
import java.util.List;

public class SchemaPropertyNodeInfo {

	private Boolean isObjectProperty = Boolean.FALSE;
	private String domainClass;
	private String rangeClass;
	private String dataType;
	private Integer minCardinality;
	private Integer maxCardinality;
	private List<SchemaClassNodeInfo> assignedClasses;
	private List<SchemaClassNodeInfo> rangeClasses;

	public List<SchemaClassNodeInfo> getAssignedClasses() {
		if(assignedClasses == null){
			assignedClasses = new ArrayList<>();
		}
		return assignedClasses;
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

	public void setAssignedClasses(List<SchemaClassNodeInfo> assignedClasses) {
		this.assignedClasses = assignedClasses;
	}

	public void setRangeClasses(List<SchemaClassNodeInfo> rangeClasses) {
		this.rangeClasses = rangeClasses;
	}
}

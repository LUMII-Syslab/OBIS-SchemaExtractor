package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lv.lumii.obis.schema.constants.SchemaConstants;

public class SchemaProperty extends SchemaEntity {

	private Integer minCardinality;
	private Integer maxCardinality;

	@JsonIgnore
	public String getDomainClass(){
		return SchemaConstants.THING_URI;
	}

	@JsonIgnore
	public String getRangeClass(){
		return SchemaConstants.THING_URI;
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

package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.services.SchemaUtil;

public class SchemaAttribute extends SchemaProperty {
	
	private String type;

	@JsonProperty("SourceClasses")
	private List<String> sourceClasses;

	@JsonIgnore
	private String rangeLookupValues;

	public List<String> getSourceClasses() {
		if(sourceClasses == null){
			sourceClasses = new ArrayList<>();
		}
		return sourceClasses;
	}

	@JsonIgnore
	@Override
	public String getDomainClass(){
		if(sourceClasses.isEmpty()){
			return SchemaConstants.THING_URI;
		}
		return sourceClasses.get(0);
	}

	@JsonIgnore
	@Override
	public String getRangeClass(){
		if(SchemaUtil.isEmpty(type)){
			return SchemaConstants.THING_URI;
		}
		return type;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public void setSourceClasses(List<String> sourceClasses) {
		this.sourceClasses = sourceClasses;
	}

	public String getRangeLookupValues() {
		return rangeLookupValues;
	}

	public void setRangeLookupValues(String rangeLookupValues) {
		this.rangeLookupValues = rangeLookupValues;
	}
}

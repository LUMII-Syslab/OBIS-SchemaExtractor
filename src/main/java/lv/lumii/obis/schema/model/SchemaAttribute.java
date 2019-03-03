package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.services.SchemaUtil;

@Setter @Getter
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

}

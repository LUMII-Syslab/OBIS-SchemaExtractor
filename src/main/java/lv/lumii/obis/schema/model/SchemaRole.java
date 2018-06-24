package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lv.lumii.obis.schema.constants.SchemaConstants;

public class SchemaRole extends SchemaProperty {
	
	@JsonProperty("ClassPairs")
	private List<ClassPair> classPairs;

	@JsonIgnore
	private SchemaRole inverseProperty;

	public List<ClassPair> getClassPairs() {
		if(classPairs == null){
			classPairs = new ArrayList<>();
		}
		return classPairs;
	}

	@JsonIgnore
	@Override
	public String getDomainClass(){
		if(classPairs.isEmpty()){
			return SchemaConstants.THING_URI;
		}
		return classPairs.get(0).getSourceClass();
	}

	@JsonIgnore
	@Override
	public String getRangeClass(){
		if(classPairs.isEmpty()){
			return SchemaConstants.THING_URI;
		}
		return classPairs.get(0).getTargetClass();
	}

	public void setClassPairs(List<ClassPair> classPairs) {
		this.classPairs = classPairs;
	}

	public SchemaRole getInverseProperty() {
		return inverseProperty;
	}

	public void setInverseProperty(SchemaRole inverseProperty) {
		this.inverseProperty = inverseProperty;
	}
}

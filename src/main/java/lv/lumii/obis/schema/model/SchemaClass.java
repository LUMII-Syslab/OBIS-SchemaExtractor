package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SchemaClass extends SchemaEntity {
	
	@JsonProperty("SuperClasses")
	private List<String> superClasses;

	@JsonProperty("SubClasses")
	private List<String> subClasses;

	public List<String> getSuperClasses() {
		if(superClasses == null){
			superClasses = new ArrayList<>();
		}
		return superClasses;
	}

	public List<String> getSubClasses() {
		if(subClasses == null){
			subClasses = new ArrayList<>();
		}
		return subClasses;
	}

	public void setSuperClasses(List<String> superClasses) {
		this.superClasses = superClasses;
	}

	public void setSubClasses(List<String> subClasses) {
		this.subClasses = subClasses;
	}
}

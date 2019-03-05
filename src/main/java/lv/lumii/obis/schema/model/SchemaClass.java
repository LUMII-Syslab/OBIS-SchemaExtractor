package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaClass extends SchemaEntity {
	
	@JsonProperty("SuperClasses")
	private List<String> superClasses;

	@JsonIgnore
	private List<String> subClasses;

	private Integer instanceCount;

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
}

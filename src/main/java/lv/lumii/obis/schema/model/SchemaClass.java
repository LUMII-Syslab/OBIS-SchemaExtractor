package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Setter @Getter
public class SchemaClass extends SchemaElement {
	
	@JsonProperty("SuperClasses")
	private Set<String> superClasses;

	@JsonIgnore
	private Set<String> subClasses;

	private Long instanceCount;
	private Long orderIndex;

	public Set<String> getSuperClasses() {
		if(superClasses == null){
			superClasses = new HashSet<>();
		}
		return superClasses;
	}

	public Set<String> getSubClasses() {
		if(subClasses == null){
			subClasses = new HashSet<>();
		}
		return subClasses;
	}
}

package lv.lumii.obis.schema.model.v1;

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
	@JsonProperty("EquivalentClasses")
	private Set<String> equivalentClasses;

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

	public Set<String> getEquivalentClasses() {
		if(equivalentClasses == null){
			equivalentClasses = new HashSet<>();
		}
		return equivalentClasses;
	}
}

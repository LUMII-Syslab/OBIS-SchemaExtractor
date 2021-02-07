package lv.lumii.obis.schema.model;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaAttribute extends SchemaProperty {
	
	private String type;

	@JsonProperty("SourceClasses")
	private Set<String> sourceClasses;

	@JsonProperty("SourceClassesDetailed")
	private Set<SchemaPropertyLinkedClassDetails> sourceClassesDetailed;

	@JsonIgnore
	private String rangeLookupValues;

	public Set<String> getSourceClasses() {
		if(sourceClasses == null){
			sourceClasses = new HashSet<>();
		}
		return sourceClasses;
	}

	public Set<SchemaPropertyLinkedClassDetails> getSourceClassesDetailed() {
		if(sourceClassesDetailed == null){
			sourceClassesDetailed = new HashSet<>();
		}
		return sourceClassesDetailed;
	}

}

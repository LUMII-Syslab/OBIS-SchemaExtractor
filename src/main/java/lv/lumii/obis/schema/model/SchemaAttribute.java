package lv.lumii.obis.schema.model;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

@Setter @Getter
public class SchemaAttribute extends SchemaProperty {
	
	private String type;

	@JsonProperty("SourceClasses")
	private Set<String> sourceClasses;

	@JsonIgnore
	private String rangeLookupValues;

	@Nonnull
	public Set<String> getSourceClasses() {
		if(sourceClasses == null){
			sourceClasses = new HashSet<>();
		}
		return sourceClasses;
	}

}

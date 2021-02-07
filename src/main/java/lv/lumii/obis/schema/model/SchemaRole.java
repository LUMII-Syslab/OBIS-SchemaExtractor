package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaRole extends SchemaProperty {

	private String inverseProperty;
	
	@JsonProperty("ClassPairs")
	private List<ClassPair> classPairs;

	@JsonProperty("SourceClassesDetailed")
	private Set<SchemaPropertyLinkedClassDetails> sourceClassesDetailed;

	@JsonProperty("TargetClassesDetailed")
	private Set<SchemaPropertyLinkedClassDetails> targetClassesDetailed;

	public List<ClassPair> getClassPairs() {
		if(classPairs == null){
			classPairs = new ArrayList<>();
		}
		return classPairs;
	}

	public Set<SchemaPropertyLinkedClassDetails> getSourceClassesDetailed() {
		if(sourceClassesDetailed == null){
			sourceClassesDetailed = new HashSet<>();
		}
		return sourceClassesDetailed;
	}

	public Set<SchemaPropertyLinkedClassDetails> getTargetClassesDetailed() {
		if(targetClassesDetailed == null){
			targetClassesDetailed = new HashSet<>();
		}
		return targetClassesDetailed;
	}

}

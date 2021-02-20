package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

@Setter @Getter
public class SchemaRole extends SchemaProperty {

	private String inverseProperty;

	@JsonProperty("TargetClassesDetailed")
	private Set<SchemaPropertyLinkedClassDetails> targetClassesDetailed;

	@JsonProperty("ClassPairs")
	private List<ClassPair> classPairs;

	@Nonnull
	public Set<SchemaPropertyLinkedClassDetails> getTargetClassesDetailed() {
		if(targetClassesDetailed == null){
			targetClassesDetailed = new HashSet<>();
		}
		return targetClassesDetailed;
	}

	@Nonnull
	public List<ClassPair> getClassPairs() {
		if(classPairs == null){
			classPairs = new ArrayList<>();
		}
		return classPairs;
	}

}

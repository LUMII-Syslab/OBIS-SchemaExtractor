package lv.lumii.obis.schema.model.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Setter @Getter
public abstract class SchemaProperty extends SchemaElement {

	private Integer minCardinality;
	private Integer maxCardinality;
	private Long tripleCount;
	private Long objectTripleCount;
	private Boolean closedDomain;
	private Boolean closedRange;

	@JsonProperty("SourceClassesDetailed")
	private Set<SchemaPropertyLinkedClassDetails> sourceClassesDetailed;

	@JsonProperty("TargetClassesDetailed")
	private Set<SchemaPropertyLinkedClassDetails> targetClassesDetailed;

	@JsonProperty("ClassPairs")
	private List<ClassPair> classPairs;

	@Nonnull
	public Set<SchemaPropertyLinkedClassDetails> getSourceClassesDetailed() {
		if(sourceClassesDetailed == null){
			sourceClassesDetailed = new HashSet<>();
		}
		return sourceClassesDetailed;
	}

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

package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Setter @Getter
public class SchemaProperty extends SchemaElement {

	private Integer maxCardinality;
	private Long tripleCount;
	private Long objectTripleCount;
	private Boolean closedDomain;
	private Boolean closedRange;

	@JsonProperty("DataTypes")
	private List<DataType> dataTypes;

	@JsonProperty("SourceClasses")
	private Set<SchemaPropertyLinkedClassDetails> sourceClasses;

	@JsonProperty("TargetClasses")
	private Set<SchemaPropertyLinkedClassDetails> targetClasses;

	@JsonProperty("ClassPairs")
	private List<ClassPair> classPairs;

	@Nonnull
	public List<DataType> getDataTypes() {
		if (dataTypes == null) {
			dataTypes = new ArrayList<>();
		}
		return dataTypes;
	}

	@Nonnull
	public Set<SchemaPropertyLinkedClassDetails> getSourceClasses() {
		if(sourceClasses == null){
			sourceClasses = new HashSet<>();
		}
		return sourceClasses;
	}

	@Nonnull
	public Set<SchemaPropertyLinkedClassDetails> getTargetClasses() {
		if(targetClasses == null){
			targetClasses = new HashSet<>();
		}
		return targetClasses;
	}

	@Nonnull
	public List<ClassPair> getClassPairs() {
		if(classPairs == null){
			classPairs = new ArrayList<>();
		}
		return classPairs;
	}

}

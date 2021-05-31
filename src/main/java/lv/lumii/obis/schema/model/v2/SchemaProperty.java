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
	private Integer maxInverseCardinality;
	private Long tripleCount;
	private Long dataTripleCount;
	private Long objectTripleCount;
	private Boolean closedDomain;
	private Boolean closedRange;

	@JsonProperty("DataTypes")
	private List<DataType> dataTypes;

	@JsonProperty("SourceClasses")
	private List<SchemaPropertyLinkedClassDetails> sourceClasses;

	@JsonProperty("TargetClasses")
	private List<SchemaPropertyLinkedClassDetails> targetClasses;

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
	public List<SchemaPropertyLinkedClassDetails> getSourceClasses() {
		if(sourceClasses == null){
			sourceClasses = new ArrayList<>();
		}
		return sourceClasses;
	}

	@Nonnull
	public List<SchemaPropertyLinkedClassDetails> getTargetClasses() {
		if(targetClasses == null){
			targetClasses = new ArrayList<>();
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

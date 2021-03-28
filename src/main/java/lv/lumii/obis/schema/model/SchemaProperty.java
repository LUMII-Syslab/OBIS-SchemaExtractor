package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.HashSet;
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

	@Nonnull
	public Set<SchemaPropertyLinkedClassDetails> getSourceClassesDetailed() {
		if(sourceClassesDetailed == null){
			sourceClassesDetailed = new HashSet<>();
		}
		return sourceClassesDetailed;
	}

}

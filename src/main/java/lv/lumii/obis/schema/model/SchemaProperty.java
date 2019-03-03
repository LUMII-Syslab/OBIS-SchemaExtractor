package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public abstract class SchemaProperty extends SchemaEntity {

	private Integer minCardinality;
	private Integer maxCardinality;

	@JsonIgnore
	public abstract String getDomainClass();
	@JsonIgnore
	public abstract String getRangeClass();

}

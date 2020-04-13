package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public abstract class SchemaProperty extends SchemaElement {

	private Integer minCardinality;
	private Integer maxCardinality;

}

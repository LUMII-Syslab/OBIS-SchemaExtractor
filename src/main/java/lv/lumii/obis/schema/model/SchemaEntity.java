package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaEntity extends AnnotationElement {

	private String localName;
	private String namespace;
	private String fullName;

}

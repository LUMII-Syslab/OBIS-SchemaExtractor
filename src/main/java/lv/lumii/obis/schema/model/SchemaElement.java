package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaElement extends AnnotationElement {

	private String localName;
	private String namespace;
	private String fullName;

}

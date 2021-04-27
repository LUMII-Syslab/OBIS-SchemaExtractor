package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaElement {

    private String localName;
    private String namespace;
    private String fullName;

}

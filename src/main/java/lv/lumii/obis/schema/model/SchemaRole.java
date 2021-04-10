package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaRole extends SchemaProperty {

    private String inverseProperty;

}

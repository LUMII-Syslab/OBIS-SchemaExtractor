package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaAttributeDataType {

    private String dataType;
    private Long tripleCount;

    public SchemaAttributeDataType(String dataType, Long tripleCount) {
        this.dataType = dataType;
        this.tripleCount = tripleCount;
    }
}

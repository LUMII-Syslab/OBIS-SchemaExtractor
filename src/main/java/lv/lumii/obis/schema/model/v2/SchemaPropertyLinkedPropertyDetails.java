package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaPropertyLinkedPropertyDetails {

    private String propertyName;
    private Long tripleCount;

    public SchemaPropertyLinkedPropertyDetails(String propertyName, Long tripleCount) {
        this.propertyName = propertyName;
        this.tripleCount = tripleCount;
    }
}

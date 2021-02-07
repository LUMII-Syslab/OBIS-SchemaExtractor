package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaPropertyLinkedClassDetails {

    private String classFullName;

    private Long instanceCount;

    public SchemaPropertyLinkedClassDetails(String classFullName, Long instanceCount) {
        this.classFullName = classFullName;
        this.instanceCount = instanceCount;
    }
}

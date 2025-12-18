package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaIntersactionClass {

    private String className;
    private Long instanceCount;

    public SchemaIntersactionClass(String className, Long instanceCount) {
        this.className = className;
        this.instanceCount = instanceCount;
    }
}

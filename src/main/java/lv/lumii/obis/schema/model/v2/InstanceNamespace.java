package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class InstanceNamespace {

    private String namespace;
    private Long count;
    private Long limit;

    public InstanceNamespace(String namespace, Long count) {
        this.namespace = namespace;
        this.count = count;
    }

    public InstanceNamespace(String namespace, Long count, Long limit) {
        this.namespace = namespace;
        this.count = count;
        this.limit = limit;
    }
}

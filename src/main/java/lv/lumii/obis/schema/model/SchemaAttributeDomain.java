package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaAttributeDomain {

    private String domain;

    private Long instanceCount;

    public SchemaAttributeDomain(String domain, Long instanceCount) {
        this.domain = domain;
        this.instanceCount = instanceCount;
    }
}

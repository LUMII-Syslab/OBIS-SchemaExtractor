package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaPropertyLinkedClassDetails {

    private String classFullName;

    private Long instanceCount;

    private Long objectTripleCount;

    private Integer minCardinality;

    public SchemaPropertyLinkedClassDetails(String classFullName, Long instanceCount) {
        this.classFullName = classFullName;
        this.instanceCount = instanceCount;
    }

    public SchemaPropertyLinkedClassDetails(String classFullName, Long instanceCount, Long objectTripleCount, Integer minCardinality) {
        this.classFullName = classFullName;
        this.instanceCount = instanceCount;
        this.objectTripleCount = objectTripleCount;
        this.minCardinality = minCardinality;
    }
}

package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaPropertyLinkedClassDetails {

    private String classFullName;

    private Long tripleCount;

    private Long objectTripleCount;

    private Integer minCardinality;

    public SchemaPropertyLinkedClassDetails(String classFullName, Long tripleCount) {
        this.classFullName = classFullName;
        this.tripleCount = tripleCount;
    }

    public SchemaPropertyLinkedClassDetails(String classFullName, Long tripleCount, Long objectTripleCount, Integer minCardinality) {
        this.classFullName = classFullName;
        this.tripleCount = tripleCount;
        this.objectTripleCount = objectTripleCount;
        this.minCardinality = minCardinality;
    }
}

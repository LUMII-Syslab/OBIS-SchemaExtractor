package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaPropertyLinkedClassDetails {

    private String classFullName;

    private Long tripleCount;

    private Long objectTripleCount;

    private Boolean importanceIndex;

    private Integer minCardinality;

    public SchemaPropertyLinkedClassDetails(String classFullName, Long tripleCount, Long objectTripleCount, Integer minCardinality, Boolean importanceIndex) {
        this.classFullName = classFullName;
        this.tripleCount = tripleCount;
        this.objectTripleCount = objectTripleCount;
        this.minCardinality = minCardinality;
        this.importanceIndex = importanceIndex;
    }
}

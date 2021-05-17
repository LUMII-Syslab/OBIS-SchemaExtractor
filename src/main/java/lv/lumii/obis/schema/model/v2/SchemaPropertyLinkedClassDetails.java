package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaPropertyLinkedClassDetails {

    private String classFullName;

    private Long tripleCount;

    private Long objectTripleCount;

    private Integer importanceIndex;

    private Integer minCardinality;

    private Integer maxCardinality;

    private Integer minInverseCardinality;

    private Integer maxInverseCardinality;

    public SchemaPropertyLinkedClassDetails(String classFullName, Long tripleCount, Long objectTripleCount,
                                            Integer minCardinality, Integer maxCardinality,
                                            Integer minInverseCardinality, Integer maxInverseCardinality,
                                            Integer importanceIndex) {
        this.classFullName = classFullName;
        this.tripleCount = tripleCount;
        this.objectTripleCount = objectTripleCount;
        this.minCardinality = minCardinality;
        this.maxCardinality = maxCardinality;
        this.minInverseCardinality = minInverseCardinality;
        this.maxInverseCardinality = maxInverseCardinality;
        this.importanceIndex = importanceIndex;
    }
}

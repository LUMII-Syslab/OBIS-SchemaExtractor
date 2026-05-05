package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorDataTypeInfo;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

@Setter
@Getter
public class SchemaPropertyLinkedClassDetails {

    private String classFullName;

    private Long tripleCount;

    private Long tripleCountBase;

    private Long dataTripleCount;

    private Long objectTripleCount;
    private Long distinctSubjectsCount;
    private Long distinctObjectsCount;

    private Boolean closedDomain;
    private Integer closedSourceAssertedSize;
    private Boolean closedRange;
    private Integer closedTargetAssertedSize;

    private Boolean isPrincipal;
    private Integer principalAssertedSize;

    private Integer importanceIndex;

    private Integer minCardinality;
    private Integer minCardinalityAssertionSize;

    private Integer maxCardinality;
    private Integer maxCardinalityAssertionSize;

    private Integer minInverseCardinality;
    private Integer minInverseCardinalityAssertionSize;

    private Integer maxInverseCardinality;
    private Integer maxInverseCardinalityAssertionSize;

    @JsonProperty("DataTypes")
    private List<DataType> dataTypes;

    public SchemaPropertyLinkedClassDetails(String classFullName,
                                            Long tripleCount, Long tripleCountBase, Long dataTripleCount, Long objectTripleCount,
                                            Long distinctSubjectsCount, Long distinctObjectsCount,
                                            Boolean closedDomain, Integer closedSourceAssertedSize, Boolean closedRange, Integer closedTargetAssertedSize,
                                            Boolean isPrincipal, Integer principalAssertedSize,
                                            Integer minCardinality, Integer minCardinalityAssertionSize, Integer maxCardinality, Integer maxCardinalityAssertionSize,
                                            Integer minInverseCardinality, Integer minInverseCardinalityAssertionSize, Integer maxInverseCardinality, Integer maxInverseCardinalityAssertionSize,
                                            Integer importanceIndex, List<SchemaExtractorDataTypeInfo> dataTypes) {
        this.classFullName = classFullName;
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
        this.dataTripleCount = dataTripleCount;
        this.objectTripleCount = objectTripleCount;
        this.distinctSubjectsCount = distinctSubjectsCount;
        this.distinctObjectsCount = distinctObjectsCount;
        this.closedDomain = closedDomain;
        this.closedSourceAssertedSize = closedSourceAssertedSize;
        this.closedRange = closedRange;
        this.closedTargetAssertedSize = closedTargetAssertedSize;
        this.isPrincipal = isPrincipal;
        this.principalAssertedSize = principalAssertedSize;
        this.minCardinality = minCardinality;
        this.minCardinalityAssertionSize = minCardinalityAssertionSize;
        this.maxCardinality = maxCardinality;
        this.maxCardinalityAssertionSize = maxCardinalityAssertionSize;
        this.minInverseCardinality = minInverseCardinality;
        this.minInverseCardinalityAssertionSize = minInverseCardinalityAssertionSize;
        this.maxInverseCardinality = maxInverseCardinality;
        this.maxInverseCardinalityAssertionSize = maxInverseCardinalityAssertionSize;
        this.importanceIndex = importanceIndex;
        if(!CollectionUtils.isEmpty(dataTypes)) {
            this.dataTypes = dataTypes.stream().map(d -> new DataType(d.getDataType(), d.getTripleCount(), d.getTripleCountBase())).collect(Collectors.toList());
        } else {
            this.dataTypes = null;
        }
    }
}

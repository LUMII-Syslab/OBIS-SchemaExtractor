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

    private Long dataTripleCount;

    private Long objectTripleCount;

    private Boolean closedDomain;

    private Boolean closedRange;

    private Integer importanceIndex;

    private Integer minCardinality;

    private Integer maxCardinality;

    private Integer minInverseCardinality;

    private Integer maxInverseCardinality;

    @JsonProperty("DataTypes")
    private List<DataType> dataTypes;

    public SchemaPropertyLinkedClassDetails(String classFullName,
                                            Long tripleCount, Long dataTripleCount, Long objectTripleCount,
                                            Boolean closedDomain, Boolean closedRange,
                                            Integer minCardinality, Integer maxCardinality,
                                            Integer minInverseCardinality, Integer maxInverseCardinality,
                                            Integer importanceIndex, List<SchemaExtractorDataTypeInfo> dataTypes) {
        this.classFullName = classFullName;
        this.tripleCount = tripleCount;
        this.dataTripleCount = dataTripleCount;
        this.objectTripleCount = objectTripleCount;
        this.closedDomain = closedDomain;
        this.closedRange = closedRange;
        this.minCardinality = minCardinality;
        this.maxCardinality = maxCardinality;
        this.minInverseCardinality = minInverseCardinality;
        this.maxInverseCardinality = maxInverseCardinality;
        this.importanceIndex = importanceIndex;
        if(!CollectionUtils.isEmpty(dataTypes)) {
            this.dataTypes = dataTypes.stream().map(d -> new DataType(d.getDataType(), d.getTripleCount())).collect(Collectors.toList());
        } else {
            this.dataTypes = null;
        }
    }
}

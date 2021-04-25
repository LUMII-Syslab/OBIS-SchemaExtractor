package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class SchemaExtractorPropertyNodeInfo {

    private String propertyName;
    private Boolean isObjectProperty = Boolean.FALSE;
    private String dataType;
    private Integer minCardinality;
    private Integer maxCardinality;
    private Long tripleCount;
    private Long objectTripleCount;
    private Boolean isClosedDomain;
    private Boolean isClosedRange;
    private List<SchemaExtractorDomainRangeInfo> domainRangePairs;
    private List<SchemaExtractorClassNodeInfo> domainClasses;
    private List<SchemaExtractorClassNodeInfo> rangeClasses;
    private List<SchemaExtractorDataTypeInfo> dataTypes;

    @Nonnull
    public List<SchemaExtractorDomainRangeInfo> getDomainRangePairs() {
        if (domainRangePairs == null) {
            domainRangePairs = new ArrayList<>();
        }
        return domainRangePairs;
    }

    @Nonnull
    public List<SchemaExtractorClassNodeInfo> getDomainClasses() {
        if (domainClasses == null) {
            domainClasses = new ArrayList<>();
        }
        return domainClasses;
    }

    @Nonnull
    public List<SchemaExtractorClassNodeInfo> getRangeClasses() {
        if (rangeClasses == null) {
            rangeClasses = new ArrayList<>();
        }
        return rangeClasses;
    }

    @Nonnull
    public List<SchemaExtractorDataTypeInfo> getDataTypes() {
        if (dataTypes == null) {
            dataTypes = new ArrayList<>();
        }
        return dataTypes;
    }
}

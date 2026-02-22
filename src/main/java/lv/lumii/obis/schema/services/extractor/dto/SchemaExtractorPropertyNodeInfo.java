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
    private Integer maxInverseCardinality;
    private Long tripleCount;
    private Long dataTripleCount;
    private Long objectTripleCount;
    private Long blankNodeObjects;
    private Long blankNodeSubjects;
    private Long distinctSubjectsCount;
    private Long distinctObjectsCount;
    private Boolean isClosedDomain;
    private Boolean isClosedRange;
    private Boolean hasDomain;
    private Boolean hasRange;
    private Boolean hasOutgoingPropertiesOK;
    private Boolean hasIncomingPropertiesOK;
    private Boolean hasFollowersOK;
    private List<SchemaExtractorSourceTargetInfo> sourceAndTargetPairs;
    private List<SchemaExtractorClassNodeInfo> sourceClasses;
    private List<SchemaExtractorClassNodeInfo> targetClasses;
    private List<SchemaExtractorDataTypeInfo> dataTypes;
    private List<SchemaExtractorPropertyRelatedPropertyInfo> followers;
    private List<SchemaExtractorPropertyRelatedPropertyInfo> outgoingProperties;
    private List<SchemaExtractorPropertyRelatedPropertyInfo> incomingProperties;

    @Nonnull
    public List<SchemaExtractorSourceTargetInfo> getSourceAndTargetPairs() {
        if (sourceAndTargetPairs == null) {
            sourceAndTargetPairs = new ArrayList<>();
        }
        return sourceAndTargetPairs;
    }

    @Nonnull
    public List<SchemaExtractorClassNodeInfo> getSourceClasses() {
        if (sourceClasses == null) {
            sourceClasses = new ArrayList<>();
        }
        return sourceClasses;
    }

    @Nonnull
    public List<SchemaExtractorClassNodeInfo> getTargetClasses() {
        if (targetClasses == null) {
            targetClasses = new ArrayList<>();
        }
        return targetClasses;
    }

    @Nonnull
    public List<SchemaExtractorDataTypeInfo> getDataTypes() {
        if (dataTypes == null) {
            dataTypes = new ArrayList<>();
        }
        return dataTypes;
    }

    public List<SchemaExtractorPropertyRelatedPropertyInfo> getFollowers() {
        if (followers == null) {
            followers = new ArrayList<>();
        }
        return followers;
    }

    public List<SchemaExtractorPropertyRelatedPropertyInfo> getOutgoingProperties() {
        if (outgoingProperties == null) {
            outgoingProperties = new ArrayList<>();
        }
        return outgoingProperties;
    }

    public List<SchemaExtractorPropertyRelatedPropertyInfo> getIncomingProperties() {
        if (incomingProperties == null) {
            incomingProperties = new ArrayList<>();
        }
        return incomingProperties;
    }
}

package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Setter
@Getter
public class SchemaProperty extends SchemaElement {

    private Integer maxCardinality;
    private Integer maxInverseCardinality;
    private Long tripleCount;
    private Long dataTripleCount;
    private Long objectTripleCount;
    private Boolean closedDomain;
    private Boolean closedRange;

    @JsonProperty("DataTypes")
    private List<DataType> dataTypes;

    @JsonProperty("SourceClasses")
    private List<SchemaPropertyLinkedClassDetails> sourceClasses;

    @JsonProperty("TargetClasses")
    private List<SchemaPropertyLinkedClassDetails> targetClasses;

    @JsonProperty("ClassPairs")
    private List<ClassPair> classPairs;

    @JsonProperty("Followers")
    private List<SchemaPropertyLinkedPropertyDetails> followers;
    @JsonProperty("OutgoingProperties")
    private List<SchemaPropertyLinkedPropertyDetails> outgoingProperties;
    @JsonProperty("IncomingProperties")
    private List<SchemaPropertyLinkedPropertyDetails> incomingProperties;

    @Nonnull
    public List<DataType> getDataTypes() {
        if (dataTypes == null) {
            dataTypes = new ArrayList<>();
        }
        return dataTypes;
    }

    @Nonnull
    public List<SchemaPropertyLinkedClassDetails> getSourceClasses() {
        if (sourceClasses == null) {
            sourceClasses = new ArrayList<>();
        }
        return sourceClasses;
    }

    @Nonnull
    public List<SchemaPropertyLinkedClassDetails> getTargetClasses() {
        if (targetClasses == null) {
            targetClasses = new ArrayList<>();
        }
        return targetClasses;
    }

    @Nonnull
    public List<ClassPair> getClassPairs() {
        if (classPairs == null) {
            classPairs = new ArrayList<>();
        }
        return classPairs;
    }

    public List<SchemaPropertyLinkedPropertyDetails> getFollowers() {
        if (followers == null) {
            followers = new ArrayList<>();
        }
        return followers;
    }

    public List<SchemaPropertyLinkedPropertyDetails> getOutgoingProperties() {
        if (outgoingProperties == null) {
            outgoingProperties = new ArrayList<>();
        }
        return outgoingProperties;
    }

    public List<SchemaPropertyLinkedPropertyDetails> getIncomingProperties() {
        if (incomingProperties == null) {
            incomingProperties = new ArrayList<>();
        }
        return incomingProperties;
    }
}

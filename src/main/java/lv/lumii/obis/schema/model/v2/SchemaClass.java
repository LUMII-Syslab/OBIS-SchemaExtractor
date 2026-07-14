package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.*;

@Setter
@Getter
public class SchemaClass extends SchemaElement {

    @JsonProperty("SuperClasses")
    private Set<String> superClasses;

    @JsonProperty("IntersectionClasses")
    private List<SchemaIntersactionClass> intersectionClasses;

    @JsonIgnore
    private Set<String> subClasses;

    private Long instanceCount;

    private Long distinctInstances;

    private Long blankNodeCount;

    private Long incomingTripleCount;

    private String dataType;

    private Boolean isLiteral;

    private Boolean propertiesInSchema;

    private String classificationProperty;

    private Integer incomingPropertiesOK;
    private Integer outgoingPropertiesOK;

    @JsonIgnore
    private Map<String, TripleCount> outgoingProperties;
    @JsonIgnore
    private Boolean outgoingPropertiesFullList;

    @JsonIgnore
    private Map<String, TripleCount> incomingProperties;
    @JsonIgnore
    private Boolean incomingPropertiesFullList;

    @JsonProperty("InstanceNamespaces")
    private List<InstanceNamespace> instanceNamespaces;

    @Nonnull
    public Set<String> getSuperClasses() {
        if (superClasses == null) {
            superClasses = new HashSet<>();
        }
        return superClasses;
    }

    @Nonnull
    public Set<String> getSubClasses() {
        if (subClasses == null) {
            subClasses = new HashSet<>();
        }
        return subClasses;
    }

    @Nonnull
    public List<SchemaIntersactionClass> getIntersectionClasses() {
        if (intersectionClasses == null) {
            intersectionClasses = new ArrayList<>();
        }
        return intersectionClasses;
    }

    @Nonnull
    public List<InstanceNamespace> getInstanceNamespaces() {
        if (instanceNamespaces == null) {
            instanceNamespaces = new ArrayList<>();
        }
        return instanceNamespaces;
    }

    @Nonnull
    public Map<String, TripleCount> getOutgoingProperties() {
        if (outgoingProperties == null) {
            outgoingProperties = new HashMap<>();
        }
        return outgoingProperties;
    }

    @Nonnull
    public Map<String, TripleCount> getIncomingProperties() {
        if (incomingProperties == null) {
            incomingProperties = new HashMap<>();
        }
        return incomingProperties;
    }
}

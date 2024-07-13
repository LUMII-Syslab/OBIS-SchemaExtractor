package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class SchemaClass extends SchemaElement {

    @JsonProperty("SuperClasses")
    private Set<String> superClasses;

    @JsonProperty("IntersectionClasses")
    private Set<String> intersectionClasses;

    @JsonIgnore
    private Set<String> subClasses;

    private Long instanceCount;

    private Long incomingTripleCount;

    private String dataType;

    private Boolean isLiteral;

    private Boolean propertiesInSchema;

    private String classificationProperty;

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
    public Set<String> getIntersectionClasses() {
        if (intersectionClasses == null) {
            intersectionClasses = new HashSet<>();
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
}

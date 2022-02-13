package lv.lumii.obis.schema.model.v1;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

@Setter
@Getter
public class SchemaAttribute extends SchemaProperty {

    private String type;

    @JsonProperty("SourceClasses")
    private Set<String> sourceClasses;

    @JsonIgnore
    private String rangeLookupValues;

    private List<SchemaAttributeDataType> dataTypes;

    @Nonnull
    public Set<String> getSourceClasses() {
        if (sourceClasses == null) {
            sourceClasses = new HashSet<>();
        }
        return sourceClasses;
    }

    @Nonnull
    public List<SchemaAttributeDataType> getDataTypes() {
        if (dataTypes == null) {
            dataTypes = new ArrayList<>();
        }
        return dataTypes;
    }

}

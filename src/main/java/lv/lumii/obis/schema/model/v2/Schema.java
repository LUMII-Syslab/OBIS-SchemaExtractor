package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class Schema {

    @JsonProperty("SchemaName")
    private String name;

    @JsonProperty("Classes")
    private List<SchemaClass> classes;

    @JsonProperty("Properties")
    private List<SchemaProperty> properties;

    @JsonProperty("namespace")
    private String defaultNamespace;

    @JsonProperty("Prefixes")
    private List<NamespacePrefixEntry> prefixes;

    @JsonProperty("Parameters")
    private SchemaExtractorRequestDto executionParameters;

    @Nonnull
    public List<SchemaClass> getClasses() {
        if (classes == null) {
            classes = new ArrayList<>();
        }
        return classes;
    }

    @Nonnull
    public List<SchemaProperty> getProperties() {
        if (properties == null) {
            properties = new ArrayList<>();
        }
        return properties;
    }

    @Nonnull
    public List<NamespacePrefixEntry> getPrefixes() {
        if (prefixes == null) {
            prefixes = new ArrayList<>();
        }
        return prefixes;
    }

}

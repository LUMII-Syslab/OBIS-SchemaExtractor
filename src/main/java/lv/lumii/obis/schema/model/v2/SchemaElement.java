package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class SchemaElement {

    private String localName;
    private String namespace;
    private String fullName;

    @JsonProperty("Labels")
    private List<Label> labels;

    @Nonnull
    public List<Label> getLabels() {
        if (labels == null) {
            labels = new ArrayList<>();
        }
        return labels;
    }
}

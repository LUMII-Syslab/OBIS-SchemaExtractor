package lv.lumii.obis.schema.model.v1;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
public class SchemaElement {

    private String localName;
    private String namespace;
    private String fullName;
    private Boolean isAbstract;
    private Map<String, String> comment;
    private Map<String, String> label;

    public void addComment(@Nullable String key, @Nullable String value) {
        if (key == null || value == null) {
            return;
        }
        if (comment == null) {
            comment = new HashMap<>();
        }
        comment.put(key, value);
    }

    public void addLabel(@Nullable String key, @Nullable String value) {
        if (key == null || value == null) {
            return;
        }
        if (label == null) {
            label = new HashMap<>();
        }
        label.put(key, value);
    }
}

package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class NamespacePrefixEntry {

    private String prefix;
    private String namespace;

    public NamespacePrefixEntry(String prefix, String namespace) {
        this.prefix = prefix;
        this.namespace = namespace;
    }
}

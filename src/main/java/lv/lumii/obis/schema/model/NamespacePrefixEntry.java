package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class NamespacePrefixEntry {

    private String prefix;
    private String namespace;

    public NamespacePrefixEntry() {}

    public NamespacePrefixEntry(String prefix, String namespace) {
        this.prefix = prefix;
        this.namespace = namespace;
    }
}

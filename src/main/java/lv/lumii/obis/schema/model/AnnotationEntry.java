package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class AnnotationEntry {

    private String key;
    private String value;

    public AnnotationEntry() {}

    public AnnotationEntry(String key, String value) {
        this.key = key;
        this.value = value;
    }
}

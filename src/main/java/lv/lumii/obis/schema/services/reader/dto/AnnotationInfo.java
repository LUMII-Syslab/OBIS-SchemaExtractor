package lv.lumii.obis.schema.services.reader.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AnnotationInfo {

    private String key;
    private String value;

    public AnnotationInfo(String key, String value) {
        this.key = key;
        this.value = value;
    }
}

package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Label {

    private String property;
    private String value;
    private String language;

    public Label(String property, String value) {
        this.property = property;
        this.value = value;
    }

    public Label(String property, String value, String language) {
        this.property = property;
        this.value = value;
        this.language = language;
    }
}

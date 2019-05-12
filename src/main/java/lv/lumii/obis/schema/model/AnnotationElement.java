package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

public abstract class AnnotationElement {

    @JsonIgnore
    private List<AnnotationEntry> annotations;

    public List<AnnotationEntry> getAnnotations() {
        if(annotations == null){
            annotations = new ArrayList<>();
        }
        return annotations;
    }
}

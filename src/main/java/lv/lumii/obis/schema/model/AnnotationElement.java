package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;
import java.util.Map;

public abstract class AnnotationElement {

    @JsonIgnore
    private Map<String, String> annotations;

    public void addAnnotation(String name, String value){
        getAnnotations().put(name, value);
    }

    public Map<String, String> getAnnotations() {
        if(annotations == null){
            annotations = new HashMap<>();
        }
        return annotations;
    }
}

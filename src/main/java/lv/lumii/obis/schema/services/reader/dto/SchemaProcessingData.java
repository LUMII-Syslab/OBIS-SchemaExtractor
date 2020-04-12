package lv.lumii.obis.schema.services.reader.dto;

import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.model.SchemaClass;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class SchemaProcessingData {

    private Map<String, SchemaClass> classesMap;
    private Map<String, List<SchemaCardinalityInfo>> cardinalityMap;

    @Nonnull
    public Map<String, SchemaClass> getClassesMap() {
        if (classesMap == null) {
            classesMap = new HashMap<>();
        }
        return classesMap;
    }

    @Nonnull
    public Map<String, List<SchemaCardinalityInfo>> getCardinalityMap() {
        if (cardinalityMap == null) {
            cardinalityMap = new HashMap<>();
        }
        return cardinalityMap;
    }
}

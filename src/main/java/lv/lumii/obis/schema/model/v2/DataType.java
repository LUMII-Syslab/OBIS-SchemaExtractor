package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DataType {

    private String dataType;
    private Long tripleCount;

    public DataType(String dataType, Long tripleCount) {
        this.dataType = dataType;
        this.tripleCount = tripleCount;
    }
}

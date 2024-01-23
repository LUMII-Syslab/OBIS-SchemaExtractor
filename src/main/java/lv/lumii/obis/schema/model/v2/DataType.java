package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DataType {

    private String dataType;
    private Long tripleCount;
    private Long tripleCountBase;

    public DataType(String dataType, Long tripleCount, Long tripleCountBase) {
        this.dataType = dataType;
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
    }
}

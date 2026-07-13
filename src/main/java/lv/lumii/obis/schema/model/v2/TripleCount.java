package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TripleCount {

    private Long tripleCount;
    private Long tripleCountBase;

    public TripleCount(Long tripleCount, Long tripleCountBase) {
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
    }

}

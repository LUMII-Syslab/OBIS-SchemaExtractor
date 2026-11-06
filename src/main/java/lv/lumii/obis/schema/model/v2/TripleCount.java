package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TripleCount {

    private Long tripleCount;
    private Long tripleCountBase;

    // Track requested recount work only for discovered pairs without exposing execution state in schema JSON.
    @JsonIgnore
    private boolean countNeedsRefinement;

    public TripleCount(Long tripleCount, Long tripleCountBase) {
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
    }

}

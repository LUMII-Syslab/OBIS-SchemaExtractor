package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
public class QueryResponse {

    private boolean hasErrors;

    private List<QueryResult> results;

    public boolean hasErrors() {
        return hasErrors;
    }

    public List<QueryResult> getResults() {
        if(results == null) {
            results = new ArrayList<>();
        }
        return results;
    }
}

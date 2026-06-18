package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
public class QueryResponse {

    private boolean hasErrors;
    private QueryResponseError queryResponseError;

    private long executionTime;

    private List<QueryResult> results;

    public boolean hasErrors() {
        return hasErrors;
    }

    public long getExecutionTime() {
        return executionTime;
    }

    public List<QueryResult> getResults() {
        if (results == null) {
            results = new ArrayList<>();
        }
        return results;
    }

    public QueryResponseError getQueryResponseError() {
        return queryResponseError;
    }
}

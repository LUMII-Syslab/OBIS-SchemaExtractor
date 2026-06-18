package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class QueryResponseError {

    public static final int NOT_ACCEPTABLE_CODE = 406;
    public static final String NOT_ACCEPTABLE_MSG = "Not Acceptable";

    private int errorStatusCode;
    private String responseMessage;

    public QueryResponseError(int errorStatusCode, String responseMessage) {
        this.errorStatusCode = errorStatusCode;
        this.responseMessage = responseMessage;
    }
}

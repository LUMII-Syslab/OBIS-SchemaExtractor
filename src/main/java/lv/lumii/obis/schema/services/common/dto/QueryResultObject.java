package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class QueryResultObject {

    private String value;
    private String fullName;
    private String localName;
    private String namespace;
    private String dataType;

}

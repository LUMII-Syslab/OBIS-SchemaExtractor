package lv.lumii.obis.schema.services.reader.dto;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class OWLOntologyReaderRequest {

    @ApiParam(access = "2", value = "List of excluded namespaces", allowEmptyValue = true)
    private List<String> excludedNamespaces;

}

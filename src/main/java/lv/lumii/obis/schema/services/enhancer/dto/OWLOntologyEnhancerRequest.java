package lv.lumii.obis.schema.services.enhancer.dto;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class OWLOntologyEnhancerRequest {

    @ApiParam(access = "2", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", allowEmptyValue = true)
    private String endpointUrl;

    @ApiParam(access = "3", value = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(hidden = true)
    private String correlationId;

}

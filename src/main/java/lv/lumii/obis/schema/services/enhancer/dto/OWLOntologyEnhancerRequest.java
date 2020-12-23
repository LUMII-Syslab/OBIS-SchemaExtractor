package lv.lumii.obis.schema.services.enhancer.dto;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class OWLOntologyEnhancerRequest {

    @ApiParam(access = "3", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", allowEmptyValue = true)
    private String endpointUrl;

    @ApiParam(access = "4", value = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "5", value = "Threshold to define abstract property (any property with instance count < threshold will be considered as abstract)", defaultValue = "10")
    private Integer abstractPropertyThreshold;

    @ApiParam(access = "6", value = "Threshold to define when missing property will be added to the schema (property missing in the ontology and having instance count > threshold)", defaultValue = "1000")
    private Integer propertyInstanceCountThreshold;

    @ApiParam(access = "7", value = "Flag whether min and max cardinalities are calculated from the endpoint data", defaultValue = "false")
    private Boolean calculateCardinalities;

    @ApiParam(hidden = true)
    private String correlationId;

    @ApiParam(hidden = true)
    private List<String> excludedNamespaces;

}

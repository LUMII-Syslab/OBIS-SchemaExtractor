package lv.lumii.obis.schema.services.enhancer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class OWLOntologyEnhancerRequest {

    @Schema(description = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql")
    private String endpointUrl;

    @Schema(description = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint")
    private String graphName;

    @Schema(description = "Threshold to define abstract property (any property with instance count < threshold will be considered as abstract)", defaultValue = "10")
    private Integer abstractPropertyThreshold;

    @Schema(description = "Threshold to define when missing property will be added to the schema (property missing in the ontology and having instance count > threshold)", defaultValue = "1000")
    private Integer propertyInstanceCountThreshold;

    @Schema(description = "Flag whether min and max cardinalities are calculated from the endpoint data", defaultValue = "false")
    private Boolean calculateCardinalities;

    @Schema(hidden = true)
    private String correlationId;

    @Schema(hidden = true)
    private List<String> excludedNamespaces;

}

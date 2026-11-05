package lv.lumii.obis.schema.services.enhancer.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class OWLOntologyEnhancerRequest {

    @Parameter(description = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql")
    private String endpointUrl;

    @Parameter(description = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint")
    private String graphName;

    @Parameter(description = "Threshold to define abstract property (any property with instance count < threshold will be considered as abstract)", schema = @Schema(implementation = Integer.class, defaultValue = "10"))
    private Integer abstractPropertyThreshold;

    @Parameter(description = "Threshold to define when missing property will be added to the schema (property missing in the ontology and having instance count > threshold)", schema = @Schema(implementation = Integer.class, defaultValue = "1000"))
    private Integer propertyInstanceCountThreshold;

    @Parameter(description = "Flag whether min and max cardinalities are calculated from the endpoint data", schema = @Schema(implementation = Boolean.class, defaultValue = "false"))
    private Boolean calculateCardinalities;

    @Parameter(hidden = true)
    private String correlationId;

    @Parameter(hidden = true)
    private List<String> excludedNamespaces;

}

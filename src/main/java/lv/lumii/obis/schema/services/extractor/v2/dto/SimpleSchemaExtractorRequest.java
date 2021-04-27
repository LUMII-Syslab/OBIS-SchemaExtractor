package lv.lumii.obis.schema.services.extractor.v2.dto;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequest;

@Setter @Getter
public class SimpleSchemaExtractorRequest {

    @ApiParam(access = "1", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "2", value = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "3", value = "Extraction Complexity: excludeDataTypesAndCardinalities, excludeCardinalities, full", defaultValue = "full", required = true)
    private SchemaExtractorRequest.ExtractionMode mode;

    @ApiParam(access = "4", value = "Enable SPARQL Query Logging to the file", defaultValue = "false", required = true)
    private Boolean enableLogging;

    @ApiParam(hidden = true)
    private String correlationId;

    public SchemaExtractorRequest.ExtractionMode getMode() {
        if(mode == null){
            mode = SchemaExtractorRequest.ExtractionMode.full;
        }
        return mode;
    }

    public Boolean getEnableLogging() {
        if(enableLogging == null){
            enableLogging = Boolean.FALSE;
        }
        return enableLogging;
    }
}

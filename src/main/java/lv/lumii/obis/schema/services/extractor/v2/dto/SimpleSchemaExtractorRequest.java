package lv.lumii.obis.schema.services.extractor.v2.dto;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SimpleSchemaExtractorRequest {

    @ApiParam(access = "1", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "2", value = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "3", value = "Calculate subclass relations", defaultValue = "true", required = true)
    private Boolean calculateSubClassRelations;

    @ApiParam(access = "4", value = "Calculate data types for attributes", defaultValue = "true", required = true)
    private Boolean calculateDataTypes;

    @ApiParam(access = "5", value = "Calculate min and max cardinalities for all properties", defaultValue = "true", required = true)
    private Boolean calculateCardinalities;

    @ApiParam(access = "6", value = "Enable SPARQL Query Logging to the file", defaultValue = "false", required = true)
    private Boolean enableLogging;

    @ApiParam(hidden = true)
    private String correlationId;

    public Boolean getCalculateSubClassRelations() {
        if(calculateSubClassRelations == null){
            calculateSubClassRelations = Boolean.FALSE;
        }
        return calculateSubClassRelations;
    }

    public Boolean getCalculateDataTypes() {
        if(calculateDataTypes == null){
            calculateDataTypes = Boolean.FALSE;
        }
        return calculateDataTypes;
    }

    public Boolean getCalculateCardinalities() {
        if(calculateCardinalities == null){
            calculateCardinalities = Boolean.FALSE;
        }
        return calculateCardinalities;
    }

    public Boolean getEnableLogging() {
        if(enableLogging == null){
            enableLogging = Boolean.FALSE;
        }
        return enableLogging;
    }
}

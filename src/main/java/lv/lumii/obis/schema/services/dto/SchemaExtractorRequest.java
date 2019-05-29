package lv.lumii.obis.schema.services.dto;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaExtractorRequest {

    public enum ExtractionMode {simple, data, full};

    @ApiParam(access = "1", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "2", value = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "3", value = "Extraction Complexity: [simple] - exclude datatype and cardinality calculations, [data] - exclude cardinality calculations, [full] - no exclusions", defaultValue = "full", required = true)
    private ExtractionMode mode;

    @ApiParam(access = "4", value = "Enable SPARQL Query Logging to the file", defaultValue = "false", required = true)
    private Boolean enableLogging;

    @ApiParam(access = "5", value = "Exclude Virtuoso System Classes", defaultValue = "true", required = true)
    private Boolean excludeSystemClasses;

    @ApiParam(access = "6", value = "Exclude Meta Domain Classes (w3 namespaces for owl/rdf-schema/22-rdf-syntax-ns)", defaultValue = "false", required = true)
    private Boolean excludeMetaDomainClasses;

    @ApiParam(hidden = true)
    private String correlationId;


    public ExtractionMode getMode() {
        if(mode == null){
            mode = ExtractionMode.full;
        }
        return mode;
    }

    public Boolean getEnableLogging() {
        if(enableLogging == null){
            enableLogging = Boolean.FALSE;
        }
        return enableLogging;
    }

    public Boolean getExcludeSystemClasses() {
        if(excludeSystemClasses == null){
            excludeSystemClasses = Boolean.TRUE;
        }
        return excludeSystemClasses;
    }

    public Boolean getExcludeMetaDomainClasses() {
        if(excludeMetaDomainClasses == null){
            excludeMetaDomainClasses = Boolean.FALSE;
        }
        return excludeMetaDomainClasses;
    }
}

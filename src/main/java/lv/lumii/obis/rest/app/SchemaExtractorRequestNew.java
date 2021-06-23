package lv.lumii.obis.rest.app;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class SchemaExtractorRequestNew {

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

    @ApiParam(access = "6", value = "List of excluded namespaces", allowEmptyValue = true)
    private List<String> excludedNamespaces;

    @ApiParam(access = "7", value = "Enable SPARQL Query Logging to the file", defaultValue = "true", required = true)
    private Boolean enableLogging;

    public Boolean getCalculateSubClassRelations() {
        if (calculateSubClassRelations == null) {
            calculateSubClassRelations = Boolean.FALSE;
        }
        return calculateSubClassRelations;
    }

    public Boolean getCalculateDataTypes() {
        if (calculateDataTypes == null) {
            calculateDataTypes = Boolean.FALSE;
        }
        return calculateDataTypes;
    }

    public Boolean getCalculateCardinalities() {
        if (calculateCardinalities == null) {
            calculateCardinalities = Boolean.FALSE;
        }
        return calculateCardinalities;
    }

    public Boolean getEnableLogging() {
        if (enableLogging == null) {
            enableLogging = Boolean.FALSE;
        }
        return enableLogging;
    }

    @Nonnull
    public List<String> getExcludedNamespaces() {
        if (excludedNamespaces == null) {
            excludedNamespaces = new ArrayList<>();
        }
        return excludedNamespaces;
    }
}

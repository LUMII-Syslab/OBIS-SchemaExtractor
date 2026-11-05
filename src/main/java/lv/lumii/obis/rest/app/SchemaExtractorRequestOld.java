package lv.lumii.obis.rest.app;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Deprecated
public class SchemaExtractorRequestOld {

    public enum ExtractionMode {excludeDataTypesAndCardinalities, excludeCardinalities, full}
    public enum ExtractionVersion {manySmallQueries, manySmallQueriesWithDirectProperties, fewComplexQueries}

    @Parameter(description = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @Parameter(description = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint")
    private String graphName;

    @Parameter(description = "Extraction Version: a lot of small SPARQL queries or just a few but complex queries", required = true, schema = @Schema(implementation = ExtractionVersion.class, defaultValue = "manySmallQueriesWithDirectProperties"))
    private ExtractionVersion version;

    @Parameter(description = "Extraction Complexity: excludeDataTypesAndCardinalities, excludeCardinalities, full", required = true, schema = @Schema(implementation = ExtractionMode.class, defaultValue = "full"))
    private ExtractionMode mode;

    @Parameter(description = "Enable SPARQL Query Logging to the file", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "false"))
    private Boolean enableLogging;

    @Parameter(description = "Exclude Virtuoso System Classes", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean excludeSystemClasses;

    @Parameter(description = "Exclude Meta Domain Classes (w3 namespaces for owl/rdf-schema/22-rdf-syntax-ns)", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "false"))
    private Boolean excludeMetaDomainClasses;

    @Parameter(description = "Exclude Properties without defined domain and/or range classes (not applicable for [fewComplexQueries] extraction version)", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean excludePropertiesWithoutClasses;

    public ExtractionVersion getVersion() {
        if (version == null) {
            version = ExtractionVersion.manySmallQueries;
        }
        return version;
    }

    public ExtractionMode getMode() {
        if (mode == null) {
            mode = ExtractionMode.full;
        }
        return mode;
    }

    public Boolean getEnableLogging() {
        if (enableLogging == null) {
            enableLogging = Boolean.FALSE;
        }
        return enableLogging;
    }

    public Boolean getExcludeSystemClasses() {
        if (excludeSystemClasses == null) {
            excludeSystemClasses = Boolean.TRUE;
        }
        return excludeSystemClasses;
    }

    public Boolean getExcludeMetaDomainClasses() {
        if (excludeMetaDomainClasses == null) {
            excludeMetaDomainClasses = Boolean.FALSE;
        }
        return excludeMetaDomainClasses;
    }

    public Boolean getExcludePropertiesWithoutClasses() {
        if (excludePropertiesWithoutClasses == null) {
            excludePropertiesWithoutClasses = Boolean.TRUE;
        }
        return excludePropertiesWithoutClasses;
    }
}

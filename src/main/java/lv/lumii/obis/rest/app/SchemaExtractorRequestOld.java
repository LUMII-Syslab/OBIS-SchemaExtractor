package lv.lumii.obis.rest.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Deprecated
public class SchemaExtractorRequestOld {

    public enum ExtractionMode {excludeDataTypesAndCardinalities, excludeCardinalities, full}
    public enum ExtractionVersion {manySmallQueries, manySmallQueriesWithDirectProperties, fewComplexQueries}

    @Schema(description = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", requiredMode = Schema.RequiredMode.REQUIRED)
    private String endpointUrl;

    @Schema(description = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint")
    private String graphName;

    @Schema(description = "Extraction Version: a lot of small SPARQL queries or just a few but complex queries", defaultValue = "manySmallQueriesWithDirectProperties", requiredMode = Schema.RequiredMode.REQUIRED)
    private ExtractionVersion version;

    @Schema(description = "Extraction Complexity: excludeDataTypesAndCardinalities, excludeCardinalities, full", defaultValue = "full", requiredMode = Schema.RequiredMode.REQUIRED)
    private ExtractionMode mode;

    @Schema(description = "Enable SPARQL Query Logging to the file", defaultValue = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean enableLogging;

    @Schema(description = "Exclude Virtuoso System Classes", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean excludeSystemClasses;

    @Schema(description = "Exclude Meta Domain Classes (w3 namespaces for owl/rdf-schema/22-rdf-syntax-ns)", defaultValue = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean excludeMetaDomainClasses;

    @Schema(description = "Exclude Properties without defined domain and/or range classes (not applicable for [fewComplexQueries] extraction version)", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
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

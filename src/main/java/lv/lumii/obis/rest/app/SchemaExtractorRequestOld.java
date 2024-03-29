package lv.lumii.obis.rest.app;

import io.swagger.annotations.ApiParam;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Deprecated
public class SchemaExtractorRequestOld {

    public enum ExtractionMode {excludeDataTypesAndCardinalities, excludeCardinalities, full}
    public enum ExtractionVersion {manySmallQueries, manySmallQueriesWithDirectProperties, fewComplexQueries}

    @ApiParam(access = "1", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "2", value = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "3", value = "Extraction Version: a lot of small SPARQL queries or just a few but complex queries", defaultValue = "manySmallQueriesWithDirectProperties", required = true)
    private ExtractionVersion version;

    @ApiParam(access = "4", value = "Extraction Complexity: excludeDataTypesAndCardinalities, excludeCardinalities, full", defaultValue = "full", required = true)
    private ExtractionMode mode;

    @ApiParam(access = "5", value = "Enable SPARQL Query Logging to the file", defaultValue = "false", required = true)
    private Boolean enableLogging;

    @ApiParam(access = "6", value = "Exclude Virtuoso System Classes", defaultValue = "true", required = true)
    private Boolean excludeSystemClasses;

    @ApiParam(access = "7", value = "Exclude Meta Domain Classes (w3 namespaces for owl/rdf-schema/22-rdf-syntax-ns)", defaultValue = "false", required = true)
    private Boolean excludeMetaDomainClasses;

    @ApiParam(access = "8", value = "Exclude Properties without defined domain and/or range classes (not applicable for [fewComplexQueries] extraction version)", defaultValue = "true", required = true)
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

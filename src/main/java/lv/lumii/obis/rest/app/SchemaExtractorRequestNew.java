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

    public enum CalculateCardinalitiesMode {none, propertyLevelOnly, propertyLevelAndClassContext}

    @ApiParam(access = "1", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "2", value = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "3", value = "Calculate subclass relations", defaultValue = "true", required = true)
    private Boolean calculateSubClassRelations;

    @ApiParam(access = "4", value = "Calculate property-property relations", defaultValue = "true", required = true)
    private Boolean calculatePropertyPropertyRelations;

    @ApiParam(access = "5", value = "Calculate domain and range pairs", defaultValue = "true", required = true)
    private Boolean calculateDomainAndRangePairs;

    @ApiParam(access = "6", value = "Calculate closed domains and closed ranges", defaultValue = "true", required = true)
    private Boolean calculateClosedDomainsAndRanges;

    @ApiParam(access = "7", value = "Calculate principal domains and ranges", defaultValue = "true", required = true)
    private Boolean calculatePrincipalDomainsAndRanges;

    @ApiParam(access = "8", value = "Calculate data types for attributes", defaultValue = "true", required = true)
    private Boolean calculateDataTypes;

    @ApiParam(access = "9", value = "Calculate min and max cardinalities for all properties", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculateCardinalitiesMode calculateCardinalitiesMode;

    @ApiParam(access = "91", value = "Check instance namespace URIs", defaultValue = "false", required = false)
    private Boolean checkInstanceNamespaces;

    @ApiParam(access = "92", value = "Add label information", allowEmptyValue = true)
    private List<String> addedLabels;

    @ApiParam(access = "93", value = "Minimal Analyzed Class Size (set 1 if all classes should be analyzed)", defaultValue = "1", required = true)
    private Integer minimalAnalyzedClassSize;

    @ApiParam(access = "94", value = "List of classification properties, default property is http://www.w3.org/1999/02/22-rdf-syntax-ns#type", allowEmptyValue = true)
    private List<String> classificationProperties;

    @ApiParam(access = "95", value = "List of excluded namespaces", allowEmptyValue = true)
    private List<String> excludedNamespaces;

    @ApiParam(access = "96", value = "Enable SPARQL Query Logging to the file", defaultValue = "true", required = true)
    private Boolean enableLogging;

    public Boolean getCalculateSubClassRelations() {
        if (calculateSubClassRelations == null) {
            calculateSubClassRelations = Boolean.FALSE;
        }
        return calculateSubClassRelations;
    }

    public Boolean getCalculatePropertyPropertyRelations() {
        if (calculatePropertyPropertyRelations == null) {
            calculatePropertyPropertyRelations = Boolean.FALSE;
        }
        return calculatePropertyPropertyRelations;
    }

    public Boolean getCalculateDomainAndRangePairs() {
        if (calculateDomainAndRangePairs == null) {
            calculateDomainAndRangePairs = Boolean.FALSE;
        }
        return calculateDomainAndRangePairs;
    }

    public Boolean getCalculateClosedDomainsAndRanges() {
        if (calculateClosedDomainsAndRanges == null) {
            calculateClosedDomainsAndRanges = Boolean.FALSE;
        }
        return calculateClosedDomainsAndRanges;
    }

    public Boolean getCalculatePrincipalDomainsAndRanges() {
        if (calculatePrincipalDomainsAndRanges == null) {
            calculatePrincipalDomainsAndRanges = Boolean.FALSE;
        }
        return calculatePrincipalDomainsAndRanges;
    }

    public Boolean getCalculateDataTypes() {
        if (calculateDataTypes == null) {
            calculateDataTypes = Boolean.FALSE;
        }
        return calculateDataTypes;
    }

    public CalculateCardinalitiesMode getCalculateCardinalitiesMode() {
        if (calculateCardinalitiesMode == null) {
            calculateCardinalitiesMode = CalculateCardinalitiesMode.none;
        }
        return calculateCardinalitiesMode;
    }

    public Boolean getCheckInstanceNamespaces() {
        if (checkInstanceNamespaces == null) {
            checkInstanceNamespaces = Boolean.FALSE;
        }
        return checkInstanceNamespaces;
    }

    public Boolean getEnableLogging() {
        if (enableLogging == null) {
            enableLogging = Boolean.FALSE;
        }
        return enableLogging;
    }

    @Nonnull
    public List<String> getClassificationProperties() {
        if (classificationProperties == null) {
            classificationProperties = new ArrayList<>();
        }
        return classificationProperties;
    }

    @Nonnull
    public List<String> getExcludedNamespaces() {
        if (excludedNamespaces == null) {
            excludedNamespaces = new ArrayList<>();
        }
        return excludedNamespaces;
    }

    @Nonnull
    public List<String> getAddedLabels() {
        if (addedLabels == null) {
            addedLabels = new ArrayList<>();
        }
        return addedLabels;
    }
}

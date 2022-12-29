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

    @ApiParam(access = "6", value = "Calculate data types for attributes", defaultValue = "true", required = true)
    private Boolean calculateDataTypes;

    @ApiParam(access = "7", value = "Calculate min and max cardinalities for all properties", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculateCardinalitiesMode calculateCardinalitiesMode;

    @ApiParam(access = "8", value = "Add label information", allowEmptyValue = true)
    private List<String> addedLabels;

    @ApiParam(access = "9", value = "Minimal Analyzed Class Size (set 1 if all classes should be analyzed)", defaultValue = "1", required = true)
    private Integer minimalAnalyzedClassSize;

    @ApiParam(access = "91", value = "List of classification properties, default property is rdf:type", allowEmptyValue = true)
    private List<String> classificationProperties;

    @ApiParam(access = "92", value = "List of excluded namespaces", allowEmptyValue = true)
    private List<String> excludedNamespaces;

    @ApiParam(access = "93", value = "Enable SPARQL Query Logging to the file", defaultValue = "true", required = true)
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

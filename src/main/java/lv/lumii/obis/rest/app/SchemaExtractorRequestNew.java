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

    public enum ShowIntersectionClassesMode {yes, no, auto}

    public enum DistinctQueryMode {yes, no, auto}

    @ApiParam(access = "1", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "2", value = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "3", value = "Calculate subclass relations", defaultValue = "true", required = true)
    private Boolean calculateSubClassRelations;

    @ApiParam(access = "4", value = "Calculate property-property relations", defaultValue = "true", required = true)
    private Boolean calculatePropertyPropertyRelations;

    @ApiParam(access = "5", value = "Calculate source and target pairs", defaultValue = "true", required = true)
    private Boolean calculateSourceAndTargetPairs;

    @ApiParam(access = "6", value = "Calculate domains and ranges", defaultValue = "true", required = true)
    private Boolean calculateDomainsAndRanges;

    @ApiParam(access = "7", value = "Calculate importance indexes", defaultValue = "true", required = true)
    private Boolean calculateImportanceIndexes;

    @ApiParam(access = "8", value = "Calculate closed class sets", defaultValue = "false", required = true)
    private Boolean calculateClosedClassSets;

    @ApiParam(access = "9", value = "Calculate min and max cardinalities for all properties", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculateCardinalitiesMode calculateCardinalitiesMode;

    @ApiParam(access = "10", value = "Calculate data types for attributes", defaultValue = "true", required = true)
    private Boolean calculateDataTypes;

    @ApiParam(access = "11", value = "Limit of instances to use in data type calculation (no value or 0 means all data will be used)", required = false)
    private Long sampleLimitForDataTypeCalculation;

    @ApiParam(access = "12", value = "Limit of instances to use in property-class relation calculation (no value or 0 means all data will be used)", required = false)
    private Long sampleLimitForPropertyClassRelationCalculation;

    @ApiParam(access = "13", value = "Limit of instances to use in property-property relation calculation (no value or 0 means all data will be used)", required = false)
    private Long sampleLimitForPropertyToPropertyRelationCalculation;

    @ApiParam(access = "14", value = "Check instance namespace URIs", defaultValue = "false", required = false)
    private Boolean checkInstanceNamespaces;

    @ApiParam(access = "15", value = "Add label information", allowEmptyValue = true)
    private List<String> addedLabels;

    @ApiParam(access = "16", value = "Minimal Analyzed Class Size (set 1 if all classes should be analyzed)", defaultValue = "1", required = true)
    private Integer minimalAnalyzedClassSize;

    @ApiParam(access = "17", value = "Add intersection classes to the result schema (yes, no, auto - add only if intersection classes count <= 200 )", defaultValue = "auto", required = true)
    private ShowIntersectionClassesMode addIntersectionClasses;

    @ApiParam(access = "18", value = "List of properties defining the class structure; the classifier values (objects of these property triples) can be superclasses of other classes/classifiers and are checked for being sources and targets for other properties (default property is rdf:type)", allowEmptyValue = true)
    private List<String> principalClassificationProperties;

    @ApiParam(access = "19", value = "List of properties defining the classifiers whose values can define subsets of principal property classifiers (classes) and that are checked for being sources and targets for other properties", allowEmptyValue = true)
    private List<String> classificationPropertiesWithConnectionsOnly;

    @ApiParam(access = "20", value = "List of properties defining simple classifiers (their values can define subsets of principal classifiers (classes)), not checked for being sources and targets for other properties", allowEmptyValue = true)
    private List<String> simpleClassificationProperties;

    @ApiParam(access = "21", value = "Add DISTINCT in queries (yes, no, auto - add distinct only if total instances count < 10M)", defaultValue = "yes", required = true)
    private DistinctQueryMode exactCountCalculations;

    @ApiParam(access = "22", value = "Total instance count limit for exact count calculations", defaultValue = "10000000", required = false)
    private Long maxInstanceLimitForExactCount;

    @ApiParam(access = "23", value = "List of excluded namespaces", allowEmptyValue = true)
    private List<String> excludedNamespaces;

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

    public Boolean getCalculateSourceAndTargetPairs() {
        if (calculateSourceAndTargetPairs == null) {
            calculateSourceAndTargetPairs = Boolean.FALSE;
        }
        return calculateSourceAndTargetPairs;
    }

    public Boolean getCalculateDomainsAndRanges() {
        if (calculateDomainsAndRanges == null) {
            calculateDomainsAndRanges = Boolean.FALSE;
        }
        return calculateDomainsAndRanges;
    }

    public Boolean getCalculateClosedClassSets() {
        if (calculateClosedClassSets == null) {
            calculateClosedClassSets = Boolean.FALSE;
        }
        return calculateClosedClassSets;
    }

    public Boolean getCalculateImportanceIndexes() {
        if (calculateImportanceIndexes == null) {
            calculateImportanceIndexes = Boolean.FALSE;
        }
        return calculateImportanceIndexes;
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

    @Nonnull
    public List<String> getPrincipalClassificationProperties() {
        if (principalClassificationProperties == null) {
            principalClassificationProperties = new ArrayList<>();
        }
        return principalClassificationProperties;
    }

    @Nonnull
    public List<String> getClassificationPropertiesWithConnectionsOnly() {
        if (classificationPropertiesWithConnectionsOnly == null) {
            classificationPropertiesWithConnectionsOnly = new ArrayList<>();
        }
        return classificationPropertiesWithConnectionsOnly;
    }

    @Nonnull
    public List<String> getSimpleClassificationProperties() {
        if (simpleClassificationProperties == null) {
            simpleClassificationProperties = new ArrayList<>();
        }
        return simpleClassificationProperties;
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

    @Nonnull
    public ShowIntersectionClassesMode getAddIntersectionClasses() {
        if (addIntersectionClasses == null) {
            addIntersectionClasses = ShowIntersectionClassesMode.auto;
        }
        return addIntersectionClasses;
    }

    @Nonnull
    public DistinctQueryMode getExactCountCalculations() {
        if (exactCountCalculations == null) {
            exactCountCalculations = DistinctQueryMode.yes;
        }
        return exactCountCalculations;
    }
}

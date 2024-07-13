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

    public enum CalculatePropertyFeatureMode {none, propertyLevelOnly, propertyLevelAndClassContext}

    public enum ShowIntersectionClassesMode {yes, no, auto}

    public enum DistinctQueryMode {yes, no, auto}

    public enum ImportantIndexesMode {detailed, base, no}

    public enum InstanceNamespacesMode {no, detailed, overview}

    @ApiParam(access = "10", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "20", value = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "30", value = "Calculate subclass relations", defaultValue = "true", required = true)
    private Boolean calculateSubClassRelations;

    @ApiParam(access = "31", value = "Include check for multiple inheritance", defaultValue = "true", required = true)
    private Boolean calculateMultipleInheritanceSuperclasses;

    @ApiParam(access = "40", value = "Calculate property-property relations", defaultValue = "true", required = true)
    private Boolean calculatePropertyPropertyRelations;

    @ApiParam(access = "50", value = "Calculate source and target pairs", defaultValue = "true", required = true)
    private Boolean calculateSourceAndTargetPairs;

    @ApiParam(access = "60", value = "Calculate domains and ranges", defaultValue = "true", required = true)
    private Boolean calculateDomainsAndRanges;

    @ApiParam(access = "70", value = "Calculate importance indexes", defaultValue = "base", required = true)
    private ImportantIndexesMode calculateImportanceIndexes;

    @ApiParam(access = "80", value = "Calculate closed class sets", defaultValue = "false", required = true)
    private Boolean calculateClosedClassSets;

    @ApiParam(access = "90", value = "Calculate min and max cardinalities for all properties", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculatePropertyFeatureMode calculateCardinalitiesMode;

    @ApiParam(access = "100", value = "Calculate data types for attributes", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculatePropertyFeatureMode calculateDataTypes;

    @ApiParam(access = "110", value = "Limit of instances to use in data type calculation (no value or 0 means all data will be used)", required = false)
    private Long sampleLimitForDataTypeCalculation;

    @ApiParam(access = "140", value = "Calculate instance namespace URIs", defaultValue = "no", required = true)
    private InstanceNamespacesMode calculateInstanceNamespaces;

    @ApiParam(access = "141", value = "Limit of instances to use in namespace calculation (no value or 0 means all data will be used)", defaultValue = "1000", required = false)
    private Long sampleLimitForInstanceNamespacesCalculation;

    @ApiParam(access = "150", value = "Properties for class and property labels. rdfs:label and skos:prefLabel assumed by default. For labels in specific languages use @{en,de} notation after the label property iri or short form. To exclude a default labeling property, use property@{-} notation.", allowEmptyValue = true)
    private List<String> addedLabels;

    @ApiParam(access = "160", value = "Minimal Analyzed Class Size (set 1 if all classes should be analyzed)", defaultValue = "1", required = true)
    private Integer minimalAnalyzedClassSize;

    @ApiParam(access = "170", value = "Add intersection classes to the result schema (yes, no, auto - add only if intersection classes count <= 200 )", defaultValue = "auto", required = true)
    private ShowIntersectionClassesMode addIntersectionClasses;

    @ApiParam(access = "180", value = "List of properties defining the class structure; the classifier values (objects of these property triples) can be superclasses of other classes/classifiers and are checked for being sources and targets for other properties (default property is rdf:type)", allowEmptyValue = true)
    private List<String> principalClassificationProperties;

    @ApiParam(access = "190", value = "List of properties defining the classifiers whose values can define subsets of principal property classifiers (classes) and that are checked for being sources and targets for other properties", allowEmptyValue = true)
    private List<String> classificationPropertiesWithConnectionsOnly;

    @ApiParam(access = "200", value = "List of properties defining simple classifiers (their values can define subsets of principal classifiers (classes)), not checked for being sources and targets for other properties", allowEmptyValue = true)
    private List<String> simpleClassificationProperties;

    @ApiParam(access = "210", value = "Add DISTINCT in queries (yes, no, auto - add distinct only if total instances count < 10M)", defaultValue = "yes", required = true)
    private DistinctQueryMode exactCountCalculations;

    @ApiParam(access = "220", value = "Total instance count limit for exact count calculations", defaultValue = "10000000", required = false)
    private Long maxInstanceLimitForExactCount;

    @ApiParam(access = "230", value = "List of excluded namespaces", allowEmptyValue = true)
    private List<String> excludedNamespaces;

    public Boolean getCalculateSubClassRelations() {
        if (calculateSubClassRelations == null) {
            calculateSubClassRelations = Boolean.TRUE;
        }
        return calculateSubClassRelations;
    }

    public Boolean getCalculateMultipleInheritanceSuperclasses() {
        if (calculateMultipleInheritanceSuperclasses == null) {
            calculateMultipleInheritanceSuperclasses = Boolean.TRUE;
        }
        return calculateMultipleInheritanceSuperclasses;
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

    public ImportantIndexesMode getCalculateImportanceIndexes() {
        if (calculateImportanceIndexes == null) {
            calculateImportanceIndexes = ImportantIndexesMode.base;
        }
        return calculateImportanceIndexes;
    }

    public CalculatePropertyFeatureMode getCalculateDataTypes() {
        if (calculateDataTypes == null) {
            calculateDataTypes = CalculatePropertyFeatureMode.propertyLevelAndClassContext;
        }
        return calculateDataTypes;
    }

    public CalculatePropertyFeatureMode getCalculateCardinalitiesMode() {
        if (calculateCardinalitiesMode == null) {
            calculateCardinalitiesMode = CalculatePropertyFeatureMode.propertyLevelAndClassContext;
        }
        return calculateCardinalitiesMode;
    }

    public InstanceNamespacesMode getCalculateInstanceNamespaces() {
        if (calculateInstanceNamespaces == null) {
            calculateInstanceNamespaces = InstanceNamespacesMode.no;
        }
        return calculateInstanceNamespaces;
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

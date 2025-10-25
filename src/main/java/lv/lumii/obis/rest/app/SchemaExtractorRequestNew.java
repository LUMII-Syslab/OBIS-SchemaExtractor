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

    public enum DistinctQueryMode {yes, no}

    public enum ImportantIndexesMode {basic, unionBased, classCoverage, no}

    public enum InstanceNamespacesMode {no, detailed, overview}

    @ApiParam(access = "10", value = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @ApiParam(access = "20", value = "Named Graph (optional, recommended). If no graph name provided, the search will involve all graphs from the endpoint", allowEmptyValue = true)
    private String graphName;

    @ApiParam(access = "30", value = "Calculate subclass relation (strongly recommended)", defaultValue = "true", required = true)
    private Boolean calculateSubClassRelations;

    @ApiParam(access = "31", value = "Include multiple inheritance check (strongly recommended, can be skipped for performance reasons for very large endpoints)", defaultValue = "true", required = true)
    private Boolean calculateMultipleInheritanceSuperclasses;

    @ApiParam(access = "32", value = "Minimal Analyzed Class Size (if 1, all classes should be analyzed). Values above 1 (e.g., 10 or 100) may be essential for endpoints with above 500 classes", defaultValue = "1", required = true)
    private Integer minimalAnalyzedClassSize;

    @ApiParam(access = "33", value = "At least 1 class is required for the schema extraction, if no classes found - the extractor work is aborted", defaultValue = "true", required = true)
    private Boolean requireClasses;

    @ApiParam(access = "40", value = "Calculate property-property adjacency (following properties, same subject, same object). Useful for auto-completion. Currently not used in schema visualization. Can be time consuming for larger queries", defaultValue = "true", required = true)
    private Boolean calculatePropertyPropertyRelations;

    @ApiParam(access = "50", value = "Calculate pairs of source and target classes for properties (creates finer-grained schemas; used as a source of statistics in visual schema diagrams)", defaultValue = "true", required = true)
    private Boolean calculateSourceAndTargetPairs;

    @ApiParam(access = "60", value = "Calculate domain and range classes for properties", defaultValue = "true", required = true)
    private Boolean calculateDomainsAndRanges;

    @ApiParam(access = "70", value = "Calculate ascription points (principal classes) for properties (strongly recommended, if schema diagrams are envisaged). Use 'class coverage', if all class-to-property connections are to be marked for the class itself, or some its superclass or subclass (can make a difference in the case of overlapping classes)", defaultValue = "basic", required = true)
    private ImportantIndexesMode calculateImportanceIndexes;

    @ApiParam(access = "80", value = "Check property source and target class set closure (essential for SHACL export (to be developed))", defaultValue = "false", required = true)
    private Boolean calculateClosedClassSets;

    @ApiParam(access = "90", value = "Calculate min and max cardinalities for properties", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculatePropertyFeatureMode calculateCardinalities;

    @ApiParam(access = "100", value = "Calculate data types for attributes", defaultValue = "propertyLevelAndClassContext", required = true)
    private CalculatePropertyFeatureMode calculateDataTypes;

    @ApiParam(access = "110", value = "Instance sample size for data type calculation", required = false)
    private Long sampleLimitForDataTypeCalculation;

    @ApiParam(access = "140", value = "Calculate instance namespace URIs", defaultValue = "no", required = true)
    private InstanceNamespacesMode calculateInstanceNamespaces;

    @ApiParam(access = "141", value = "Limit of instances to use in namespace calculation (no value or 0 means all data will be used)", defaultValue = "1000", required = false)
    private Long sampleLimitForInstanceNamespacesCalculation;

    @ApiParam(access = "150", value = "Properties for class and property labels. rdfs:label and skos:prefLabel assumed by default. For labels in specific languages use @{en,de} notation after the label property iri or short form. To exclude a default labeling property, use property@{-} notation.", allowEmptyValue = true)
    private List<String> addedLabels;

    @ApiParam(access = "170", value = "Add intersection classes to the result schema (yes, no, auto - add only if intersection classes count <= 200 )", defaultValue = "auto", required = true)
    private ShowIntersectionClassesMode addIntersectionClasses;

    @ApiParam(hidden = true, access = "180", value = "List of properties defining the class structure; the classifier values (objects of these property triples) can be superclasses of other classes/classifiers and are checked for being sources and targets for other properties (default property is rdf:type)", allowEmptyValue = true)
    private List<String> principalClassificationProperties;

    @ApiParam(hidden = true, access = "190", value = "List of properties defining the classifiers whose values can define subsets of principal property classifiers (classes) and that are checked for being sources and targets for other properties", allowEmptyValue = true)
    private List<String> classificationPropertiesWithConnectionsOnly;

    @ApiParam(hidden = true, access = "200", value = "List of properties defining simple classifiers (their values can define subsets of principal classifiers (classes)), not checked for being sources and targets for other properties", allowEmptyValue = true)
    private List<String> simpleClassificationProperties;

    @ApiParam(access = "210", value = "Add DISTINCT in queries (can be used for classes and properties of less than 10M size, if entity/triple duplications are observed otherwise in the schemas)", defaultValue = "yes", required = true)
    private DistinctQueryMode exactCountCalculations;

    @ApiParam(hidden = true, access = "220", value = "Total instance count limit for exact count calculations", defaultValue = "10000000", required = false)
    private Long maxInstanceLimitForExactCount;

    @ApiParam(access = "230", value = "List of excluded namespaces (e.g., http://www.openlinksw.com/schemas/virtrdf#)", allowEmptyValue = true)
    private List<String> excludedNamespaces;

    @ApiParam(access = "235", value = "Large Query Timeout (in seconds). Default value 600 seconds (set 0 to execute without timeout)", defaultValue = "600", required = false)
    private Long largeQueryTimeout;
    @ApiParam(access = "236", value = "Small Query Timeout (in seconds). Default value 60 seconds (set 0 to execute without timeout)", defaultValue = "60", required = false)
    private Long smallQueryTimeout;

    @ApiParam(hidden = true, access = "237", value ="Query Delay Timeout in case of failure (in seconds)", defaultValue = "0", required = false)
    private Long delayOnFailure;

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
            calculateImportanceIndexes = ImportantIndexesMode.basic;
        }
        return calculateImportanceIndexes;
    }

    public CalculatePropertyFeatureMode getCalculateDataTypes() {
        if (calculateDataTypes == null) {
            calculateDataTypes = CalculatePropertyFeatureMode.propertyLevelAndClassContext;
        }
        return calculateDataTypes;
    }

    public CalculatePropertyFeatureMode getCalculateCardinalities() {
        if (calculateCardinalities == null) {
            calculateCardinalities = CalculatePropertyFeatureMode.propertyLevelAndClassContext;
        }
        return calculateCardinalities;
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

    @Nonnull
    public Boolean getRequireClasses() {
        if (requireClasses == null) {
            requireClasses = Boolean.TRUE;
        }
        return requireClasses;
    }
}

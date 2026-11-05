package lv.lumii.obis.rest.app;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class SchemaExtractorRequestNew {

    public enum CalculatePropertyFeatureMode {none, propertyLevelOnly, propertyLevelAndClassContext}

    public enum ShowIntersectionClassesMode {yes, no}

    public enum DistinctQueryMode {yes, no}

    public enum ImportantIndexesMode {basic, unionBased, classCoverage, no}

    public enum InstanceNamespacesMode {no, detailed, overview}

    public enum DistinctSubjectsAndObjectsMode {no, propertyLevel, yes}

    public enum BlankNodeMode {no, targetsOnly, full}

    public enum PropertyRelationsCheckMode {none, limits, limitsPlus, limitsPlusSimple, detailsBase, details}

    public enum NoClassesLoggingOptions {yes, no, sourcesOnly}

    @Parameter(description = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", required = true)
    private String endpointUrl;

    @Parameter(description = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint")
    private String graphName;

    @Parameter(description = "Calculate subclass relation (strongly recommended)", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculateSubClassRelations;

    @Parameter(description = "Include multiple inheritance check, strongly recommended. If set to false, no more than a single direct super-class for a class shall be found. " +
            "Set false only for very large endpoints, if the processing of subclass relations is stuck in the extractor for a longer time.", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculateMultipleInheritanceSuperclasses;

    @Parameter(description = "Minimal analyzed class size. The classes below the specified size shall be included in the schema, " +
            "still they shall not be considered as possible super-classes of other classes and their links to properties shall not be analyzed individually. " +
            "If 1, all classes should be analyzed. Values above 1, e.g. 10 or 100, may be essential for endpoints with more than several thousand classes.", required = true, schema = @Schema(implementation = Integer.class, defaultValue = "1"))
    private Integer minimalAnalyzedClassSize;

    @Parameter(description = "At least 1 class is required for the schema extraction, if no classes found - the extractor work is aborted", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean requireClasses;

    @Parameter(hidden = true, description = "Validate the list of classes with simple and statistics queries ", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean validateClassesList;
    @Parameter(hidden = true, description = "Validate the list of properties with simple and statistics queries ", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean validatePropertiesList;

    @Parameter(description = "Calculate property-property adjacency: following properties, same subject, same object. " +
            "Important for visual and textual query auto-completion use case; can be used in schema visualization if property sources and targets are considered as potential nodes (work in progress). " +
            "Can be time-consuming for larger schemas, consider appropriate propertyPropertyLinkCheckBackupMode", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculatePropertyPropertyRelations;

    @Parameter(description = "Set backup validation queries to calculate property-property relations in the case of a failing property co-occurrence query for a given base property. " +
            "details, limitsPlus and limitsPlusSimple guarantee trying a single-line pattern of two property co-occurrence, if larger patterns fail. " +
            "details gives exact statistics for two property co-occurrence, whenever possible. " +
            "limitsPlus attempts at least a limit-based estimate, limitsPlusSimple can stay with yes/no answer for co-occurrence for performance reasons (with some count information imputed at later schema import steps). " +
            "none and limits are faster, but can result in partially incomplete structure information.", required = true, schema = @Schema(implementation = PropertyRelationsCheckMode.class, defaultValue = "details"))
    private PropertyRelationsCheckMode propertyPropertyLinkCheckBackupMode;

    @Parameter(description = "Calculate pairs of source and target classes for properties (creates finer-grained schemas; used as a source of statistics in visual schema diagrams)", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculateSourceAndTargetPairs;

    @Parameter(description = "Calculate domain and range classes for properties (in the strict, ontological sense)", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculateDomainsAndRanges;

    @Parameter(hidden = true, description = "Calculate distinct subjects and objects count for property and/or for property source and target classes", required = true, schema = @Schema(implementation = DistinctSubjectsAndObjectsMode.class, defaultValue = "propertyLevel"))
    private DistinctSubjectsAndObjectsMode includeDistinctSubjectsAndObjects;

    @Parameter(hidden = true, description = "Calculate blank node statistics", required = true, schema = @Schema(implementation = BlankNodeMode.class, defaultValue = "full"))
    private BlankNodeMode computeBlankNodeStatistics;

    @Parameter(description = "Calculate ascription points (principal classes) for properties (strongly recommended, if schema diagrams are envisaged). Use 'class coverage', if all class-to-property connections are to be marked for the class itself, or some its superclass or subclass (can make a difference in the case of overlapping classes)", required = true, schema = @Schema(implementation = ImportantIndexesMode.class, defaultValue = "basic"))
    private ImportantIndexesMode calculateImportanceIndexes;

    @Parameter(description = "Check, if the property source and target class set sets cover all non-literal property subjects and objects; " +
            "essential for future SHACL export, can be used in advanced schema diagrams.", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculateClosedClassSets;

    @Parameter(description = "Calculate minimum and maximum cardinalities for properties. May consider propertyLevelOnly for larger schemas.", required = true, schema = @Schema(implementation = CalculatePropertyFeatureMode.class, defaultValue = "propertyLevelAndClassContext"))
    private CalculatePropertyFeatureMode calculateCardinalities;

    @Parameter(description = "Calculate data types for attributes. May consider propertyLevelOnly for larger schemas.", required = true, schema = @Schema(implementation = CalculatePropertyFeatureMode.class, defaultValue = "propertyLevelAndClassContext"))
    private CalculatePropertyFeatureMode calculateDataTypes;

    @Parameter(description = "Instance sample size for datatype calculation. It is recommended to provide a constraint (e.g., 100K or 1M) for larger data sets " +
            "as the exact data set information can be less essential in the schemas and attempts to obtain exact data type usage statistics may take unreasonably long time.")
    private Long sampleLimitForDataTypeCalculation;

    @Parameter(hidden = true, description = "Calculate instance namespace URIs. Mode 'details' will retrieve namespaces for all classes and " +
            "for subjects and objects of all properties. Mode 'overview' will retrieve for limited data set (use parameter sampleLimitForInstanceNamespacesCalculation).", required = true, schema = @Schema(implementation = InstanceNamespacesMode.class, defaultValue = "no"))
    private InstanceNamespacesMode calculateInstanceNamespaces;

    @Parameter(hidden = true, description = "Limit of instances to use in namespace calculation (no value or 0 means all data will be used)", schema = @Schema(implementation = Long.class, defaultValue = "1000"))
    private Long sampleLimitForInstanceNamespacesCalculation;

    @Parameter(description = "Properties for class and property labels. rdfs:label and skos:prefLabel assumed by default. For labels in specific languages use @{en,de} notation after the label property iri or short form. To exclude a default labeling property, use property@{-} notation.",
            array = @ArraySchema(schema = @Schema(example = "rdfs:label@{en}")))
    private List<String> addedLabels;

    @Parameter(description = "Record intersection class information in the extracted schema", required = true, schema = @Schema(implementation = ShowIntersectionClassesMode.class, defaultValue = "yes"))
    private ShowIntersectionClassesMode addIntersectionClasses;

    @Parameter(hidden = true, description = "List of properties defining the class structure; the classifier values (objects of these property triples) can be superclasses of other classes/classifiers and are checked for being sources and targets for other properties (default property is rdf:type)")
    private List<String> principalClassificationProperties;

    @Parameter(hidden = true, description = "List of properties defining the classifiers whose values can define subsets of principal property classifiers (classes) and that are checked for being sources and targets for other properties")
    private List<String> classificationPropertiesWithConnectionsOnly;

    @Parameter(hidden = true, description = "List of properties defining simple classifiers (their values can define subsets of principal classifiers (classes)), not checked for being sources and targets for other properties")
    private List<String> simpleClassificationProperties;

    @Parameter(hidden = true, description = "Add DISTINCT in queries (can be used for classes and properties of less than 10M size, if entity/triple duplications are observed otherwise in the schemas)", required = true, schema = @Schema(implementation = DistinctQueryMode.class, defaultValue = "no"))
    private DistinctQueryMode exactCountCalculations;
    @Parameter(hidden = true, description = "Calculate distinct triples count for classes and properties)", required = true, schema = @Schema(implementation = Boolean.class, defaultValue = "true"))
    private Boolean calculateDistinctTriples;

    @Parameter(hidden = true, description = "Total instance count limit for exact count calculations", schema = @Schema(implementation = Long.class, defaultValue = "10000000"))
    private Long maxInstanceLimitForExactCount;

    @Parameter(hidden = true, description = "Cross check target classes on non-literal property object check failure", schema = @Schema(implementation = Boolean.class, defaultValue = "false"))
    private Boolean crossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure;

    @Parameter(description = "List of excluded namespaces (e.g., http://www.openlinksw.com/schemas/virtrdf#)",
            array = @ArraySchema(schema = @Schema(example = "http://www.openlinksw.com/schemas/virtrdf#")))
    private List<String> excludedNamespaces;

    @Parameter(hidden = true, description = "Timeout for large SPARQL queries, in seconds. 0 or null means execute without timeout (default = 0). " +
            "Some typical values can be 600 or 1200.", schema = @Schema(implementation = Long.class, defaultValue = "0"))
    private Long largeQueryTimeout;
    @Parameter(hidden = true, description = "Timeout for small SPARQL queries, in seconds. 0 or null means execute without timeout (default = 0). " +
            "A typical value can be 60.", schema = @Schema(implementation = Long.class, defaultValue = "0"))
    private Long smallQueryTimeout;

    @Parameter(hidden = true, description = "Delay after query/endpoint failure, in seconds. 0 or null means no delay (default = 0). " +
            "Can be useful in rare occasions, if the endpoint responds positively to Health check query (cached), but not real data queries.", schema = @Schema(implementation = Long.class, defaultValue = "0"))
    private Long delayOnFailure;
    @Parameter(hidden = true, description = "Waiting time for endpoint recovery, in minutes. 0 or null means wait forever (default = 0)", schema = @Schema(implementation = Long.class, defaultValue = "60"))
    private Long waitingTimeForEndpoint;

    @Parameter(hidden = true, description = "Controls logging when a property has no source or target (for properties with non-literal objects) classes (default = no)", required = true, schema = @Schema(implementation = NoClassesLoggingOptions.class, defaultValue = "no"))
    private NoClassesLoggingOptions logNoClassesForProperty;

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
            calculatePropertyPropertyRelations = Boolean.TRUE;
        }
        return calculatePropertyPropertyRelations;
    }

    public Boolean getCalculateSourceAndTargetPairs() {
        if (calculateSourceAndTargetPairs == null) {
            calculateSourceAndTargetPairs = Boolean.TRUE;
        }
        return calculateSourceAndTargetPairs;
    }

    public Boolean getCalculateDomainsAndRanges() {
        if (calculateDomainsAndRanges == null) {
            calculateDomainsAndRanges = Boolean.TRUE;
        }
        return calculateDomainsAndRanges;
    }

    public DistinctSubjectsAndObjectsMode getIncludeDistinctSubjectsAndObjects() {
        if (includeDistinctSubjectsAndObjects == null) {
            includeDistinctSubjectsAndObjects = DistinctSubjectsAndObjectsMode.propertyLevel;
        }
        return includeDistinctSubjectsAndObjects;
    }

    public BlankNodeMode getComputeBlankNodeStatistics() {
        if (computeBlankNodeStatistics == null) {
            computeBlankNodeStatistics = BlankNodeMode.full;
        }
        return computeBlankNodeStatistics;
    }

    public Boolean getCalculateClosedClassSets() {
        if (calculateClosedClassSets == null) {
            calculateClosedClassSets = Boolean.TRUE;
        }
        return calculateClosedClassSets;
    }

    public ImportantIndexesMode getCalculateImportanceIndexes() {
        if (calculateImportanceIndexes == null) {
            calculateImportanceIndexes = ImportantIndexesMode.basic;
        }
        return calculateImportanceIndexes;
    }

    @Nonnull
    public NoClassesLoggingOptions getLogNoClassesForProperty() {
        if (logNoClassesForProperty == null) {
            logNoClassesForProperty = NoClassesLoggingOptions.no;
        }
        return logNoClassesForProperty;
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
            addIntersectionClasses = ShowIntersectionClassesMode.yes;
        }
        return addIntersectionClasses;
    }

    @Nonnull
    public DistinctQueryMode getExactCountCalculations() {
        if (exactCountCalculations == null) {
            exactCountCalculations = DistinctQueryMode.no;
        }
        return exactCountCalculations;
    }

    @Nonnull
    public Boolean getCalculateDistinctTriples() {
        if (calculateDistinctTriples == null) {
            calculateDistinctTriples = Boolean.TRUE;
        }
        return calculateDistinctTriples;
    }

    @Nonnull
    public Boolean getRequireClasses() {
        if (requireClasses == null) {
            requireClasses = Boolean.TRUE;
        }
        return requireClasses;
    }

    @Nonnull
    public Boolean getValidateClassesList() {
        if (validateClassesList == null) {
            validateClassesList = Boolean.TRUE;
        }
        return validateClassesList;
    }

    @Nonnull
    public Boolean getValidatePropertiesList() {
        if (validatePropertiesList == null) {
            validatePropertiesList = Boolean.TRUE;
        }
        return validatePropertiesList;
    }

    @Nonnull
    public Boolean getCrossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure() {
        if (crossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure == null) {
            crossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure = Boolean.FALSE;
        }
        return crossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure;
    }

    @Nonnull
    public PropertyRelationsCheckMode getPropertyPropertyLinkCheckBackupMode() {
        if (propertyPropertyLinkCheckBackupMode == null) {
            propertyPropertyLinkCheckBackupMode = PropertyRelationsCheckMode.details;
        }
        return propertyPropertyLinkCheckBackupMode;
    }
}

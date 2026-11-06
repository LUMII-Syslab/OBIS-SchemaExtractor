package lv.lumii.obis.rest.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;

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

    @Schema(description = "SPARQL Endpoint URL, for example, http://localhost:8890/sparql", requiredMode = Schema.RequiredMode.REQUIRED)
    private String endpointUrl;

    @Schema(description = "Named Graph (optional). If no graph name provided, the search will involve all graphs from the endpoint")
    private String graphName;

    @Schema(description = "Calculate subclass relation (strongly recommended)", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculateSubClassRelations;

    @Schema(description = "Include multiple inheritance check, strongly recommended. If set to false, no more than a single direct super-class for a class shall be found. " +
            "Set false only for very large endpoints, if the processing of subclass relations is stuck in the extractor for a longer time.", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculateMultipleInheritanceSuperclasses;

    @Schema(description = "Minimal analyzed class size. The classes below the specified size shall be included in the schema, " +
            "still they shall not be considered as possible super-classes of other classes and their links to properties shall not be analyzed individually. " +
            "If 1, all classes should be analyzed. Values above 1, e.g. 10 or 100, may be essential for endpoints with more than several thousand classes.", defaultValue = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer minimalAnalyzedClassSize;

    @Schema(description = "At least 1 class is required for the schema extraction, if no classes found - the extractor work is aborted", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean requireClasses;

    @Schema(hidden = true, description = "Validate the list of classes with simple and statistics queries ", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean validateClassesList;
    @Schema(hidden = true, description = "Validate the list of properties with simple and statistics queries ", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean validatePropertiesList;

    @Schema(description = "Calculate property-property adjacency: following properties, same subject, same object. " +
            "Important for visual and textual query auto-completion use case; can be used in schema visualization if property sources and targets are considered as potential nodes (work in progress). " +
            "Can be time-consuming for larger schemas, consider appropriate propertyPropertyLinkCheckBackupMode", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculatePropertyPropertyRelations;

    @Schema(description = "Set backup validation queries to calculate property-property relations in the case of a failing property co-occurrence query for a given base property. " +
            "details, limitsPlus and limitsPlusSimple guarantee trying a single-line pattern of two property co-occurrence, if larger patterns fail. " +
            "details gives exact statistics for two property co-occurrence, whenever possible. " +
            "limitsPlus attempts at least a limit-based estimate, limitsPlusSimple can stay with yes/no answer for co-occurrence for performance reasons (with some count information imputed at later schema import steps). " +
            "none and limits are faster, but can result in partially incomplete structure information.", defaultValue = "details", requiredMode = Schema.RequiredMode.REQUIRED)
    private PropertyRelationsCheckMode propertyPropertyLinkCheckBackupMode;

    @Schema(description = "Calculate pairs of source and target classes for properties (creates finer-grained schemas; used as a source of statistics in visual schema diagrams)", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculateSourceAndTargetPairs;

    @Schema(description = "Refine existing class-property relation counts. All and CPRels enable refinement; None keeps recovered counts.", defaultValue = "None")
    private SchemaExtractorRequestDto.FineTuneTripleCounts fineTuneTripleCounts;

    @Schema(description = "Calculate domain and range classes for properties (in the strict, ontological sense)", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculateDomainsAndRanges;

    @Schema(hidden = true, description = "Calculate distinct subjects and objects count for property and/or for property source and target classes", defaultValue = "propertyLevel", requiredMode = Schema.RequiredMode.REQUIRED)
    private DistinctSubjectsAndObjectsMode includeDistinctSubjectsAndObjects;

    @Schema(hidden = true, description = "Calculate blank node statistics", defaultValue = "full", requiredMode = Schema.RequiredMode.REQUIRED)
    private BlankNodeMode computeBlankNodeStatistics;

    @Schema(description = "Calculate ascription points (principal classes) for properties (strongly recommended, if schema diagrams are envisaged). Use 'class coverage', if all class-to-property connections are to be marked for the class itself, or some its superclass or subclass (can make a difference in the case of overlapping classes)", defaultValue = "basic", requiredMode = Schema.RequiredMode.REQUIRED)
    private ImportantIndexesMode calculateImportanceIndexes;

    @Schema(description = "Check, if the property source and target class set sets cover all non-literal property subjects and objects; " +
            "essential for future SHACL export, can be used in advanced schema diagrams.", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculateClosedClassSets;

    @Schema(description = "Calculate minimum and maximum cardinalities for properties. May consider propertyLevelOnly for larger schemas.", defaultValue = "propertyLevelAndClassContext", requiredMode = Schema.RequiredMode.REQUIRED)
    private CalculatePropertyFeatureMode calculateCardinalities;

    @Schema(description = "Calculate data types for attributes. May consider propertyLevelOnly for larger schemas.", defaultValue = "propertyLevelAndClassContext", requiredMode = Schema.RequiredMode.REQUIRED)
    private CalculatePropertyFeatureMode calculateDataTypes;

    @Schema(description = "Instance sample size for datatype calculation. It is recommended to provide a constraint (e.g., 100K or 1M) for larger data sets " +
            "as the exact data set information can be less essential in the schemas and attempts to obtain exact data type usage statistics may take unreasonably long time.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long sampleLimitForDataTypeCalculation;

    @Schema(hidden = true, description = "Calculate instance namespace URIs. Mode 'details' will retrieve namespaces for all classes and " +
            "for subjects and objects of all properties. Mode 'overview' will retrieve for limited data set (use parameter sampleLimitForInstanceNamespacesCalculation).", defaultValue = "no", requiredMode = Schema.RequiredMode.REQUIRED)
    private InstanceNamespacesMode calculateInstanceNamespaces;

    @Schema(hidden = true, description = "Limit of instances to use in namespace calculation (no value or 0 means all data will be used)", defaultValue = "1000", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long sampleLimitForInstanceNamespacesCalculation;

    @Schema(description = "Properties for class and property labels. rdfs:label and skos:prefLabel assumed by default. For labels in specific languages use @{en,de} notation after the label property iri or short form. To exclude a default labeling property, use property@{-} notation.")
    private List<String> addedLabels;

    @Schema(description = "Record intersection class information in the extracted schema", defaultValue = "yes", requiredMode = Schema.RequiredMode.REQUIRED)
    private ShowIntersectionClassesMode addIntersectionClasses;

    @Schema(hidden = true, description = "List of properties defining the class structure; the classifier values (objects of these property triples) can be superclasses of other classes/classifiers and are checked for being sources and targets for other properties (default property is rdf:type)")
    private List<String> principalClassificationProperties;

    @Schema(hidden = true, description = "List of properties defining the classifiers whose values can define subsets of principal property classifiers (classes) and that are checked for being sources and targets for other properties")
    private List<String> classificationPropertiesWithConnectionsOnly;

    @Schema(hidden = true, description = "List of properties defining simple classifiers (their values can define subsets of principal classifiers (classes)), not checked for being sources and targets for other properties")
    private List<String> simpleClassificationProperties;

    @Schema(hidden = true, description = "Add DISTINCT in queries (can be used for classes and properties of less than 10M size, if entity/triple duplications are observed otherwise in the schemas)", defaultValue = "no", requiredMode = Schema.RequiredMode.REQUIRED)
    private DistinctQueryMode exactCountCalculations;
    @Schema(hidden = true, description = "Calculate distinct triples count for classes and properties)", defaultValue = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean calculateDistinctTriples;

    @Schema(hidden = true, description = "Total instance count limit for exact count calculations", defaultValue = "10000000", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long maxInstanceLimitForExactCount;

    @Schema(hidden = true, description = "Cross check target classes on non-literal property object check failure", defaultValue = "false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Boolean crossCheckTargetClassesOnNonLiteralPropertyObjectCheckFailure;

    @Schema(description = "List of excluded namespaces (e.g., http://www.openlinksw.com/schemas/virtrdf#)")
    private List<String> excludedNamespaces;

    @Schema(hidden = true, description = "Timeout for large SPARQL queries, in seconds. 0 or null means execute without timeout (default = 0). " +
            "Some typical values can be 600 or 1200.", defaultValue = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long largeQueryTimeout;
    @Schema(hidden = true, description = "Timeout for small SPARQL queries, in seconds. 0 or null means execute without timeout (default = 0). " +
            "A typical value can be 60.", defaultValue = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long smallQueryTimeout;

    @Schema(hidden = true, description = "Delay after query/endpoint failure, in seconds. 0 or null means no delay (default = 0). " +
            "Can be useful in rare occasions, if the endpoint responds positively to Health check query (cached), but not real data queries.", defaultValue = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long delayOnFailure;
    @Schema(hidden = true, description = "Waiting time for endpoint recovery, in minutes. 0 or null means wait forever (default = 0)", defaultValue = "60", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long waitingTimeForEndpoint;

    @Schema(hidden = true, description = "Controls logging when a property has no source or target (for properties with non-literal objects) classes (default = no)", defaultValue = "no", requiredMode = Schema.RequiredMode.REQUIRED)
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
    // Keep optional relation recounting disabled when callers omit the new parameter.
    public SchemaExtractorRequestDto.FineTuneTripleCounts getFineTuneTripleCounts() {
        return fineTuneTripleCounts == null ? SchemaExtractorRequestDto.FineTuneTripleCounts.None : fineTuneTripleCounts;
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

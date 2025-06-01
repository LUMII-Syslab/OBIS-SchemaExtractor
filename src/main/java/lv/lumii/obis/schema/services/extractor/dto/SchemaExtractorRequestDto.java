package lv.lumii.obis.schema.services.extractor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorPredefinedNamespaces;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedClassDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedLabelDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedPropertyDto;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static lv.lumii.obis.schema.constants.SchemaConstants.RDF_TYPE;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class SchemaExtractorRequestDto {

    /**
     * DEPRECATED enums for old services
     */
    public enum ExtractionMode {excludeDataTypesAndCardinalities, excludeCardinalities, full}

    public enum ExtractionVersion {manySmallQueries, manySmallQueriesWithDirectProperties, fewComplexQueries}

    /**
     * Actual properties for new services
     */

    public enum CalculatePropertyFeatureMode {none, propertyLevelOnly, propertyLevelAndClassContext}

    public enum ShowIntersectionClassesMode {yes, no, auto}

    public enum DistinctQueryMode {yes, no, auto}

    public enum ImportantIndexesMode {basic, unionBased, classCoverage, no}

    public enum InstanceNamespacesMode {no, detailed, overview}

    private String correlationId;

    private String endpointUrl;
    private String graphName;

    private Boolean calculateSubClassRelations;
    private Boolean calculateMultipleInheritanceSuperclasses;
    private Boolean calculatePropertyPropertyRelations;
    private Boolean calculateSourceAndTargetPairs;
    private Boolean calculateDomainsAndRanges;
    private ImportantIndexesMode calculateImportanceIndexes;
    private Boolean calculateClosedClassSets;
    private CalculatePropertyFeatureMode calculateCardinalities;
    private CalculatePropertyFeatureMode calculateDataTypes;
    private Long sampleLimitForDataTypeCalculation;

    @JsonIgnore
    private Long sampleLimitForPropertyClassRelationCalculation;
    @JsonIgnore
    private Long sampleLimitForPropertyToPropertyRelationCalculation;

    private InstanceNamespacesMode calculateInstanceNamespaces;
    private Long sampleLimitForInstanceNamespacesCalculation;
    private Integer minimalAnalyzedClassSize;
    private List<String> principalClassificationProperties;
    private List<String> classificationPropertiesWithConnectionsOnly;
    private List<String> simpleClassificationProperties;
    private ShowIntersectionClassesMode addIntersectionClasses;
    private DistinctQueryMode exactCountCalculations;
    @JsonIgnore
    private Long maxInstanceLimitForExactCount;
    private List<SchemaExtractorRequestedLabelDto> includedLabels;
    private List<SchemaExtractorRequestedClassDto> includedClasses;
    private List<SchemaExtractorRequestedPropertyDto> includedProperties;
    private List<String> excludedNamespaces;
    private SchemaExtractorPredefinedNamespaces predefinedNamespaces;

    private Long largeQueryTimeout;
    private Long smallQueryTimeout;

    private Long delayOnFailure;

    private Boolean enableLogging;

    @JsonIgnore
    private Map<String, String> queries;

    @JsonIgnore
    private Set<String> allClassificationProperties;
    @JsonIgnore
    private Set<String> mainClassificationProperties;

    @JsonIgnore
    private Boolean postMethod;

    /**
     * DEPRECATED properties for old services
     */
    @JsonIgnore
    private ExtractionVersion version;
    @JsonIgnore
    private ExtractionMode mode;
    @JsonIgnore
    private Boolean excludeSystemClasses;
    @JsonIgnore
    private Boolean excludeMetaDomainClasses;
    @JsonIgnore
    private Boolean excludePropertiesWithoutClasses;

    public SchemaExtractorRequestDto(@Nonnull String correlationId) {
        this.correlationId = correlationId;
    }

    @Nonnull
    public List<SchemaExtractorRequestedClassDto> getIncludedClasses() {
        if (includedClasses == null) {
            includedClasses = new ArrayList<>();
        }
        return includedClasses;
    }

    @Nonnull
    public List<SchemaExtractorRequestedPropertyDto> getIncludedProperties() {
        if (includedProperties == null) {
            includedProperties = new ArrayList<>();
        }
        return includedProperties;
    }

    @Nonnull
    public List<SchemaExtractorRequestedLabelDto> getIncludedLabels() {
        if (includedLabels == null) {
            includedLabels = new ArrayList<>();
        }
        return includedLabels;
    }

    @Nonnull
    public Map<String, String> getQueries() {
        if (queries == null) {
            queries = new HashMap<>();
        }
        return queries;
    }

    @Nonnull
    public Boolean getCalculateSubClassRelations() {
        if (calculateSubClassRelations == null) {
            return Boolean.TRUE;
        }
        return calculateSubClassRelations;
    }

    @Nonnull
    public Boolean getCalculateMultipleInheritanceSuperclasses() {
        if (calculateMultipleInheritanceSuperclasses == null) {
            calculateMultipleInheritanceSuperclasses = Boolean.TRUE;
        }
        return calculateMultipleInheritanceSuperclasses;
    }

    @Nonnull
    public Boolean getCalculatePropertyPropertyRelations() {
        if (calculatePropertyPropertyRelations == null) {
            return Boolean.TRUE;
        }
        return calculatePropertyPropertyRelations;
    }

    @Nonnull
    public Boolean getCalculateSourceAndTargetPairs() {
        if (calculateSourceAndTargetPairs == null) {
            return Boolean.TRUE;
        }
        return calculateSourceAndTargetPairs;
    }

    @Nonnull
    public Boolean getCalculateDomainsAndRanges() {
        if (calculateDomainsAndRanges == null) {
            return Boolean.TRUE;
        }
        return calculateDomainsAndRanges;
    }

    @Nonnull
    public ImportantIndexesMode getCalculateImportanceIndexes() {
        if (calculateImportanceIndexes == null) {
            return ImportantIndexesMode.basic;
        }
        return calculateImportanceIndexes;
    }

    @Nonnull
    public Boolean getCalculateClosedClassSets() {
        if (calculateClosedClassSets == null) {
            return Boolean.FALSE;
        }
        return calculateClosedClassSets;
    }

    @Nonnull
    public CalculatePropertyFeatureMode getCalculateDataTypes() {
        if (calculateDataTypes == null) {
            return CalculatePropertyFeatureMode.propertyLevelAndClassContext;
        }
        return calculateDataTypes;
    }

    @Nonnull
    public CalculatePropertyFeatureMode getCalculateCardinalities() {
        if (calculateCardinalities == null) {
            return CalculatePropertyFeatureMode.propertyLevelAndClassContext;
        }
        return calculateCardinalities;
    }

    @Nonnull
    public InstanceNamespacesMode getCalculateInstanceNamespaces() {
        if (calculateInstanceNamespaces == null) {
            return InstanceNamespacesMode.no;
        }
        return calculateInstanceNamespaces;
    }

    @Nonnull
    public Integer getMinimalAnalyzedClassSize() {
        if (minimalAnalyzedClassSize == null) {
            return 1;
        }
        return minimalAnalyzedClassSize;
    }

    @Nonnull
    public List<String> getPrincipalClassificationProperties() {
        if (principalClassificationProperties == null) {
            principalClassificationProperties = Collections.singletonList(RDF_TYPE);
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
    public Boolean getEnableLogging() {
        if (enableLogging == null) {
            enableLogging = Boolean.TRUE;
        }
        return enableLogging;
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
    public Long getSampleLimitForInstanceNamespacesCalculation() {
        if (sampleLimitForInstanceNamespacesCalculation == null) {
            sampleLimitForInstanceNamespacesCalculation = 1000L;
        }
        return sampleLimitForInstanceNamespacesCalculation;
    }

    @Nonnull
    public Set<String> getAllClassificationProperties() {
        if (allClassificationProperties == null) {
            allClassificationProperties =
                    Stream.of(getPrincipalClassificationProperties(), getClassificationPropertiesWithConnectionsOnly(), getSimpleClassificationProperties())
                            .flatMap(Collection::stream)
                            .collect(Collectors.toSet());
        }
        return allClassificationProperties;
    }

    @Nonnull
    public Set<String> getMainClassificationProperties() {
        if (mainClassificationProperties == null) {
            mainClassificationProperties =
                    Stream.of(getPrincipalClassificationProperties(), getClassificationPropertiesWithConnectionsOnly())
                            .flatMap(Collection::stream)
                            .collect(Collectors.toSet());
        }
        return mainClassificationProperties;
    }

    @Nonnull
    public Boolean getPostMethod() {
        if (postMethod == null) {
            return Boolean.FALSE;
        }
        return postMethod;
    }

    @Nonnull
    public Long getLargeQueryTimeout() {
        if(largeQueryTimeout == null) {
            return 0L;
        }
        return largeQueryTimeout;
    }

    @Nonnull
    public Long getSmallQueryTimeout() {
        if(smallQueryTimeout == null) {
            return 0L;
        }
        return smallQueryTimeout;
    }

    @Nonnull
    public Long getDelayOnFailure() {
        if(delayOnFailure == null) {
            return 0L;
        }
        return delayOnFailure;
    }

    @Nonnull
    public String printMainParameters() {
        return "{ \"correlationId\":\"" + this.correlationId + "\",\"endpointUrl\":\"" + this.endpointUrl + "\",\"graphName\":\"" + this.graphName + "\" }";
    }
}

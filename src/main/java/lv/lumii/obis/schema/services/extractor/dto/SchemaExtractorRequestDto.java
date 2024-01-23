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

    public enum CalculateCardinalitiesMode {none, propertyLevelOnly, propertyLevelAndClassContext}
    public enum ShowIntersectionClassesMode {yes, no, auto}

    private String correlationId;

    private String endpointUrl;
    private String graphName;

    private Boolean calculateSubClassRelations;
    private Boolean calculatePropertyPropertyRelations;
    private Boolean calculateSourceAndTargetPairs;
    private Boolean calculateDomainsAndRanges;
    private Boolean calculateImportanceIndexes;
    private Boolean calculateClosedClassSets;
    private Boolean calculateDataTypes;
    private Long dataTypeSampleLimit;
    private CalculateCardinalitiesMode calculateCardinalitiesMode;
    private Boolean checkInstanceNamespaces;
    private Integer minimalAnalyzedClassSize;
    private List<String> classificationProperties;
    private ShowIntersectionClassesMode addIntersectionClasses;
    private List<SchemaExtractorRequestedLabelDto> includedLabels;
    private List<SchemaExtractorRequestedClassDto> includedClasses;
    private List<SchemaExtractorRequestedPropertyDto> includedProperties;
    private List<String> excludedNamespaces;
    private SchemaExtractorPredefinedNamespaces predefinedNamespaces;

    private Boolean enableLogging;

    @JsonIgnore
    private Map<String, String> queries;

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
    public Boolean getCalculateImportanceIndexes() {
        if (calculateImportanceIndexes == null) {
            return Boolean.TRUE;
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
    public Boolean getCalculateDataTypes() {
        if (calculateDataTypes == null) {
            return Boolean.TRUE;
        }
        return calculateDataTypes;
    }

    @Nonnull
    public CalculateCardinalitiesMode getCalculateCardinalitiesMode() {
        if (calculateCardinalitiesMode == null) {
            return CalculateCardinalitiesMode.propertyLevelAndClassContext;
        }
        return calculateCardinalitiesMode;
    }

    @Nonnull
    public Boolean getCheckInstanceNamespaces() {
        if (checkInstanceNamespaces == null) {
            return Boolean.FALSE;
        }
        return checkInstanceNamespaces;
    }

    @Nonnull
    public Integer getMinimalAnalyzedClassSize() {
        if (minimalAnalyzedClassSize == null) {
            return 1;
        }
        return minimalAnalyzedClassSize;
    }

    @Nonnull
    public List<String> getClassificationProperties() {
        if (classificationProperties == null) {
            classificationProperties = Collections.singletonList(RDF_TYPE);
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
    public String printMainParameters() {
        return "{ \"correlationId\":\"" + this.correlationId + "\",\"endpointUrl\":\"" + this.endpointUrl + "\",\"graphName\":\"" + this.graphName + "\" }";
    }
}

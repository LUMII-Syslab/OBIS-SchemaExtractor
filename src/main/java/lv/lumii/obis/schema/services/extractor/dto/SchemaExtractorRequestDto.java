package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorPredefinedNamespaces;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedClassDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedLabelDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedPropertyDto;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
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

    private String correlationId;
    private Boolean enableLogging;

    private String endpointUrl;
    private String graphName;

    private Boolean calculateSubClassRelations;
    private Boolean calculatePropertyPropertyRelations;
    private Boolean calculateDomainAndRangePairs;
    private Boolean calculateDataTypes;
    private CalculateCardinalitiesMode calculateCardinalitiesMode;
    private Integer minimalAnalyzedClassSize;
    private List<SchemaExtractorRequestedLabelDto> includedLabels;
    private List<SchemaExtractorRequestedClassDto> includedClasses;
    private List<SchemaExtractorRequestedPropertyDto> includedProperties;
    private List<String> excludedNamespaces;
    private SchemaExtractorPredefinedNamespaces predefinedNamespaces;

    /**
     * DEPRECATED properties for old services
     */
    private ExtractionVersion version;
    private ExtractionMode mode;
    private Boolean excludeSystemClasses;
    private Boolean excludeMetaDomainClasses;
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
}

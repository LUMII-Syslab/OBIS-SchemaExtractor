package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorPredefinedNamespaces;

import javax.annotation.Nonnull;
import java.util.List;

@Setter @Getter
public class SchemaExtractorRequestDto {

    /**
     * DEPRECATED enums for old services
     */
    public enum ExtractionMode {excludeDataTypesAndCardinalities, excludeCardinalities, full}
    public enum ExtractionVersion {manySmallQueries, manySmallQueriesWithDirectProperties, fewComplexQueries}

    /**
     * Actual properties for new services
     */
    private String correlationId;
    private Boolean enableLogging;

    private String endpointUrl;
    private String graphName;

    private Boolean calculateSubClassRelations;
    private Boolean calculateDataTypes;
    private Boolean calculateCardinalities;
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
}

package lv.lumii.obis.rest.app;

import com.google.common.base.Enums;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.services.CsvFileReaderUtil;
import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorPredefinedNamespaces;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedClassDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedLabelDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedPropertyDto;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.RDF_TYPE;

@Service
public class SchemaExtractorRequestBuilder {

    private static final String RNG_ALGORITHM = "SHA1PRNG";

    private static volatile SecureRandom secureRandom;

    private final JsonSchemaService jsonSchemaService;
    private final CsvFileReaderUtil csvFileReaderUtil;

    public SchemaExtractorRequestBuilder(JsonSchemaService jsonSchemaService, CsvFileReaderUtil csvFileReaderUtil) {
        this.jsonSchemaService = jsonSchemaService;
        this.csvFileReaderUtil = csvFileReaderUtil;
    }

    public SchemaExtractorRequestDto buildRequest(@Nonnull SchemaExtractorRequestNew request) {
        SchemaExtractorRequestDto requestDto = new SchemaExtractorRequestDto(generateCorrelationId());
        requestDto.setEndpointUrl(request.getEndpointUrl());
        requestDto.setGraphName(request.getGraphName());
        requestDto.setCalculateSubClassRelations(request.getCalculateSubClassRelations());
        requestDto.setCalculatePropertyPropertyRelations(request.getCalculatePropertyPropertyRelations());
        requestDto.setCalculateSourceAndTargetPairs(request.getCalculateSourceAndTargetPairs());
        requestDto.setCalculateDomainsAndRanges(request.getCalculateDomainsAndRanges());
        requestDto.setCalculateClosedClassSets(request.getCalculateClosedClassSets());
        requestDto.setCalculateImportanceIndexes(request.getCalculateImportanceIndexes());
        requestDto.setCalculateDataTypes(request.getCalculateDataTypes());
        requestDto.setDataTypeSampleLimit(request.getDataTypeSampleLimit());
        requestDto.setCalculateCardinalitiesMode(Enums.getIfPresent(SchemaExtractorRequestDto.CalculateCardinalitiesMode.class, request.getCalculateCardinalitiesMode().name()).orNull());
        requestDto.setCheckInstanceNamespaces(request.getCheckInstanceNamespaces());
        requestDto.setIncludedLabels(applyLabels(request.getAddedLabels()));
        requestDto.setMinimalAnalyzedClassSize(request.getMinimalAnalyzedClassSize());
        requestDto.setClassificationProperties(applyClassificationProperties(request.getClassificationProperties()));
        requestDto.setExcludedNamespaces(request.getExcludedNamespaces());
        requestDto.setEnableLogging(request.getEnableLogging());
        return requestDto;
    }

    public void applyIncludedClasses(@Nonnull SchemaExtractorRequestDto requestDto, @Nullable InputStream inputStream) {
        List<String[]> includedClasses = csvFileReaderUtil.readAllCsvDataWithoutHeader(inputStream);
        includedClasses.forEach(inputLine -> {
            if (inputLine != null && inputLine.length >= 1 && StringUtils.isNotEmpty(inputLine[0])) {
                SchemaExtractorRequestedClassDto requestedClassDto = new SchemaExtractorRequestedClassDto(inputLine[0]);
                if (inputLine.length > 1 && StringUtils.isNotEmpty(inputLine[1])) {
                    requestedClassDto.setInstanceCount(inputLine[1]);
                }
                requestDto.getIncludedClasses().add(requestedClassDto);
            }
        });
    }

    public void applyIncludedProperties(@Nonnull SchemaExtractorRequestDto requestDto, @Nullable InputStream inputStream) {
        List<String[]> includedProperties = csvFileReaderUtil.readAllCsvDataWithoutHeader(inputStream);
        includedProperties.forEach(inputLine -> {
            if (inputLine != null && inputLine.length >= 1 && StringUtils.isNotEmpty(inputLine[0])) {
                SchemaExtractorRequestedPropertyDto requestedPropertyDto = new SchemaExtractorRequestedPropertyDto(inputLine[0]);
                if (inputLine.length > 1 && StringUtils.isNotEmpty(inputLine[1])) {
                    requestedPropertyDto.setInstanceCount(inputLine[1]);
                }
                requestDto.getIncludedProperties().add(requestedPropertyDto);
            }
        });
    }

    public void applyPredefinedNamespaces(@Nonnull SchemaExtractorRequestDto requestDto, @Nullable InputStream inputStream) {
        requestDto.setPredefinedNamespaces(null);
        if (inputStream != null) {
            SchemaExtractorPredefinedNamespaces predefinedNamespaces = jsonSchemaService.getObjectFromJsonStream(inputStream, SchemaExtractorPredefinedNamespaces.class);
            if (predefinedNamespaces != null && predefinedNamespaces.getNamespaceItems() != null) {
                requestDto.setPredefinedNamespaces(predefinedNamespaces);
            }
        }
    }

    public SchemaExtractorRequestDto buildRequest(@Nonnull SchemaExtractorRequestOld request) {
        SchemaExtractorRequestDto requestDto = new SchemaExtractorRequestDto(generateCorrelationId());
        requestDto.setEndpointUrl(request.getEndpointUrl());
        requestDto.setGraphName(request.getGraphName());
        requestDto.setVersion(Enums.getIfPresent(SchemaExtractorRequestDto.ExtractionVersion.class, request.getVersion().name()).orNull());
        requestDto.setMode(Enums.getIfPresent(SchemaExtractorRequestDto.ExtractionMode.class, request.getMode().name()).orNull());
        requestDto.setExcludeSystemClasses(request.getExcludeSystemClasses());
        requestDto.setExcludeMetaDomainClasses(request.getExcludeMetaDomainClasses());
        requestDto.setExcludePropertiesWithoutClasses(request.getExcludePropertiesWithoutClasses());
        requestDto.setEnableLogging(request.getEnableLogging());
        return requestDto;
    }

    public String generateCorrelationId() {
        try {
            if (secureRandom == null) {
                secureRandom = SecureRandom.getInstance(RNG_ALGORITHM);
            }
            return Long.toString(Math.abs(secureRandom.nextLong()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating unique correlation id for SchemaExtractor request: ", e);
        }
    }

    private List<SchemaExtractorRequestedLabelDto> applyLabels(@Nonnull List<String> includedLabels) {
        List<SchemaExtractorRequestedLabelDto> requestedLabelDtos = new ArrayList<>();

        // Expected label format: <label>@{<list of languages>}
        // Example 1: http://www.w3.org/2000/01/rdf-schema#label
        // Example 2: http://www.w3.org/2000/01/rdf-schema#label@{en}
        // Example 3: http://www.w3.org/2000/01/rdf-schema#label@{en;es;lv}
        for (String label : includedLabels) {
            if (StringUtils.isEmpty(label)) continue;

            String[] parts = label.split("@", 2);

            if (parts[0].trim().length() == 0) continue;

            if (parts.length == 1 || parts[1].trim().length() < 3) {
                requestedLabelDtos.add(new SchemaExtractorRequestedLabelDto(parts[0]));
                continue;
            }

            String[] languages = parts[1].substring(1, parts[1].length() - 1).split("\\s*;\\s*");
            requestedLabelDtos.add(
                    new SchemaExtractorRequestedLabelDto(parts[0], Arrays.stream(languages).collect(Collectors.toList())));
        }

        return requestedLabelDtos;
    }

    @Nonnull
    private List<String> applyClassificationProperties(@Nonnull List<String> classificationProperties) {
        List<String> formattedClassificationProperties = new ArrayList<>();
        for (String classificationProperty : classificationProperties) {
            if (classificationProperty != null && SchemaConstants.RDF_TYPE_SHORT.equalsIgnoreCase(classificationProperty.trim())) {
                formattedClassificationProperties.add(SchemaConstants.RDF_TYPE);
            } else {
                formattedClassificationProperties.add(classificationProperty);
            }
        }
        return (formattedClassificationProperties.isEmpty())
                ? Collections.singletonList(RDF_TYPE) : Collections.unmodifiableList(formattedClassificationProperties);
    }

}

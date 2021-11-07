package lv.lumii.obis.rest.app;

import com.google.common.base.Enums;
import lv.lumii.obis.schema.services.CsvFileReaderUtil;
import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorPredefinedNamespaces;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedClassDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorRequestedPropertyDto;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Service
public class SchemaExtractorRequestBuilder {

    private static final String RNG_ALGORITHM = "SHA1PRNG";

    private static volatile SecureRandom secureRandom;

    private JsonSchemaService jsonSchemaService;
    private CsvFileReaderUtil csvFileReaderUtil;

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
        requestDto.setCalculateDomainAndRangePairs(request.getCalculateDomainAndRangePairs());
        requestDto.setCalculateDataTypes(request.getCalculateDataTypes());
        requestDto.setCalculateCardinalitiesMode(Enums.getIfPresent(SchemaExtractorRequestDto.CalculateCardinalitiesMode.class, request.getCalculateCardinalitiesMode().name()).orNull());
        requestDto.setMinimalAnalyzedClassSize(request.getMinimalAnalyzedClassSize());
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

}

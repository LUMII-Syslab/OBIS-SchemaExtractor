package lv.lumii.obis.rest.app;

import com.google.common.base.Enums;
import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorPredefinedNamespaces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@Service
public class SchemaExtractorRequestBuilder {

    private static final String RNG_ALGORITHM = "SHA1PRNG";

    private static volatile SecureRandom secureRandom;

    private JsonSchemaService jsonSchemaService;

    public SchemaExtractorRequestBuilder(JsonSchemaService jsonSchemaService) {
        this.jsonSchemaService = jsonSchemaService;
    }

    public SchemaExtractorRequestDto buildRequest(@Nonnull SchemaExtractorRequestNew request) {
        SchemaExtractorRequestDto requestDto = new SchemaExtractorRequestDto(generateCorrelationId());
        requestDto.setEndpointUrl(request.getEndpointUrl());
        requestDto.setGraphName(request.getGraphName());
        requestDto.setCalculateSubClassRelations(request.getCalculateSubClassRelations());
        requestDto.setCalculateDataTypes(request.getCalculateDataTypes());
        requestDto.setCalculateCardinalities(request.getCalculateCardinalities());
        requestDto.setExcludedNamespaces(request.getExcludedNamespaces());
        requestDto.setEnableLogging(request.getEnableLogging());
        return requestDto;
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

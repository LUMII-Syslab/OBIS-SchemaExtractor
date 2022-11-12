package lv.lumii.obis.rest.app;

import com.google.gson.Gson;
import io.swagger.annotations.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import lv.lumii.obis.schema.services.extractor.v2.SchemaExtractor;
import lv.lumii.obis.schema.services.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nonnull;

import javax.ws.rs.core.MediaType;
import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * REST Controller to process Schema Extractor web requests.
 */
@RestController
@RequestMapping("/schema-extractor-rest/v2")
@Api(value = "SchemaExtractorServicesV2", tags = "Services V2")
@Slf4j
@Setter
public class SchemaExtractorControllerV2 {

    private static final String SCHEMA_EXTRACT_MESSAGE_START = "Starting to read schema from the endpoint with parameters %s";
    private static final String SCHEMA_EXTRACT_MESSAGE_END = "Completed JSON schema extraction in %s from the specified endpoint with parameters %s";

    private static final String SCHEMA_READ_INCLUDED_CLASSES_MESSAGE_START = "Request %s - Starting to read included classes from the CSV file [%s]";
    private static final String SCHEMA_READ_INCLUDED_CLASSES_MESSAGE_END = "Request %s - Completed to read included classes from the CSV file [%s]";
    private static final String SCHEMA_READ_INCLUDED_CLASSES_MESSAGE_ERROR = "Request %s - Cannot read included classes from the CSV file [%s]";
    private static final String SCHEMA_READ_INCLUDED_PROPERTIES_MESSAGE_START = "Request %s - Starting to read included properties from the CSV file [%s]";
    private static final String SCHEMA_READ_INCLUDED_PROPERTIES_MESSAGE_END = "Request %s - Completed to read included properties from the CSV file [%s]";
    private static final String SCHEMA_READ_INCLUDED_PROPERTIES_MESSAGE_ERROR = "Request %s - Cannot read included properties from the CSV file [%s]";
    private static final String SCHEMA_READ_PREFIX_FILE_MESSAGE_START = "Request %s - Starting to read Prefixes from the JSON file [%s]";
    private static final String SCHEMA_READ_PREFIX_FILE_MESSAGE_END = "Request %s - Completed to read Prefixes from the JSON file [%s]";
    private static final String SCHEMA_READ_PREFIX_FILE_MESSAGE_ERROR = "Request %s - Cannot read Prefixes from the JSON file [%s]";

    @Autowired
    private SchemaExtractor schemaExtractor;
    @Autowired
    private JsonSchemaService jsonSchemaService;
    @Autowired
    private SchemaExtractorRequestBuilder requestBuilder;


    @RequestMapping(value = "/endpoint/buildFullSchema", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build full schema model (version 2)",
            consumes = MediaType.APPLICATION_JSON,
            produces = MediaType.APPLICATION_JSON,
            response = lv.lumii.obis.schema.model.v2.Schema.class
    )
    @SuppressWarnings("unused")
    public String buildFullSchemaFromEndpointV2(@Validated @ModelAttribute @Nonnull SchemaExtractorRequestNew request,
                                                @RequestParam(value ="includedClassesFile", required=false)
                                                @ApiParam(access = "93", value = "Valid CSV file with the list of included classes (if not specified - all classes will be analyzed)") MultipartFile includedClassesCsvFile,
                                                @RequestParam(value ="includedPropertiesFile", required=false)
                                                @ApiParam(access = "94", value = "Valid CSV file with the list of included properties (if not specified - all properties will be analyzed)") MultipartFile includedPropertiesCsvFile,
                                                @RequestParam(value ="namespacePrefixFile", required=false)
                                                @ApiParam(access = "95", value = "Valid JSON file with predefined namespaces") MultipartFile namespacePrefixFile) {

        // 1. Create the request object
        SchemaExtractorRequestDto requestDto = requestBuilder.buildRequest(request);

        String requestJson = new Gson().toJson(request);
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestJson));
        LocalDateTime startTime = LocalDateTime.now();

        // 2. Read included classes from the external CSV file
        if (includedClassesCsvFile != null && !includedClassesCsvFile.isEmpty()) {
            log.info(String.format(SCHEMA_READ_INCLUDED_CLASSES_MESSAGE_START, requestDto.getCorrelationId(), includedClassesCsvFile.getOriginalFilename()));
            try {
                requestBuilder.applyIncludedClasses(requestDto, includedClassesCsvFile.getInputStream());
            } catch (IOException e) {
                log.error(String.format(SCHEMA_READ_INCLUDED_CLASSES_MESSAGE_ERROR, requestDto.getCorrelationId(), includedClassesCsvFile.getOriginalFilename()));
            }
            log.info(String.format(SCHEMA_READ_INCLUDED_CLASSES_MESSAGE_END, requestDto.getCorrelationId(), includedClassesCsvFile.getOriginalFilename()));
        }

        // 3. Read included properties from the external CSV file
        if (includedPropertiesCsvFile != null && !includedPropertiesCsvFile.isEmpty()) {
            log.info(String.format(SCHEMA_READ_INCLUDED_PROPERTIES_MESSAGE_START, requestDto.getCorrelationId(), includedPropertiesCsvFile.getOriginalFilename()));
            try {
                requestBuilder.applyIncludedProperties(requestDto, includedPropertiesCsvFile.getInputStream());
            } catch (IOException e) {
                log.error(String.format(SCHEMA_READ_INCLUDED_PROPERTIES_MESSAGE_ERROR, requestDto.getCorrelationId(), includedPropertiesCsvFile.getOriginalFilename()));
            }
            log.info(String.format(SCHEMA_READ_INCLUDED_PROPERTIES_MESSAGE_END, requestDto.getCorrelationId(), includedPropertiesCsvFile.getOriginalFilename()));
        }

        // 4. Read prefixes from the external JSON file
        if (namespacePrefixFile != null && !namespacePrefixFile.isEmpty()) {
            log.info(String.format(SCHEMA_READ_PREFIX_FILE_MESSAGE_START, requestDto.getCorrelationId(), namespacePrefixFile.getOriginalFilename()));
            try {
                requestBuilder.applyPredefinedNamespaces(requestDto, namespacePrefixFile.getInputStream());
            } catch (IOException e) {
                log.error(String.format(SCHEMA_READ_PREFIX_FILE_MESSAGE_ERROR, requestDto.getCorrelationId(), namespacePrefixFile.getOriginalFilename()));
            }
            log.info(String.format(SCHEMA_READ_PREFIX_FILE_MESSAGE_END, requestDto.getCorrelationId(), namespacePrefixFile.getOriginalFilename()));
        }

        // 5. Build the schema from the endpoint
        lv.lumii.obis.schema.model.v2.Schema schema = schemaExtractor.extractSchema(requestDto);

        LocalDateTime endTime = LocalDateTime.now();
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startTime, endTime), requestJson));

        // 6. Return the result JSON schema
        return jsonSchemaService.getJsonSchemaString(schema);
    }

    private String calculateExecutionTime(@Nonnull LocalDateTime startLocalDateTime, @Nonnull LocalDateTime endLocalDateTime) {
        return Duration.between(startLocalDateTime, endLocalDateTime).toString().substring(2)
                .replaceAll("(\\d[HMS])", "$1 ")
                .toLowerCase();
    }

}

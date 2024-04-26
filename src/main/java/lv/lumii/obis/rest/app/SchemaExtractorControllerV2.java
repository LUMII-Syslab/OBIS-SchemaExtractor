package lv.lumii.obis.rest.app;

import io.swagger.annotations.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.v2.Schema;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import lv.lumii.obis.schema.services.extractor.v2.SchemaExtractor;
import lv.lumii.obis.schema.services.*;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nonnull;

import javax.annotation.Nullable;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static lv.lumii.obis.schema.constants.SchemaConstants.GLOBAL_SPARQL_QUERIES_PATH;

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
    private static final String SCHEMA_EXTRACT_MESSAGE_ERROR = "The schema extraction process encountered errors/warnings/notes, please check the errors log file";
    private static final String SCHEMA_EXTRACT_MESSAGE_SAVED_FILE = "JSON schema saved in the file %s";
    private static final String SCHEMA_EXTRACT_MESSAGE_FULL_PARAMETERS = "Schema extraction parameters %s";

    private static final String SCHEMA_BUILD_PARAMETERS = "Request %s - Starting to build request parameters";
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
    private ObjectConversionService objectConversionService;
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
                                                @RequestParam(value = "includedClassesFile", required = false)
                                                @ApiParam(access = "240", value = "Valid CSV file with the list of included classes (if not specified - all classes will be analyzed)") MultipartFile includedClassesCsvFile,
                                                @RequestParam(value = "includedPropertiesFile", required = false)
                                                @ApiParam(access = "250", value = "Valid CSV file with the list of included properties (if not specified - all properties will be analyzed)") MultipartFile includedPropertiesCsvFile,
                                                @RequestParam(value = "namespacePrefixFile", required = false)
                                                @ApiParam(access = "260", value = "Valid JSON file with predefined namespaces") MultipartFile namespacePrefixFile,
                                                @RequestParam(value = "enableLogging", required = false, defaultValue = "true")
                                                @ApiParam(access = "270", value = "Enable SPARQL Query Logging to the file") Boolean enableLogging,
                                                @RequestParam(value = "saveThisConfig", required = false, defaultValue = "true")
                                                @ApiParam(access = "280", value = "Save this configuration to file") Boolean saveConfig) {

        // 1. Create the request object
        SchemaExtractorRequestDto requestDto = requestBuilder.buildRequest(request);
        requestDto.setEnableLogging(enableLogging);
        log.info(String.format(SCHEMA_BUILD_PARAMETERS, requestDto.getCorrelationId()));

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

        // 5. Load SPARQL queries defined in the system file
        requestDto.setQueries(initializeSparqlQueries());

        // 6. Build the schema from the endpoint and Save the result JSON schema in file
        String resultSchema = extractSchema(requestDto);

        // 7. Save this specific configuration to file
        if (BooleanUtils.isTrue(saveConfig)) {
            writeDataToFile(requestDto.getCorrelationId() + "-config.yml", objectConversionService.getYamlFromObject(requestDto));
        }

        // 8. Return the result JSON schema
        return resultSchema;
    }

    @RequestMapping(value = "/endpoint/buildFullSchemaFromConfigFile", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build full schema model (version 2) using configuration file",
            consumes = MediaType.MULTIPART_FORM_DATA,
            produces = MediaType.APPLICATION_JSON,
            response = lv.lumii.obis.schema.model.v2.Schema.class
    )
    @SuppressWarnings("unused")
    public String buildFullSchemaFromEndpointV2FromConfigFile(
            @RequestParam(value = "configurationFile")
            @ApiParam(access = "1", value = "Configuration YAML file") MultipartFile configurationFile) {

        // 1. Read request parameters from the configuration YAML file
        String correlationId = SchemaExtractorRequestBuilder.generateCorrelationId();
        log.info(String.format(SCHEMA_BUILD_PARAMETERS, correlationId));
        if (configurationFile == null) {
            return objectConversionService.getJsonFromObject(new ApiError(HttpStatus.BAD_REQUEST, "Valid configuration file is not provided"));
        }
        SchemaExtractorRequestDto requestDto;
        try {
            requestDto = objectConversionService.getObjectFromYamlStream(configurationFile.getInputStream(), SchemaExtractorRequestDto.class);
        } catch (Exception e) {
            return objectConversionService.getJsonFromObject(new ApiError(HttpStatus.BAD_REQUEST, "Valid configuration file is not provided", e));
        }
        if (requestDto == null) {
            return objectConversionService.getJsonFromObject(new ApiError(HttpStatus.BAD_REQUEST, "Valid configuration file is not provided"));
        }

        // 2. Validate the main request parameters
        requestDto.setCorrelationId(correlationId);
        if (StringUtils.isEmpty(requestDto.getEndpointUrl())) {
            return objectConversionService.getJsonFromObject(new ApiError(HttpStatus.BAD_REQUEST, "Valid configuration file is not provided, endpoint URL is empty"));
        }

        // 3. Load SPARQL queries defined in the system file
        requestDto.setQueries(initializeSparqlQueries());

        // 4. Build the schema from the endpoint and Save the result JSON schema in file
        // 5. Return the result JSON schema
        return extractSchema(requestDto);
    }

    private String extractSchema(@Nonnull SchemaExtractorRequestDto requestDto) {
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestDto.printMainParameters()));
        LocalDateTime startTime = LocalDateTime.now();

        Schema schema = schemaExtractor.extractSchema(requestDto);

        LocalDateTime endTime = LocalDateTime.now();
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startTime, endTime), requestDto.printMainParameters()));
        if (schema.getHasErrors() || schema.getHasWarnings() || schema.getHasNotes()) {
            log.error(SCHEMA_EXTRACT_MESSAGE_ERROR);
        }
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_FULL_PARAMETERS, objectConversionService.getJsonFromObject(requestDto)));

        String resultSchema = objectConversionService.getJsonFromObject(schema);
        if (resultSchema != null) {
            String fileName = requestDto.getCorrelationId() + ".json";
            writeDataToFile(fileName, resultSchema);
            log.info(String.format(SCHEMA_EXTRACT_MESSAGE_SAVED_FILE, fileName));
        }

        if(!schema.getErrors().isEmpty()) {
            StringBuilder errors = new StringBuilder("");
            schema.getErrors().forEach(error -> {
                errors.append(error.toString()).append(System.lineSeparator());
            });
            writeDataToFile(requestDto.getCorrelationId() + "-errors.log", errors.toString());
        }

        return resultSchema;
    }

    private String calculateExecutionTime(@Nonnull LocalDateTime startLocalDateTime, @Nonnull LocalDateTime endLocalDateTime) {
        return Duration.between(startLocalDateTime, endLocalDateTime).toString().substring(2)
                .replaceAll("(\\d[HMS])", "$1 ")
                .toLowerCase();
    }

    private void writeDataToFile(@Nonnull String fileName, @Nullable String fileContent) {
        if (StringUtils.isEmpty(fileContent)) {
            return;
        }
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(fileName));
            writer.write(fileContent);
        } catch (IOException e) {
            log.error("Cannot write the data to the file " + fileName);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    log.error("Cannot close output stream while writing the data in the file" + fileName);
                }
            }
        }
    }

    @Nonnull
    protected Map<String, String> initializeSparqlQueries() {
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(SchemaConstants.GLOBAL_SPARQL_QUERIES_PATH);
            Properties queries = new Properties();
            queries.load(inputStream);
            return queries.entrySet().stream().collect(
                    Collectors.toMap(
                            e -> String.valueOf(e.getKey()),
                            e -> String.valueOf(e.getValue()),
                            (prev, next) -> next, HashMap::new
                    ));
        } catch (IOException e) {
            log.error(String.format("Cannot read SPARQL queries from the config file %s. System defined queries will be used.", GLOBAL_SPARQL_QUERIES_PATH));
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                log.error("Cannot close input stream when reading SPARQL queries from " + GLOBAL_SPARQL_QUERIES_PATH);
            }
        }
        return new HashMap<>();
    }

}

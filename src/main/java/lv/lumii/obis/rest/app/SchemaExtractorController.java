package lv.lumii.obis.rest.app;

import com.google.gson.Gson;
import io.swagger.annotations.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.enhancer.OWLOntologyEnhancer;
import lv.lumii.obis.schema.services.enhancer.dto.OWLOntologyEnhancerRequest;
import lv.lumii.obis.schema.services.extractor.SchemaExtractorManyQueriesWithDirectProperties;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import lv.lumii.obis.schema.services.extractor.v2.SchemaExtractor;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.extractor.SchemaExtractorFewQueries;
import lv.lumii.obis.schema.services.extractor.SchemaExtractorManyQueries;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.services.*;
import lv.lumii.obis.schema.services.reader.OWLOntologyReader;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;

import javax.ws.rs.core.MediaType;
import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

/**
 * REST Controller to process Schema Extractor web requests.
 */
@RestController
@RequestMapping("/schema-extractor-rest")
@Api(value = "SchemaExtractorServices", tags = "services")
@Slf4j
@Setter
public class SchemaExtractorController {

    private static final String SCHEMA_EXTRACT_MESSAGE_START = "Starting to read schema from the endpoint with parameters %s";
    private static final String SCHEMA_EXTRACT_MESSAGE_END = "Completed JSON schema extraction in %s from the specified endpoint with parameters %s";

    private static final String SCHEMA_READ_FILE_MESSAGE_START = "Request %s - Starting to read Schema JSON from the OWL file [%s]";
    private static final String SCHEMA_READ_FILE_MESSAGE_END = "Request %s - Completed to read Schema JSON from the OWL file [%s] in %s";
    private static final String SCHEMA_READ_PREFIX_FILE_MESSAGE_START = "Request %s - Starting to read Prefixes from the JSON file [%s]";
    private static final String SCHEMA_READ_PREFIX_FILE_MESSAGE_END = "Request %s - Completed to read Prefixes from the JSON file [%s]";
    private static final String SCHEMA_ENHANCE_MESSAGE_START = "Request %s - Starting to enhance Schema with SPARQL endpoint data [%s]";
    private static final String SCHEMA_ENHANCE_MESSAGE_END = "Request %s - Completed to enhance Schema with SPARQL endpoint data [%s] in %s";
    private static final String SCHEMA_READ_FILE_MESSAGE_ERROR = "Request %s - Cannot read Schema JSON from the specified OWL file [%s]";

    @Autowired
    private SchemaExtractor schemaExtractor;
    @Autowired
    private SchemaExtractorFewQueries schemaExtractorFewQueries;
    @Autowired
    private SchemaExtractorManyQueries schemaExtractorManyQueries;
    @Autowired
    private SchemaExtractorManyQueriesWithDirectProperties schemaExtractorManyQueriesWithDirectProperties;
    @Autowired
    private OWLOntologyReader owlOntologyReader;
    @Autowired
    private OWLOntologyEnhancer owlOntologyEnhancer;
    @Autowired
    private JsonSchemaService jsonSchemaService;
    @Autowired
    private SchemaExtractorRequestBuilder requestBuilder;


    @RequestMapping(value = "/v2/endpoint/buildFullSchema", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build full schema model (version 2)",
            consumes = MediaType.APPLICATION_JSON,
            produces = MediaType.APPLICATION_JSON,
            response = lv.lumii.obis.schema.model.v2.Schema.class
    )
    @SuppressWarnings("unused")
    public String buildFullSchemaFromEndpointV2(@Validated @ModelAttribute @NotNull SchemaExtractorRequestNew request,
                                                @RequestParam(value ="namespacePrefixFile", required=false)
                                                @ApiParam(access = "99", value = "Valid JSON file with predefined namespaces") MultipartFile namespacePrefixFile) {

        SchemaExtractorRequestDto requestDto = requestBuilder.buildRequest(request);

        String requestJson = new Gson().toJson(request);
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestJson));
        LocalDateTime startTime = LocalDateTime.now();

        // 1. Read prefixes from the external JSON file
        if (namespacePrefixFile != null && !namespacePrefixFile.isEmpty()) {
            log.info(String.format(SCHEMA_READ_PREFIX_FILE_MESSAGE_START, requestDto.getCorrelationId(), namespacePrefixFile.getOriginalFilename()));
            try {
                requestBuilder.applyPredefinedNamespaces(requestDto, namespacePrefixFile.getInputStream());
            } catch (IOException e) {
                log.error(String.format(SCHEMA_READ_PREFIX_FILE_MESSAGE_END, requestDto.getCorrelationId(), namespacePrefixFile.getOriginalFilename()));
            }
        }

        // 2. Build the schema from the endpoint
        lv.lumii.obis.schema.model.v2.Schema schema = schemaExtractor.extractSchema(requestDto);

        LocalDateTime endTime = LocalDateTime.now();
        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startTime, endTime), requestJson));

        return jsonSchemaService.getJsonSchemaString(schema);
    }


    @RequestMapping(value = "/endpoint/buildClasses", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build only classes model",
            consumes = MediaType.APPLICATION_JSON,
            produces = MediaType.APPLICATION_JSON,
            response = Schema.class
    )
    @SuppressWarnings("unused")
    public String buildClassesFromEndpoint(@Validated @ModelAttribute @NotNull SchemaExtractorRequestOld request) {
        SchemaExtractorRequestDto requestDto = requestBuilder.buildRequest(request);
        String requestJson = new Gson().toJson(request);

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestJson));

        LocalDateTime startTime = LocalDateTime.now();
        Schema schema;
        if (isTrue(SchemaExtractorRequestOld.ExtractionVersion.fewComplexQueries.equals(request.getVersion()))) {
            schema = schemaExtractorFewQueries.extractClasses(requestDto);
        } else if (isTrue(SchemaExtractorRequestOld.ExtractionVersion.manySmallQueriesWithDirectProperties.equals(request.getVersion()))) {
            schema = schemaExtractorManyQueriesWithDirectProperties.extractClasses(requestDto);
        } else {
            schema = schemaExtractorManyQueries.extractClasses(requestDto);
        }
        LocalDateTime endTime = LocalDateTime.now();

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startTime, endTime), requestJson));

        return jsonSchemaService.getJsonSchemaString(schema);
    }

    @RequestMapping(value = "/endpoint/buildFullSchema", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build full schema model",
            consumes = MediaType.APPLICATION_JSON,
            produces = MediaType.APPLICATION_JSON,
            response = Schema.class
    )
    @SuppressWarnings("unused")
    public String buildFullSchemaFromEndpoint(@Validated @ModelAttribute @NotNull SchemaExtractorRequestOld request) {
        SchemaExtractorRequestDto requestDto = requestBuilder.buildRequest(request);
        String requestJson = new Gson().toJson(request);

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestJson));

        LocalDateTime startTime = LocalDateTime.now();
        Schema schema;
        if (isTrue(SchemaExtractorRequestOld.ExtractionVersion.fewComplexQueries.equals(request.getVersion()))) {
            schema = schemaExtractorFewQueries.extractSchema(requestDto);
        } else if (isTrue(SchemaExtractorRequestOld.ExtractionVersion.manySmallQueriesWithDirectProperties.equals(request.getVersion()))) {
            schema = schemaExtractorManyQueriesWithDirectProperties.extractSchema(requestDto);
        } else {
            schema = schemaExtractorManyQueries.extractSchema(requestDto);
        }
        LocalDateTime endTime = LocalDateTime.now();

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startTime, endTime), requestJson));

        return jsonSchemaService.getJsonSchemaString(schema);
    }

    @RequestMapping(value = "/owlFile/buildFullSchema", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze schema from OWL ontology file and then enhance with data from SPARQL endpoint (if provided)",
            consumes = MediaType.MULTIPART_FORM_DATA,
            produces = MediaType.APPLICATION_JSON,
            response = Schema.class
    )
    @SuppressWarnings("unused")
    public String buildFullSchemaFromOwl(@RequestParam("file") @ApiParam(access = "1", value = "Upload OWL file", required = true) MultipartFile file,
                                         @Validated @ModelAttribute @NotNull OWLOntologyReaderRequest readerRequest,
                                         @Validated @ModelAttribute @NotNull OWLOntologyEnhancerRequest enhancerRequest) {
        String correlationId = requestBuilder.generateCorrelationId();

        // READ OWL ontology file and convert to Schema JSON format
        LocalDateTime startTime = LocalDateTime.now();
        log.info(String.format(SCHEMA_READ_FILE_MESSAGE_START, correlationId, file.getOriginalFilename()));
        InputStream inputStream;
        try {
            inputStream = file.getInputStream();
        } catch (IOException e) {
            String error = String.format(SCHEMA_READ_FILE_MESSAGE_ERROR, correlationId, file.getOriginalFilename());
            log.error(error);
            throw new RuntimeException(error, e);
        }

        Schema schema = owlOntologyReader.readOWLOntology(inputStream, readerRequest);
        LocalDateTime endTime = LocalDateTime.now();
        log.info(String.format(SCHEMA_READ_FILE_MESSAGE_END, correlationId, file.getOriginalFilename(), calculateExecutionTime(startTime, endTime)));

        // Enhance Schema JSON with data from SPARQL endpoint
        if (StringUtils.isNotEmpty(enhancerRequest.getEndpointUrl())) {
            log.info(String.format(SCHEMA_ENHANCE_MESSAGE_START, correlationId, enhancerRequest.getEndpointUrl()));
            startTime = LocalDateTime.now();
            enhancerRequest.setCorrelationId(correlationId);
            enhancerRequest.setExcludedNamespaces(readerRequest.getExcludedNamespaces());
            schema = owlOntologyEnhancer.enhanceSchema(schema, enhancerRequest);
            endTime = LocalDateTime.now();
            log.info(String.format(SCHEMA_ENHANCE_MESSAGE_END, correlationId, enhancerRequest.getEndpointUrl(), calculateExecutionTime(startTime, endTime)));
        }

        return jsonSchemaService.getJsonSchemaString(schema);
    }

    private String calculateExecutionTime(@Nonnull LocalDateTime startLocalDateTime, @Nonnull LocalDateTime endLocalDateTime) {
        return Duration.between(startLocalDateTime, endLocalDateTime).toString().substring(2)
                .replaceAll("(\\d[HMS])", "$1 ")
                .toLowerCase();
    }

}

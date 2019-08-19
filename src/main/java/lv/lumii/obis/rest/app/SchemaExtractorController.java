package lv.lumii.obis.rest.app;

import com.google.gson.Gson;
import io.swagger.annotations.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.services.*;
import lv.lumii.obis.schema.services.dto.SchemaExtractorRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;

import javax.ws.rs.core.MediaType;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

/**
 * REST Controller to process Schema Extractor web requests.
 */
@RestController
@RequestMapping("/schema-extractor-rest")
@Api(value = "SchemaExtractorServices", tags = "services")
@Slf4j
public class SchemaExtractorController {

    private static final String RNG_ALGORITHM = "SHA1PRNG";

    private static final String SCHEMA_EXTRACT_MESSAGE_START = "Starting to read schema from the endpoint with parameters %s";
    private static final String SCHEMA_EXTRACT_MESSAGE_END = "Completed JSON schema extraction in %s from the specified endpoint with parameters %s";

    private static final String SCHEMA_READ_FILE_MESSAGE_START = "Starting to read schema from the file [%s]";
    private static final String SCHEMA_READ_FILE_MESSAGE_END = "Completed to read schema from the file [%s]";
    private static final String SCHEMA_READ_FILE_MESSAGE_ERROR = "Cannot read Schema JSON from the specified OWL file [%s]";

    private static volatile SecureRandom secureRandom;

    @Autowired @Setter @Getter
    private SchemaExtractorFewQueries schemaExtractorFewQueries;
    @Autowired @Setter @Getter
    private SchemaExtractorManyQueries schemaExtractorManyQueries;
    @Autowired @Setter @Getter
    private OwlOntologyReader owlOntologyReader;
    @Autowired @Setter @Getter
    private JsonSchemaService jsonSchemaService;

    @RequestMapping(value = "/endpoint/buildClasses", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build only classes model",
            consumes = MediaType.APPLICATION_JSON,
            produces = MediaType.APPLICATION_JSON,
            response = Schema.class
    )
    public String buildClassesFromEndpoint(@Validated @ModelAttribute @NotNull SchemaExtractorRequest request) {
        request.setCorrelationId(generateCorrelationId());
        String requestJson = new Gson().toJson(request);

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestJson));

        Date startDate = new Date();
        Schema schema;
        if(isTrue(SchemaExtractorRequest.ExtractionVersion.fewComplexQueries.equals(request.getVersion()))){
            schema = schemaExtractorFewQueries.extractClasses(request);
        } else {
            schema = schemaExtractorManyQueries.extractClasses(request);
        }
        Date endDate = new Date();

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startDate, endDate), requestJson));

        return jsonSchemaService.getJsonSchemaString(schema);
    }

    @RequestMapping(value = "/endpoint/buildFullSchema", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze data from SPARQL endpoint and build full schema model",
            consumes = MediaType.APPLICATION_JSON,
            produces = MediaType.APPLICATION_JSON,
            response = Schema.class
    )
    public String buildFullSchemaFromEndpoint(@Validated @ModelAttribute @NotNull SchemaExtractorRequest request) {
        request.setCorrelationId(generateCorrelationId());
        String requestJson = new Gson().toJson(request);

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, requestJson));

        Date startDate = new Date();
        Schema schema;
        if(isTrue(SchemaExtractorRequest.ExtractionVersion.fewComplexQueries.equals(request.getVersion()))){
            schema = schemaExtractorFewQueries.extractSchema(request);
        } else {
            schema = schemaExtractorManyQueries.extractSchema(request);
        }
        Date endDate = new Date();

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, calculateExecutionTime(startDate, endDate), requestJson));

        return jsonSchemaService.getJsonSchemaString(schema);
    }

    @RequestMapping(value = "/owlFile/buildFullSchema", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Extract and analyze schema from OWL ontology file",
            consumes = MediaType.MULTIPART_FORM_DATA,
            produces = MediaType.APPLICATION_JSON,
            response = Schema.class
    )
    public String buildFullSchemaFromOwl(@RequestPart("file") @ApiParam(value="Upload OWL file", required=true) MultipartFile file) {

        log.info(String.format(SCHEMA_READ_FILE_MESSAGE_START, file.getOriginalFilename()));

        InputStream inputStream;
        try{
            inputStream = file.getInputStream();
        } catch (IOException e){
            String error = String.format(SCHEMA_READ_FILE_MESSAGE_ERROR, file.getOriginalFilename());
            log.error(error);
            throw new RuntimeException(error, e);
        }

        Schema schema = owlOntologyReader.readOwlOntology(inputStream);

        log.info(String.format(SCHEMA_READ_FILE_MESSAGE_END, file.getOriginalFilename()));

        return jsonSchemaService.getJsonSchemaString(schema);
    }

    private static String generateCorrelationId() {
        try {
            if(secureRandom == null) {
                secureRandom = SecureRandom.getInstance(RNG_ALGORITHM);
            }
            return Long.toString(Math.abs(secureRandom.nextLong()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating unique correlation id for SchemaExtractor request: ", e);
        }
    }

    private String calculateExecutionTime(@Nonnull Date startDate, @Nonnull Date endDate){
        String timeUnit;
        long timeDiff;
        long diffInMilliseconds = Math.abs(startDate.getTime() - endDate.getTime());
        if(diffInMilliseconds > 60000){
            timeUnit = " min";
            timeDiff = TimeUnit.MINUTES.convert(diffInMilliseconds, TimeUnit.MILLISECONDS);
        } else {
            timeUnit = " s";
            timeDiff = TimeUnit.SECONDS.convert(diffInMilliseconds, TimeUnit.MILLISECONDS);
        }
        return timeDiff + timeUnit;
    }

}

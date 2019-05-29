package lv.lumii.obis.rest.app;

import com.google.gson.Gson;
import io.swagger.annotations.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.OwlOntologyReader;
import lv.lumii.obis.schema.services.SchemaExtractor;
import lv.lumii.obis.schema.services.dto.SchemaExtractorRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotNull;

import javax.ws.rs.core.MediaType;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

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
    private static final String SCHEMA_EXTRACT_MESSAGE_END = "Completed JSON schema extraction from the specified endpoint with parameters %s";

    private static final String SCHEMA_READ_FILE_MESSAGE_START = "Starting to read schema from the file [%s]";
    private static final String SCHEMA_READ_FILE_MESSAGE_END = "Completed to read schema from the file [%s]";
    private static final String SCHEMA_READ_FILE_MESSAGE_ERROR = "Cannot read Schema JSON from the specified OWL file [%s]";

    private static volatile SecureRandom secureRandom;

    @Autowired @Setter @Getter
    private SchemaExtractor schemaExtractor;
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

        Schema schema = schemaExtractor.extractClasses(request);

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, requestJson));

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

        Schema schema = schemaExtractor.extractSchema(request);

        log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, requestJson));

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

}

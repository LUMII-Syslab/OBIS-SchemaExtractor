package lv.lumii.obis.rest;

import java.io.InputStream;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.SchemaUtil;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.OwlOntologyReader;
import lv.lumii.obis.schema.services.SchemaExtractor;
import lv.lumii.obis.schema.model.Schema;

@Path("/")
@Slf4j
public class SchemaExtractorService {

	private static final String LOG_MODE_ENABLED = "enabled";

	private static final String SCHEMA_EXTRACT_MESSAGE_START = "Starting to read schema from the endpoint [%s] graph [%s] with mode [%s] and log [%s]";
	private static final String SCHEMA_EXTRACT_MESSAGE_END = "Completed JSON schema extraction from the endpoint [%s] graph [%s] with mode [%s] and log [%s]";
	private static final String SCHEMA_EXTRACT_MESSAGE_ERROR = "Cannot extract JSON schema from the endpoint [%s] graph [%s] with mode [%s] and log [%s]";
	private static final String SCHEMA_EXTRACT_MESSAGE_INCOMPLETE = "Wrong/incomplete input parameters. Please provide at least SPARQL endpoint as URL query parameter";

	private static final String SCHEMA_READ_FILE_MESSAGE_START = "Starting to read schema from the file [%s]";
	private static final String SCHEMA_READ_FILE_MESSAGE_END = "Completed to read schema from the file [%s]";
	private static final String SCHEMA_READ_FILE_MESSAGE_ERROR = "Cannot read Schema JSON from the specified OWL file [%s]";

	@GET
	@Path("/schema")
	@Produces({MediaType.APPLICATION_JSON})
	public String extractSchema(
			@QueryParam("endpoint") String endpoint,
			@QueryParam("graph") @DefaultValue("") String graphName,
			@QueryParam("mode") @DefaultValue("") String mode,
			@QueryParam("log") @DefaultValue("disabled") String logMode){


		if(SchemaUtil.isEmpty(endpoint)){
			log.error(SCHEMA_EXTRACT_MESSAGE_INCOMPLETE);
			return SCHEMA_EXTRACT_MESSAGE_INCOMPLETE;
		}

		log.info(String.format(SCHEMA_EXTRACT_MESSAGE_START, endpoint, graphName, mode, logMode));

		SchemaExtractor extractor = new SchemaExtractor();
		Schema schema = extractor.extractSchema(endpoint, graphName, mode, LOG_MODE_ENABLED.equalsIgnoreCase(logMode));

		String jsonString = JsonSchemaService.getJsonSchemaString(schema);

		if(!SchemaUtil.isEmpty(jsonString)){
			log.info(String.format(SCHEMA_EXTRACT_MESSAGE_END, endpoint, graphName, mode, logMode));
			return jsonString;
		} else {
			String error = String.format(SCHEMA_EXTRACT_MESSAGE_ERROR, endpoint, graphName, mode, logMode);
			log.error(error);
			return error;
		}
	}
	
	@POST
	@Path("/schemaFromOwl")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces({MediaType.APPLICATION_JSON})
	public String extractSchemaFromOwl(
			@FormDataParam("file") InputStream uploadedInputStream,
			@FormDataParam("file") FormDataContentDisposition fileDetail){

		log.info(String.format(SCHEMA_READ_FILE_MESSAGE_START, fileDetail.getFileName()));

		OwlOntologyReader reader = new OwlOntologyReader();
		Schema schema = reader.readOwlOntology(uploadedInputStream);

		String jsonString = JsonSchemaService.getJsonSchemaString(schema);

		if(!SchemaUtil.isEmpty(jsonString)){
			log.info(String.format(SCHEMA_READ_FILE_MESSAGE_END, fileDetail.getFileName()));
			return jsonString;
		} else {
			String error = String.format(SCHEMA_READ_FILE_MESSAGE_ERROR, fileDetail.getFileName());
			log.error(error);
			return error;
		}
	}

}

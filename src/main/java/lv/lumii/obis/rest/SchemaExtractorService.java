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

import lv.lumii.obis.schema.services.SchemaUtil;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import lv.lumii.obis.schema.services.JsonSchemaService;
import lv.lumii.obis.schema.services.OwlOntologyReader;
import lv.lumii.obis.schema.services.SchemaExtractor;
import lv.lumii.obis.schema.model.Schema;

@Path("/")
public class SchemaExtractorService {

	@GET
	@Path("/schema")
	@Produces({MediaType.APPLICATION_JSON})
	public String extractSchema(
			@QueryParam("endpoint") String endpoint,
			@QueryParam("graph") @DefaultValue("") String graphName,
			@QueryParam("mode") @DefaultValue("") String mode){


		if(SchemaUtil.isEmpty(endpoint)){
			return "Error = please provide SPARQL endpoint and graph name as URL query parameters \"endpoint\" and \"graph\"."
					+ "\nExample - /schema?endpoint=http://localhost:8890/sparql&graph=MiniUniv";
		}
		SchemaExtractor extractor = new SchemaExtractor();
		Schema schema = extractor.extractSchema(endpoint, graphName, mode);

		String jsonString = JsonSchemaService.getJsonSchemaString(schema);

		if(!SchemaUtil.isEmpty(jsonString)){
			return jsonString;
		} else {
			return "Cannot read Schema JSON from the specified endpoint:"
					+ "\nEndpoint = " + endpoint
					+ "\nGraph = " + graphName;
		}
	}
	
	@POST
	@Path("/schemaFromOwl")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces({MediaType.APPLICATION_JSON})
	public String extractSchemaFromOwl(
			@FormDataParam("file") InputStream uploadedInputStream,
			@FormDataParam("file") FormDataContentDisposition fileDetail){

		OwlOntologyReader reader = new OwlOntologyReader();
		Schema schema = reader.readOwlOntology(uploadedInputStream);

		String jsonString = JsonSchemaService.getJsonSchemaString(schema);

		if(!SchemaUtil.isEmpty(jsonString)){
			return jsonString;
		} else {
			return "Cannot read Schema JSON from the specified OWL file: " + fileDetail.getFileName();
		}
	}

}

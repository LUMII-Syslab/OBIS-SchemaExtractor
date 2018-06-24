package lv.lumii.obis.schema.services;

import java.io.File;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import lv.lumii.obis.schema.model.Schema;

public class JsonSchemaService {

	private JsonSchemaService() {}
	
	public static void writeJsonSchema(Schema ontology, String fileName){
		ObjectMapper mapper = new ObjectMapper();	
		mapper.setSerializationInclusion(Include.NON_NULL);
		try {		    
			String jsonInString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ontology);
			
			FileUtils.writeStringToFile(new File(fileName), jsonInString, Charset.defaultCharset());
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static String getJsonSchemaString(Schema ontology){
		ObjectMapper mapper = new ObjectMapper();	
		mapper.setSerializationInclusion(Include.NON_NULL);
		try {		    
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ontology);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static Schema readJsonSchema(String fileName){
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);  
		try {		    
			return mapper.readValue(new File(fileName), Schema.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}

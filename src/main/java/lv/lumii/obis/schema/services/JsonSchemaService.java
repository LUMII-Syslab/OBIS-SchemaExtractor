package lv.lumii.obis.schema.services;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

import lv.lumii.obis.schema.model.Schema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class JsonSchemaService {

	private JsonSchemaService() {}

	@Nullable
	public static String getJsonSchemaString(@Nonnull Schema ontology){
		ObjectMapper mapper = new ObjectMapper();	
		mapper.setSerializationInclusion(Include.NON_NULL);
		try {		    
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ontology);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}

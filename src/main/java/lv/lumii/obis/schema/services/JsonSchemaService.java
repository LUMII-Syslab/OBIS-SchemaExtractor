package lv.lumii.obis.schema.services;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

import lv.lumii.obis.schema.model.Schema;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Service
public class JsonSchemaService {

	@Nullable
	public String getJsonSchemaString(@Nonnull Schema schema){
		ObjectMapper mapper = new ObjectMapper();	
		mapper.setSerializationInclusion(Include.NON_NULL);
		try {		    
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}

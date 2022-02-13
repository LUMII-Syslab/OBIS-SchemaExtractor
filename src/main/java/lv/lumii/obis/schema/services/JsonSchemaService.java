package lv.lumii.obis.schema.services;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

import lv.lumii.obis.schema.model.v1.Schema;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;

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

	@Nullable
	public String getJsonSchemaString(@Nonnull lv.lumii.obis.schema.model.v2.Schema schema){
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		try {
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	@Nullable
	public <T> T getObjectFromJsonStream(@Nonnull InputStream inputStream, Class<T> objectToConvert){
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.readValue(inputStream, objectToConvert);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}

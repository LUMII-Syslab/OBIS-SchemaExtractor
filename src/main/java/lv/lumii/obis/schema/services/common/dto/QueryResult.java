package lv.lumii.obis.schema.services.common.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class QueryResult {
	
	private Map<String, String> properties;
	
	public void add(String key, String value){
		getProperties().put(key, value);
	}
	
	public String get(String key){
		return getProperties().get(key);
	}
	
	public Map<String, String> getProperties() {
		if(properties == null){
			properties = new LinkedHashMap<>();
		}
		return properties;
	}

}

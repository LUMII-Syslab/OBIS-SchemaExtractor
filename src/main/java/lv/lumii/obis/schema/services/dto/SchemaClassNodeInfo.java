package lv.lumii.obis.schema.services.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaClassNodeInfo {
	
	private String className;
	private Integer instanceCount;
	private List<String> neighbors;
	
	public List<String> getNeighbors() {
		if(neighbors == null){
			neighbors = new ArrayList<>();
		}
		return neighbors;
	}

}

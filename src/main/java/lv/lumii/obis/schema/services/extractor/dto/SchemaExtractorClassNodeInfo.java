package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaExtractorClassNodeInfo {
	
	private String className;
	private Long instanceCount;
	private List<String> neighbors;

	public SchemaExtractorClassNodeInfo() {
	}

	public SchemaExtractorClassNodeInfo(String className, Long instanceCount) {
		this.className = className;
		this.instanceCount = instanceCount;
	}

	public List<String> getNeighbors() {
		if(neighbors == null){
			neighbors = new ArrayList<>();
		}
		return neighbors;
	}

}

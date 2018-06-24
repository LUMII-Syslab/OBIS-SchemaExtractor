package lv.lumii.obis.schema.services.dto;

import java.util.ArrayList;
import java.util.List;

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

	public String getClassName() {
		return className;
	}

	public void setClassName(String className) {
		this.className = className;
	}

	public Integer getInstanceCount() {
		return instanceCount;
	}

	public void setInstanceCount(Integer instanceCount) {
		this.instanceCount = instanceCount;
	}

	public void setNeighbors(List<String> neighbors) {
		this.neighbors = neighbors;
	}
}

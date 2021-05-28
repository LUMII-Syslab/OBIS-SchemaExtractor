package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaExtractorClassNodeInfo {
	
	private String className;
	private Long tripleCount;
	private Long objectTripleCount;
	private Boolean isClosedDomain;
	private Boolean isClosedRange;
	private Integer minCardinality;
	private Integer maxCardinality;
	private Integer minInverseCardinality;
	private Integer maxInverseCardinality;
	private List<String> neighbors;
	private Integer importanceIndex;
	private List<SchemaExtractorDataTypeInfo> dataTypes;

	public SchemaExtractorClassNodeInfo() {
	}

	public SchemaExtractorClassNodeInfo(String className) {
		this.className = className;
	}

	public SchemaExtractorClassNodeInfo(String className, Long tripleCount) {
		this.className = className;
		this.tripleCount = tripleCount;
	}

	public List<String> getNeighbors() {
		if(neighbors == null){
			neighbors = new ArrayList<>();
		}
		return neighbors;
	}

	@Nonnull
	public List<SchemaExtractorDataTypeInfo> getDataTypes() {
		if (dataTypes == null) {
			dataTypes = new ArrayList<>();
		}
		return dataTypes;
	}

}

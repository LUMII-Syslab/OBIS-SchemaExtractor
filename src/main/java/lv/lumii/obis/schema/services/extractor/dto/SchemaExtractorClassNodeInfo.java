package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorIntersectionClassDto;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaExtractorClassNodeInfo {
	
	private String className;
	private Long tripleCount;
	private Long tripleCountBase;
	private Long dataTripleCount;
	private Long objectTripleCount;
	private Boolean isClosedDomain;
	private Boolean isClosedRange;
	private Boolean isPrincipal;
	private Boolean hasDomainInClassPair;
	private Boolean hasRangeInClassPair;
	private Integer minCardinality;
	private Integer maxCardinality;
	private Integer minInverseCardinality;
	private Integer maxInverseCardinality;
	private List<SchemaExtractorIntersectionClassDto> neighbors;
	private Integer importanceIndex;
	private List<SchemaExtractorDataTypeInfo> dataTypes;

	private String classificationProperty;
	private Boolean isLiteral;

	public SchemaExtractorClassNodeInfo() {
	}

	public SchemaExtractorClassNodeInfo(String className) {
		this.className = className;
	}

	public SchemaExtractorClassNodeInfo(String className, String classificationProperty, Boolean isLiteral) {
		this.className = className;
		this.classificationProperty = classificationProperty;
		this.isLiteral = isLiteral;
	}

	public SchemaExtractorClassNodeInfo(String className, Long tripleCount, String classificationProperty, Boolean isLiteral) {
		this.className = className;
		this.tripleCount = tripleCount;
		this.classificationProperty = classificationProperty;
		this.isLiteral = isLiteral;
	}

	public List<SchemaExtractorIntersectionClassDto> getNeighbors() {
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

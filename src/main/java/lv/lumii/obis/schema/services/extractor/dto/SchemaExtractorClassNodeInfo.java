package lv.lumii.obis.schema.services.extractor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.v2.dto.SchemaExtractorIntersectionClassDto;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaExtractorClassNodeInfo {
	
	private String className;
	private Long tripleCount;
	private Long tripleCountBase;
	private Long dataTripleCount;
	// Preserve positive literal-existence evidence without publishing an unavailable count.
	@JsonIgnore
	private boolean literalObjectsKnown;
	private Long objectTripleCount;
	private BigDecimal objectTripleRatio;
	@JsonIgnore
	private Boolean literalObjectsExistence;
	@JsonIgnore
	private Boolean nonLiteralObjectsExistence;
	private Long distinctSubjectsCount;
	private Long distinctObjectsCount;
	private Boolean isClosedSource;
	private Integer closedSourceAssertedSize;
	private Boolean isClosedTarget;
	private Integer closedTargetAssertedSize;
	private Boolean isPrincipal;
	private Integer principalAssertedSize;
	private Boolean hasDomainInClassPair;
	private Boolean hasRangeInClassPair;
	private Integer minCardinality;
	private Integer minCardinalityAssertionSize;
	private Integer maxCardinality;
	private Integer maxCardinalityAssertionSize;
	private Integer minInverseCardinality;
	private Integer minInverseCardinalityAssertionSize;
	private Integer maxInverseCardinality;
	private Integer maxInverseCardinalityAssertionSize;
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

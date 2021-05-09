package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class ClassPair {
	
	@JsonProperty("SourceClass")
	private String sourceClass;

	@JsonProperty("TargetClass")
	private String targetClass;

	private Long tripleCount;

	private Integer sourceImportanceIndex;

	private Integer targetImportanceIndex;
	
	public ClassPair(String sourceClass, String targetClass, Long tripleCount, Integer sourceImportanceIndex, Integer targetImportanceIndex) {
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
		this.tripleCount = tripleCount;
		this.sourceImportanceIndex = sourceImportanceIndex;
		this.targetImportanceIndex = targetImportanceIndex;
	}
}

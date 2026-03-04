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

	private Boolean isPrincipalSource;
	private Integer sourcePrincipalAssertedSize;

	private Integer sourceImportanceIndex;

	private Boolean isPrincipalTarget;
	private Integer targetPrincipalAssertedSize;

	private Integer targetImportanceIndex;
	
	public ClassPair(String sourceClass, String targetClass, Long tripleCount, Boolean isPrincipalSource, Integer sourcePrincipalAssertedSize,
					 Integer sourceImportanceIndex, Boolean isPrincipalTarget, Integer targetPrincipalAssertedSize, Integer targetImportanceIndex) {
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
		this.tripleCount = tripleCount;
		this.isPrincipalSource = isPrincipalSource;
		this.sourcePrincipalAssertedSize = sourcePrincipalAssertedSize;
		this.sourceImportanceIndex = sourceImportanceIndex;
		this.isPrincipalTarget = isPrincipalTarget;
		this.targetPrincipalAssertedSize = targetPrincipalAssertedSize;
		this.targetImportanceIndex = targetImportanceIndex;
	}
}

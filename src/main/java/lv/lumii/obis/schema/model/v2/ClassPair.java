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

	private Boolean sourceImportanceIndex;

	private Boolean targetImportanceIndex;
	
	public ClassPair() {}
	
	public ClassPair(String sourceClass, String targetClass) {
		super();
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
	}

	public ClassPair(String sourceClass, String targetClass, Long tripleCount) {
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
		this.tripleCount = tripleCount;
	}
}

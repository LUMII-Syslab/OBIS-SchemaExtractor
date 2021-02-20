package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class ClassPair {
	
	@JsonProperty("SourceClass")
	private String sourceClass;

	@JsonProperty("TargetClass")
	private String targetClass;

	private Long instanceCount;
	
	public ClassPair() {}
	
	public ClassPair(String sourceClass, String targetClass) {
		super();
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
	}

	public ClassPair(String sourceClass, String targetClass, Long instanceCount) {
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
		this.instanceCount = instanceCount;
	}
}

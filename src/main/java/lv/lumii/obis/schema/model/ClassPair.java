package lv.lumii.obis.schema.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ClassPair {
	
	@JsonProperty("SourceClass")
	private String sourceClass;

	@JsonProperty("TargetClass")
	private String targetClass;
	
	public ClassPair() {}
	
	public ClassPair(String sourceClass, String targetClass) {
		super();
		this.sourceClass = sourceClass;
		this.targetClass = targetClass;
	}

	public String getSourceClass() {
		return sourceClass;
	}

	public void setSourceClass(String sourceClass) {
		this.sourceClass = sourceClass;
	}

	public String getTargetClass() {
		return targetClass;
	}

	public void setTargetClass(String targetClass) {
		this.targetClass = targetClass;
	}

}

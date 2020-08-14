package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaExtractorPropertyNodeInfo {

	private Boolean isObjectProperty = Boolean.FALSE;
	private String dataType;
	private Integer minCardinality;
	private Integer maxCardinality;
	private List<SchemaExtractorDomainRangeInfo> domainRangePairs;
	private List<SchemaExtractorClassNodeInfo> domainClasses;

	public List<SchemaExtractorDomainRangeInfo> getDomainRangePairs() {
		if(domainRangePairs == null){
			domainRangePairs = new ArrayList<>();
		}
		return domainRangePairs;
	}

	public List<SchemaExtractorClassNodeInfo> getDomainClasses() {
		if(domainClasses == null){
			domainClasses = new ArrayList<>();
		}
		return domainClasses;
	}

}

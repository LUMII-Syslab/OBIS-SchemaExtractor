package lv.lumii.obis.schema.services.extractor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter @Getter
public class SchemaPropertyNodeInfo {

	private Boolean isObjectProperty = Boolean.FALSE;
	private String dataType;
	private Integer minCardinality;
	private Integer maxCardinality;
	private List<SchemaDomainRangeInfo> domainRangePairs;
	private List<SchemaClassNodeInfo> domainClasses;

	public List<SchemaDomainRangeInfo> getDomainRangePairs() {
		if(domainRangePairs == null){
			domainRangePairs = new ArrayList<>();
		}
		return domainRangePairs;
	}

	public List<SchemaClassNodeInfo> getDomainClasses() {
		if(domainClasses == null){
			domainClasses = new ArrayList<>();
		}
		return domainClasses;
	}

}
package lv.lumii.obis.schema.services.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaCardinalityInfo {
	
	public enum CardinalityType {
		MIN_CARDINALITY, MAX_CARDINALITY, EXACT_CARDINALITY
	}

	private Integer cardinality;
	private CardinalityType cardinalityType;

    public SchemaCardinalityInfo(CardinalityType cardinalityType, Integer cardinality) {
		super();
		this.cardinalityType = cardinalityType;
		this.cardinality = cardinality;
	}

}

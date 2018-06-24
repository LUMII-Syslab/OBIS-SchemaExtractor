package lv.lumii.obis.schema.services.dto;

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

	public Integer getCardinality() {
		return cardinality;
	}

	public void setCardinality(Integer cardinality) {
		this.cardinality = cardinality;
	}

	public CardinalityType getCardinalityType() {
		return cardinalityType;
	}

	public void setCardinalityType(CardinalityType cardinalityType) {
		this.cardinalityType = cardinalityType;
	}
}

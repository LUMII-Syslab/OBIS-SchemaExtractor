package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.SchemaProperty;
import lv.lumii.obis.schema.services.common.dto.SchemaCardinalityInfo;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

public abstract class OWLPropertyProcessor implements OWLElementProcessor {

    protected void setCardinalities(@Nonnull SchemaProperty property, @Nonnull String propertyName, @Nonnull Map<String, List<SchemaCardinalityInfo>> cardinalityMap) {
        if (!cardinalityMap.containsKey(propertyName)) {
            return;
        }

        Integer exactCardinality = cardinalityMap.get(propertyName).stream()
                .filter(c -> SchemaCardinalityInfo.CardinalityType.EXACT_CARDINALITY.equals(c.getCardinalityType()))
                .findFirst().orElse(new SchemaCardinalityInfo(SchemaCardinalityInfo.CardinalityType.EXACT_CARDINALITY, null))
                .getCardinality();
        if (exactCardinality != null && exactCardinality > 0) {
            property.setMinCardinality(exactCardinality);
            property.setMaxCardinality(exactCardinality);
            return;
        }

        Integer minCardinality = cardinalityMap.get(propertyName).stream()
                .filter(c -> SchemaCardinalityInfo.CardinalityType.MIN_CARDINALITY.equals(c.getCardinalityType()))
                .findFirst().orElse(new SchemaCardinalityInfo(SchemaCardinalityInfo.CardinalityType.MIN_CARDINALITY, null))
                .getCardinality();
        if (minCardinality != null) {
            property.setMinCardinality(minCardinality);
        }

        Integer maxCardinality = cardinalityMap.get(propertyName).stream()
                .filter(c -> SchemaCardinalityInfo.CardinalityType.MAX_CARDINALITY.equals(c.getCardinalityType()))
                .findFirst().orElse(new SchemaCardinalityInfo(SchemaCardinalityInfo.CardinalityType.MAX_CARDINALITY, null))
                .getCardinality();
        if (maxCardinality != null) {
            property.setMaxCardinality(maxCardinality);
        }

    }

}

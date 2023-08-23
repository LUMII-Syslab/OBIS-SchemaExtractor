package lv.lumii.obis.schema.model.v2;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaParameter {

    public static final String PARAM_NAME_ENDPOINT = "endpointUrl";
    public static final String PARAM_NAME_GRAPH_NAME = "graphName";
    public static final String PARAM_NAME_MODE = "mode";
    public static final String PARAM_NAME_CALCULATE_SUBCLASS_RELATIONS = "calculateSubClassRelations";
    public static final String PARAM_NAME_CALCULATE_PROPERTY_PROPERTY_RELATIONS = "calculatePropertyPropertyRelations";
    public static final String PARAM_NAME_CALCULATE_DOMAIN_AND_RANGE_PAIRS = "calculateDomainAndRangePairs";
    public static final String PARAM_NAME_CALCULATE_DATA_TYPES = "calculateDataTypes";
    public static final String PARAM_NAME_DATA_TYPES_SAMPLE_LIMIT = "dataTypeSampleLimit";
    public static final String PARAM_NAME_CALCULATE_CARDINALITIES = "calculateCardinalities";
    public static final String PARAM_NAME_CHECK_INSTANCE_NAMESPACES = "checkInstanceNamespaces";
    public static final String PARAM_NAME_MIN_CLASS_SIZE = "minimalAnalyzedClassSize";
    public static final String PARAM_NAME_INCLUDED_LABELS = "includedLabels";
    public static final String PARAM_NAME_INCLUDED_CLASSES = "includedClasses";
    public static final String PARAM_NAME_INCLUDED_PROPERTIES = "includedProperties";
    public static final String PARAM_NAME_CLASSIFICATION_PROPERTIES = "classificationProperties";
    public static final String PARAM_NAME_EXCLUDED_NAMESPACES = "excludedNamespaces";

    private String name;
    private String value;

    public SchemaParameter(String name, String value) {
        this.name = name;
        this.value = value;
    }
}

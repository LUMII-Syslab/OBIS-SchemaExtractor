package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaParameter {

    public static final String PARAM_NAME_ENDPOINT = "endpointUrl";
    public static final String PARAM_NAME_GRAPH_NAME = "graphName";
    public static final String PARAM_NAME_MODE = "mode";
    public static final String PARAM_NAME_VERSION = "extractionVersion";
    public static final String PARAM_NAME_EXCLUDE_SYSTEM_CLASSES = "excludeSystemClasses";
    public static final String PARAM_NAME_EXCLUDE_META_DOMAIN_CLASSES = "excludeMetaDomainClasses";
    public static final String PARAM_NAME_EXCLUDE_PROPERTIES_WITHOUT_CLASSES = "excludePropertiesWithoutClasses";
    public static final String PARAM_NAME_EXCLUDED_NAMESPACES = "excludedNamespaces";
    public static final String PARAM_NAME_ABSTRACT_PROPERTY_THRESHOLD = "abstractPropertyThreshold";
    public static final String PARAM_NAME_CALCULATE_CARDINALITIES = "calculateCardinalities";

    private String name;
    private String value;

    public SchemaParameter(String name, String value) {
        this.name = name;
        this.value = value;
    }
}

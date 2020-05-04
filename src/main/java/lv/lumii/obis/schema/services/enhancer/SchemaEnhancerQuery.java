package lv.lumii.obis.schema.services.enhancer;

public class SchemaEnhancerQuery {

    public static final String FIND_CLASS_INSTANCE_COUNT = "SELECT (COUNT(?x) as ?instances) WHERE { ?x a <domainClass>. }";
    public static final String FIND_DATA_TYPE_PROPERTY_DOMAINS = "SELECT ?domainClass WHERE { ?x <property> ?value. ?x a ?domainClass. }";

    public static final String QUERY_BINDING_NAME_DOMAIN_CLASS = "domainClass";
    public static final String QUERY_BINDING_NAME_PROPERTY = "property";
    public static final String QUERY_BINDING_NAME_INSTANCES_COUNT = "instances";

    private SchemaEnhancerQuery() {
    }
}
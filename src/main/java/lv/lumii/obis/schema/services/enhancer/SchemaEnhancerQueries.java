package lv.lumii.obis.schema.services.enhancer;

public class SchemaEnhancerQueries {

    public static final String FIND_CLASS_INSTANCE_COUNT = "SELECT (COUNT(?x) as ?instances) WHERE { ?x a <domainClass>. }";
    public static final String FIND_DATA_TYPE_PROPERTY_DOMAINS = "SELECT ?domainClass WHERE { ?x <property> ?value. ?x a ?domainClass. }";
    public static final String FIND_OBJECT_TYPE_PROPERTY_DOMAINS = "SELECT ?domainClass WHERE { ?x <property> ?value. ?x a ?domainClass. ?value a <rangeClass>. }";
    public static final String FIND_OBJECT_TYPE_PROPERTY_RANGES = "SELECT ?rangeClass WHERE { ?x <property> ?value. ?x a <domainClass>. ?value a ?rangeClass. }";
    public static final String FIND_OBJECT_TYPE_PROPERTY_DOMAINS_RANGES = "SELECT ?domainClass ?rangeClass WHERE { ?x <property> ?value. ?x a ?domainClass. ?value a ?rangeClass. }";
    public static final String FIND_ALL_PROPERTIES_WITH_INSTANCE_COUNT = "SELECT DISTINCT ?property (COUNT(?x) as ?instances) WHERE {?x ?property []} GROUP BY ?property";
    public static final String FIND_ALL_PROPERTIES_WITH_INSTANCE_COUNT_WITH_LIMIT = "SELECT ?property ?instances WHERE { {SELECT DISTINCT ?property (COUNT(?x) as ?instances) WHERE {?x ?property []} GROUP BY ?property } FILTER (?instances >= minInstances) }";

    public static final String QUERY_BINDING_NAME_DOMAIN_CLASS = "domainClass";
    public static final String QUERY_BINDING_NAME_RANGE_CLASS = "rangeClass";
    public static final String QUERY_BINDING_NAME_PROPERTY = "property";
    public static final String QUERY_BINDING_NAME_INSTANCES_COUNT = "instances";
    public static final String QUERY_BINDING_NAME_INSTANCES_COUNT_MIN = "minInstances";

    private SchemaEnhancerQueries() {
    }
}

package lv.lumii.obis.schema.services.enhancer;

public class SchemaEnhancerQueries {

    public static final String FIND_CLASS_INSTANCE_COUNT = "SELECT (COUNT(?x) as ?instances) WHERE { ?x a <domainClass>. }";

    public static final String FIND_DATA_TYPE_PROPERTY_DOMAINS = "SELECT ?domainClass WHERE { ?x <property> ?value. ?x a ?domainClass. }";
    public static final String FIND_DATA_TYPE_PROPERTY_INSTANCE_COUNT = "SELECT (COUNT(?x) as ?instances) WHERE { ?x <property> ?value. ?x a <domainClass>. }";

    public static final String FIND_OBJECT_TYPE_PROPERTY_DOMAINS = "SELECT ?domainClass WHERE { ?x <property> ?value. ?x a ?domainClass. ?value a <rangeClass>. }";
    public static final String FIND_OBJECT_TYPE_PROPERTY_RANGES = "SELECT ?rangeClass WHERE { ?x <property> ?value. ?x a <domainClass>. ?value a ?rangeClass. }";
    public static final String FIND_OBJECT_TYPE_PROPERTY_DOMAINS_RANGES = "SELECT ?domainClass ?rangeClass WHERE { ?x <property> ?value. ?x a ?domainClass. ?value a ?rangeClass. }";
    public static final String FIND_OBJECT_TYPE_PROPERTY_INSTANCE_COUNT = "SELECT (COUNT(?x) as ?instances) WHERE { ?x <property> ?value. ?x a <domainClass>. ?value a <rangeClass>. }";

    public static final String FIND_ALL_PROPERTIES_TOTAL_COUNT = "SELECT (COUNT(?property) as ?totalCount) WHERE { {SELECT ?property (COUNT(?x) as ?instances) WHERE {?x ?property []} GROUP BY ?property } FILTER (?instances >= minInstances) }";
    public static final String FIND_ALL_PROPERTIES_WITH_INSTANCE_COUNT = "SELECT ?property ?instances WHERE { SELECT ?property ?instances WHERE { {SELECT ?property (COUNT(?x) as ?instances) WHERE {?x ?property []} GROUP BY ?property } FILTER (?instances >= minInstances) } ORDER BY ?property } LIMIT limitValue OFFSET offsetValue";
    public static final String FIND_MISSING_DATA_TYPE_PROPERTY_DOMAINS = "SELECT ?domainClass (datatype(?value) as ?dataType) WHERE { ?x <property> ?value. ?x a ?domainClass. FILTER (!isURI(?value)). }";

    public static final String FIND_PROPERTY_MAX_CARDINALITY = "SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1";
    public static final String FIND_PROPERTY_MIN_CARDINALITY = "SELECT ?x WHERE { ?x a <domainClass>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1";

    public static final String QUERY_BINDING_NAME_DOMAIN_CLASS = "domainClass";
    public static final String QUERY_BINDING_NAME_RANGE_CLASS = "rangeClass";
    public static final String QUERY_BINDING_NAME_PROPERTY = "property";
    public static final String QUERY_BINDING_NAME_DATA_TYPE = "dataType";
    public static final String QUERY_BINDING_NAME_INSTANCES_COUNT = "instances";
    public static final String QUERY_BINDING_NAME_INSTANCES_COUNT_MIN = "minInstances";
    public static final String QUERY_BINDING_NAME_TOTAL_COUNT = "totalCount";
    public static final String QUERY_BINDING_NAME_OFFSET = "offsetValue";
    public static final String QUERY_BINDING_NAME_LIMIT = "limitValue";

    public static final Integer QUERY_CONSTANT_LIMIT = 10000;

    private SchemaEnhancerQueries() {
    }
}

package lv.lumii.obis.schema.services.enhancer;

public class SchemaEnhancerQueries {

    public static final String FIND_CLASS_INSTANCE_COUNT = "SELECT (COUNT(?x) as ?instances) WHERE { ?x a <classA>. }";

    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_A = "classA";
    public static final String SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT = "instances";

    private SchemaEnhancerQueries() {
    }
}

package lv.lumii.obis.schema.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SchemaConstants {

    /**
     * THING name and URL constants
     */
    public static final String THING_NAME = "Thing";
    public static final String THING_URI = "http://www.w3.org/2002/07/owl#Thing";

    /**
     * List of THING and top level OWL/RDF resources
     */
    public static final List<String> OWL_RDF_TOP_LEVEL_RESOURCES = Collections.unmodifiableList(Arrays.asList(
            THING_URI,
            "http://www.w3.org/2000/01/rdf-schema#Resource",
            "http://www.w3.org/2000/01/rdf-schema#Class",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#Resource"
    ));

    /**
     * Namespaces
     */
    public static final String GLOBAL_NAMESPACE_PATH = "./namespaces.json";

    public static final String GLOBAL_SPARQL_QUERIES_PATH = "./queries.properties";
    public static final String DEFAULT_NAMESPACE_PREFIX = ":";
    public static final String DEFAULT_NAMESPACE_PREFIX_AUTO = "n";
    public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";
    public static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String RDFS_NAMESPACE = "http://www.w3.org/2000/01/rdf-schema#";
    public static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    public static final String RDF_TYPE_SHORT = "rdf:type";
    public static final String RDFS_LABEL_SHORT = "rdfs:label";
    public static final String SKOS_LABEL_SHORT = "skos:prefLabel";

    /**
     * Data types constants
     */
    public static final String DATA_TYPE_XSD_DEFAULT = "xsd:string";
    public static final String DATA_TYPE_RDF_LANG_STRING = "rdf:langString";
    public static final String DATA_TYPE_LITERAL = "Literal";

    /**
     * Cardinality constants
     */
    public static final Integer DEFAULT_MAX_CARDINALITY = -1;
    public static final Integer DEFAULT_MIN_CARDINALITY = 0;

    /**
     * List of excluded namespaces
     */
    public static final List<String> EXCLUDED_URI_FROM_OWL = Collections.unmodifiableList(Arrays.asList(
            "http://www.w3.org/2000/01/rdf-schema#Resource",
            "http://www.w3.org/2000/01/rdf-schema#Class",
            THING_URI));
    public static final List<String> EXCLUDED_SYSTEM_URI_FROM_ENDPOINT = Collections.unmodifiableList(Arrays.asList(
            "http://www.openlinksw.com/schemas/virtrdf"));
    public static final List<String> EXCLUDED_META_DOMAIN_URI_FROM_ENDPOINT = Collections.unmodifiableList(Arrays.asList(
            "http://www.w3.org/2002/07/owl",
            "http://www.w3.org/2000/01/rdf-schema",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns"));

    /**
     * Annotation constants
     */
    public static final String ANNOTATION_INSTANCE_COUNT = "http://lumii.lv/2018/1.0/owlc#instanceCount";
    public static final String ANNOTATION_ORDER_INDEX = "http://lumii.lv/2018/1.0/owlc#orderIndex";

    /**
     * SPARQL query binding parameter names
     */
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS = "class";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_FULL = "<class>";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_A = "classA";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_A_FULL = "<classA>";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_B = "classB";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_B_FULL = "<classB>";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE = "sourceClass";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_SOURCE_FULL = "<sourceClass>";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_TARGET = "targetClass";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASS_TARGET_FULL = "<targetClass>";
    public static final String SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT = "instances";
    public static final String SPARQL_QUERY_BINDING_NAME_NAMESPACE = "nspace";
    public static final String SPARQL_QUERY_BINDING_NAME_NAMESPACE_COUNT = "nsCount";
    public static final String SPARQL_QUERY_BINDING_NAME_PROPERTY = "property";
    public static final String SPARQL_QUERY_BINDING_NAME_PROPERTY_FULL = "<property>";

    public static final String SPARQL_QUERY_BINDING_NAME_PROPERTY2_FULL = "<property2>";
    public static final String SPARQL_QUERY_BINDING_NAME_DATA_TYPE = "dataType";

    public static final String SPARQL_QUERY_BINDING_NAME_DISTINCT_FULL = "<DISTINCT>";
    public static final String SPARQL_QUERY_BINDING_NAME_DISTINCT = "DISTINCT";
    public static final String SPARQL_QUERY_BINDING_NAME_LIMIT = "<limit>";
    public static final String SPARQL_QUERY_BINDING_NAME_X = "x";
    public static final String SPARQL_QUERY_BINDING_NAME_Y = "y";
    public static final String SPARQL_QUERY_BINDING_NAME_PROPERTY_OTHER = "p2";
    public static final String SPARQL_QUERY_BINDING_NAME_CUSTOM_FILTER = "customFilter";
    public static final String SPARQL_QUERY_BINDING_NAME_VALUES = "<valuesClause>";
    public static final String SPARQL_QUERY_BINDING_NAME_RESOURCE1 = "resource1";
    public static final String SPARQL_QUERY_BINDING_NAME_RESOURCE2 = "resource2";
    public static final String SPARQL_QUERY_BINDING_NAME_VALUE = "value";
    public static final String SPARQL_QUERY_BINDING_NAME_LANGUAGE = "language";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY = "classificationProperty";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_A = "classificationPropertyA";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_B = "classificationPropertyB";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_SOURCE = "classificationPropertySource";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_FOR_TARGET = "classificationPropertyTarget";
    public static final String SPARQL_QUERY_BINDING_NAME_CLASSIFICATION_PROPERTY_OTHER = "classificationPropertyOther";

}

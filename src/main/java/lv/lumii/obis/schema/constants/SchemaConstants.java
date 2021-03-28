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
	 * Namespaces
	 */
	public static final String DEFAULT_NAMESPACE_PREFIX = ":";
	public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";
	public static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
	public static final String RDFS_NAMESPACE = "http://www.w3.org/2000/01/rdf-schema#";

	/**
	 * Data types constants
	 */
	public static final String DATA_TYPE_XSD_DEFAULT = "xsd:string";
	public static final String DATA_TYPE_XSD_LANG_STRING = "xsd:langString";
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
	public static final String SPARQL_QUERY_BINDING_NAME_CLASS_A = "classA";
	public static final String SPARQL_QUERY_BINDING_NAME_CLASS_B = "classB";
	public static final String SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN = "domainClass";
	public static final String SPARQL_QUERY_BINDING_NAME_CLASS_RANGE = "rangeClass";
	public static final String SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT = "instances";
	public static final String SPARQL_QUERY_BINDING_NAME_PROPERTY = "property";
	public static final String SPARQL_QUERY_BINDING_NAME_DATA_TYPE = "dataType";
	public static final String SPARQL_QUERY_BINDING_NAME_X = "x";
	public static final String SPARQL_QUERY_BINDING_NAME_Y = "y";

}

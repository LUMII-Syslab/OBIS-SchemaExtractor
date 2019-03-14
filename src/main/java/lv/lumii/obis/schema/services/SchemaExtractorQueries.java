package lv.lumii.obis.schema.services;

public class SchemaExtractorQueries {
	
	public static final String BINDING_NAME_CLASS = "class";
	public static final String BINDING_NAME_CLASS_A = "classA";
	public static final String BINDING_NAME_CLASS_B = "classB";
	public static final String BINDING_NAME_INSTANCES_COUNT = "instances";
	public static final String BINDING_NAME_PROPERTY = "property";
	public static final String BINDING_NAME_DATA_TYPE = "dataType";
	
	public static final String FIND_ALL_CLASSES = 
			  "SELECT DISTINCT ?class WHERE { ?x a ?class. }";
	
	public static final String FIND_INTERSECTION_CLASSES = 
			  "SELECT DISTINCT ?classA ?classB WHERE {" + "\n\t"
			+ "?x a ?classA." + "\n\t"
			+ "?x a ?classB." + "\n\t"
			+ "FILTER (?classA != ?classB)" + "\n"
			+ "} ORDER BY ?classA";
	
	public static final String FIND_INSTANCES_COUNT = 
			  "SELECT ?class (COUNT(?x) as ?instances) WHERE { ?x a ?class. } GROUP BY ?class";
	
	public static final String CHECK_SUPERCLASS = 
			"SELECT (COUNT(?x) as ?instances) WHERE {" + "\n\t"
			+ "?x a ?classA." + "\n\t"
			+ "FILTER (NOT EXISTS {?x a ?classB.})" + "\n\t"
			+ "}";
	
	public static final String FIND_ALL_PROPERTIES = 
			  "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
			+ "SELECT DISTINCT ?property ?class (COUNT(?x) as ?instances) WHERE {" + "\n\t"
			+ "?x a ?class." + "\n\t"
			+ "?x ?property ?value." + "\n\t"
			+ "FILTER (?property != rdf:type)" + "\n"
			+ "} GROUP BY ?property ?class";
	
	public static final String FIND_OBJECT_PROPERTIES_WITH_RANGE = 
			  "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
			+ "SELECT DISTINCT ?property ?class (COUNT(?x) as ?instances) WHERE {" + "\n\t"
			+ "?x a ?type." + "\n\t"
			+ "?x ?property ?value." + "\n\t"
			+ "?value a ?class." + "\n\t"
			+ "FILTER (?property != rdf:type)" + "\n"
			+ "} GROUP BY ?property ?class";
	
	public static final String FIND_DATA_TYPE =
			  "SELECT DISTINCT  (datatype(?value) as ?dataType) WHERE { ?x <property> ?value. }";
	
	public static final String FIND_MAX_CARDINALITY = 
			  "SELECT ?x WHERE { ?x <property> ?value. } GROUP BY ?x HAVING (COUNT(?value) > 1) LIMIT 1";
	
	public static final String FIND_MIN_CARDINALITY = 
			  "SELECT (COUNT(?x) as ?instances) WHERE { ?x a <class>. FILTER NOT EXISTS { ?x <property> ?value. } } ";

	public static final String CHECK_DOMAIN_RANGE_MAPPING =
			"SELECT (COUNT(?x) as ?instances) WHERE { ?x a <classA>. ?x <property> ?value. ?value a <classB>. } ";

}

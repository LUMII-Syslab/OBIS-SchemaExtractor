package lv.lumii.obis.schema.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SchemaConstants {
	
	public static final String THING_NAME = "Thing";
	public static final String THING_URI = "http://www.w3.org/2002/07/owl#Thing";
	
	public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";
	public static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
	public static final String RDFS_NAMESPACE = "http://www.w3.org/2000/01/rdf-schema#";
	
	public static final String DATA_TYPE_XSD_DEFAULT = "xsd:string";
	public static final String DATA_TYPE_LITERAL = "Literal";

	public static final Integer DEFAULT_MAX_CARDINALITY = -1;
	public static final Integer DEFAULT_MIN_CARDINALITY = 0;

	public static final String DEFAULT_NAMESPACE_PREFIX = ":";
	
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

}
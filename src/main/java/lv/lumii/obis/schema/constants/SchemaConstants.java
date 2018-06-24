package lv.lumii.obis.schema.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SchemaConstants {
	
	public static final String EXTRACT_MODE_SIMPLE = "SIMPLE";
	
	public static final String THING_NAME = "Thing";
	public static final String THING_URI = "http://www.w3.org/2002/07/owl#Thing";
	
	public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";
	
	public static final String DEFAULT_XSD_DATA_TYPE = "xsd_string";

	public static final Integer DEFAULT_MAX_CARDINALITY = -1;
	public static final Integer DEFAULT_MIN_CARDINALITY = 0;
	
	public static final List<String> EXCLUDED_URI = Collections.unmodifiableList(Arrays.asList(
			"http://www.w3.org/2000/01/rdf-schema#Resource", 
			"http://www.w3.org/2000/01/rdf-schema#Class", 
			THING_URI));

	public static final String DEFAULT_NAMESPACE_PREFIX = ":";

}

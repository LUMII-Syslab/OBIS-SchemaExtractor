package lv.lumii.obis.schema.services;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

public enum SchemaExtractorQueries {

	FIND_CLASSES_WITH_INSTANCE_COUNT(
			"SELECT ?class (COUNT(?x) as ?instances) WHERE { ?x a ?class. } GROUP BY ?class"
	),

	FIND_INTERSECTION_CLASSES(
			"SELECT DISTINCT ?classA ?classB WHERE {" + "\n\t"
			+ "?x a ?classA." + "\n\t"
			+ "?x a ?classB." + "\n\t"
			+ "FILTER (?classA != ?classB)" + "\n"
			+ "} ORDER BY ?classA"
	),

	CHECK_SUPERCLASS(
			"SELECT ?x WHERE { ?x a <classA>. OPTIONAL { ?x ?a ?value. FILTER (?value = <classB>) } FILTER (!BOUND(?value)) } LIMIT 1 "
	),

	FIND_ALL_PROPERTIES(
			"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
			+ "SELECT DISTINCT ?property ?class (COUNT(?x) as ?instances) WHERE {" + "\n\t"
			+ "?x a ?class." + "\n\t"
			+ "?x ?property ?value." + "\n\t"
			+ "FILTER (?property != rdf:type)" + "\n"
			+ "} GROUP BY ?property ?class"
	),

	FIND_OBJECT_PROPERTIES_WITH_RANGE(
			"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
			+ "SELECT DISTINCT ?property ?class (COUNT(?x) as ?instances) WHERE {" + "\n\t"
			+ "?x a ?type." + "\n\t"
			+ "?x ?property ?value." + "\n\t"
			+ "?value a ?class." + "\n\t"
			+ "FILTER (?property != rdf:type)" + "\n"
			+ "} GROUP BY ?property ?class"
	),

	FIND_PROPERTY_DATA_TYPE(
			"SELECT DISTINCT  (datatype(?value) as ?dataType) WHERE { ?x <property> ?value. }"
	),

	FIND_PROPERTY_MAX_CARDINALITY(
			"SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
	),

	FIND_PROPERTY_MIN_CARDINALITY(
            "SELECT ?x WHERE { ?x a <class>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
	),

	CHECK_PROPERTY_DOMAIN_RANGE_MAPPING(
			"SELECT ?x WHERE { ?x a <classA>. ?x <property> ?value. ?value a <classB>. } LIMIT 1 "
	);
	
	@Setter @Getter
	private String sparqlQuery;

	SchemaExtractorQueries(@Nonnull String sparqlQuery) {
		this.sparqlQuery = sparqlQuery;
	}

}

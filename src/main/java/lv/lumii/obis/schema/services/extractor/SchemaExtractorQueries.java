package lv.lumii.obis.schema.services.extractor;

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

    FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS(
            "SELECT DISTINCT ?classB WHERE {" + "\n\t"
                    + "?x a ?classA." + "\n\t"
                    + "?x a ?classB." + "\n\t"
                    + "FILTER (?classA = <domainClass>)" + "\n\t"
                    + "FILTER (?classA != ?classB)" + "\n"
                    + "}"
    ),

    CHECK_SUPERCLASS(
            "SELECT ?x WHERE { ?x a <classA>. OPTIONAL { ?x ?a ?value. FILTER (?value = <classB>) } FILTER (!BOUND(?value)) } LIMIT 1 "
    ),

    FIND_ALL_DATATYPE_PROPERTIES(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?class (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "OPTIONAL { ?x a ?class. }" + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n"
                    + "FILTER (!isURI(?value))" + "\n"
                    + "} GROUP BY ?property ?class"
    ),

    FIND_ALL_DATATYPE_PROPERTIES_FOR_KNOWN_CLASS(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x a <domainClass>." + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (!isURI(?value))" + "\n"
                    + "} GROUP BY ?property"
    ),

    FIND_ALL_DATATYPE_PROPERTIES_WITHOUT_DOMAIN(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "OPTIONAL { ?x a ?domainClass. }" + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (!isURI(?value))" + "\n\t"
                    + "FILTER (!BOUND(?domainClass))" + "\n"
                    + "} GROUP BY ?property"
    ),

    FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?domainClass ?rangeClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "OPTIONAL { ?x a ?domainClass. }" + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (isURI(?value))" + "\n\t"
                    + "OPTIONAL { ?value a ?rangeClass. }" + "\n"
                    + "} GROUP BY ?property ?domainClass ?rangeClass"
    ),

    FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE_FOR_KNOWN_CLASS(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?rangeClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x a <domainClass>." + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (isURI(?value))" + "\n\t"
                    + "?value a ?rangeClass." + "\n"
                    + "} GROUP BY ?property ?rangeClass"
    ),
    FIND_OBJECT_PROPERTIES_WITHOUT_DOMAIN(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?rangeClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (isURI(?value))" + "\n\t"
                    + "OPTIONAL { ?value a ?rangeClass. }" + "\n"
                    + "OPTIONAL { ?x a ?domainClass. }" + "\n\t"
                    + "FILTER (!BOUND(?domainClass))" + "\n"
                    + "} GROUP BY ?property ?rangeClass"
    ),
    FIND_OBJECT_PROPERTIES_WITHOUT_RANGE(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?domainClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (isURI(?value))" + "\n\t"
                    + "OPTIONAL { ?value a ?rangeClass. }" + "\n"
                    + "OPTIONAL { ?x a ?domainClass. }" + "\n\t"
                    + "FILTER (!BOUND(?rangeClass))" + "\n"
                    + "} GROUP BY ?property ?domainClass"
    ),

    FIND_PROPERTY_DATA_TYPE(
            "SELECT DISTINCT  (datatype(?value) as ?dataType) WHERE { ?x <property> ?value. }"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { { SELECT (datatype(?value) as ?dataType) ?value WHERE { ?x <property> ?value. } } } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),

    FIND_PROPERTY_MAX_CARDINALITY(
            "SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY(
            "SELECT ?y WHERE { ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_RANGE(
            "SELECT ?y WHERE { ?y a <rangeClass>. ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_PROPERTY_MIN_CARDINALITY(
            "SELECT ?x WHERE { ?x a <class>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
    ),

    FIND_ALL_PROPERTIES(
            "SELECT ?property (COUNT(?x) as ?instances) WHERE {?x ?property ?y} GROUP BY ?property"
    ),

    FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT(
            "SELECT distinct ?class WHERE {?x <property> ?y. ?x a ?class. }"
    ),

    FIND_PROPERTY_DOMAINS_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x a <domainClass>. }"
    ),

    FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT(
            "SELECT distinct ?class WHERE {?x <property> ?y. ?y a ?class. } "
    ),

    FIND_PROPERTY_RANGES_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?y a <rangeClass>. }"
    ),

    FIND_PROPERTY_DOMAIN_RANGE_PAIRS(
            "SELECT ?domainClass ?rangeClass (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x a ?domainClass. ?y a ?rangeClass. } GROUP BY ?domainClass ?rangeClass"
    ),
    FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x a <domainClass>. ?y a <rangeClass>.}"
    ),

    COUNT_PROPERTY_URL_VALUES(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <property> ?y. FILTER(isURI(?y)) }"
    ),
    COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x a <domainClass>. ?x <property> ?y. FILTER(isURI(?y)) }"
    ),

    FIND_CLOSED_RANGE_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. FILTER(isURI(?y)) OPTIONAL {?y a ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. OPTIONAL {?x a ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    )


    ;


    @Setter
    @Getter
    private String sparqlQuery;

    SchemaExtractorQueries(@Nonnull String sparqlQuery) {
        this.sparqlQuery = sparqlQuery;
    }

}

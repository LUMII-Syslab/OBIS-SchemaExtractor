package lv.lumii.obis.schema.services.extractor.v1;

import lombok.Getter;

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
                    + "FILTER (?classA = <sourceClass>)" + "\n\t"
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
                    + "FILTER (isLiteral(?value))" + "\n"
                    + "} GROUP BY ?property ?class"
    ),

    FIND_ALL_DATATYPE_PROPERTIES_FOR_KNOWN_CLASS(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x a <sourceClass>." + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (isLiteral(?value))" + "\n"
                    + "} GROUP BY ?property"
    ),

    FIND_ALL_DATATYPE_PROPERTIES_WITHOUT_DOMAIN(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "OPTIONAL { ?x a ?sourceClass. }" + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (isLiteral(?value))" + "\n\t"
                    + "FILTER (!BOUND(?sourceClass))" + "\n"
                    + "} GROUP BY ?property"
    ),

    FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?sourceClass ?targetClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "OPTIONAL { ?x a ?sourceClass. }" + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (!isLiteral(?value))" + "\n\t"
                    + "OPTIONAL { ?value a ?targetClass. }" + "\n"
                    + "} GROUP BY ?property ?sourceClass ?targetClass"
    ),

    FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE_FOR_KNOWN_CLASS(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?targetClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x a <sourceClass>." + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (!isLiteral(?value))" + "\n\t"
                    + "?value a ?targetClass." + "\n"
                    + "} GROUP BY ?property ?targetClass"
    ),
    FIND_OBJECT_PROPERTIES_WITHOUT_DOMAIN(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?targetClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (!isLiteral(?value))" + "\n\t"
                    + "OPTIONAL { ?value a ?targetClass. }" + "\n"
                    + "OPTIONAL { ?x a ?sourceClass. }" + "\n\t"
                    + "FILTER (!BOUND(?sourceClass))" + "\n"
                    + "} GROUP BY ?property ?targetClass"
    ),
    FIND_OBJECT_PROPERTIES_WITHOUT_RANGE(
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" + "\n"
                    + "SELECT DISTINCT ?property ?sourceClass (COUNT(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x ?property ?value." + "\n\t"
                    + "FILTER (?property != rdf:type)" + "\n\t"
                    + "FILTER (!isLiteral(?value))" + "\n\t"
                    + "OPTIONAL { ?value a ?targetClass. }" + "\n"
                    + "OPTIONAL { ?x a ?sourceClass. }" + "\n\t"
                    + "FILTER (!BOUND(?targetClass))" + "\n"
                    + "} GROUP BY ?property ?sourceClass"
    ),

    FIND_PROPERTY_DATA_TYPE(
            "SELECT DISTINCT  (datatype(?value) as ?dataType) WHERE { ?x <property> ?value. }"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_DOMAIN(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { ?x a <class>. ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_DOMAIN(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x a <class>. ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),

    FIND_PROPERTY_MAX_CARDINALITY(
            "SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_PROPERTY_MAX_CARDINALITY_FOR_DOMAIN(
            "SELECT ?x WHERE { ?x a <class>. ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY(
            "SELECT ?y WHERE { ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_RANGE(
            "SELECT ?y WHERE { ?y a <targetClass>. ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_PROPERTY_MIN_CARDINALITY(
            "SELECT ?x WHERE { ?x a <class>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
    ),

    FIND_INVERSE_PROPERTY_MIN_CARDINALITY(
            "SELECT ?y WHERE { ?y a <class>. OPTIONAL { ?x ?prop ?y. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
    ),

    FIND_PROPERTY_FOLLOWERS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x where { [] <property> ?x } } ?x ?p2 [] } GROUP BY ?p2"
    ),
    FIND_PROPERTY_OUTGOING_PROPERTIES(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x WHERE { ?x <property> [] } } ?x ?p2 [] } GROUP BY ?p2"
    ),
    FIND_PROPERTY_INCOMING_PROPERTIES(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x WHERE { [] <property> ?x . FILTER(!isLiteral(?x))} } [] ?p2 ?x } GROUP BY ?p2"
    ),

    FIND_ALL_PROPERTIES(
            "SELECT ?property (COUNT(?x) as ?instances) WHERE {?x ?property ?y} GROUP BY ?property"
    ),

    FIND_PROPERTY_DOMAINS_WITHOUT_TRIPLE_COUNT(
            "SELECT distinct ?class WHERE {?x <property> ?y. ?x a ?class. }"
    ),

    FIND_PROPERTY_DOMAINS_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x a <sourceClass>. }"
    ),

    FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT(
            "SELECT distinct ?class WHERE {?x <property> ?y. OPTIONAL{ ?y a ?class.} } "
    ),

    FIND_PROPERTY_RANGES_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?y a <targetClass>. }"
    ),

    FIND_PROPERTY_DOMAIN_RANGE_PAIRS(
            "SELECT ?sourceClass ?targetClass (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x a ?sourceClass. ?y a ?targetClass. } GROUP BY ?sourceClass ?targetClass"
    ),
    FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x a <sourceClass>. ?y a <targetClass>.}"
    ),

    COUNT_PROPERTY_URL_VALUES(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <property> ?y. FILTER(!isLiteral(?y)) }"
    ),
    COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x a <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) }"
    ),
    COUNT_PROPERTY_LITERAL_VALUES(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <property> ?y. FILTER(isLiteral(?y)) }"
    ),
    COUNT_PROPERTY_LITERAL_VALUES_FOR_DOMAIN(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x a <sourceClass>. ?x <property> ?y. FILTER(isLiteral(?y)) }"
    ),
    FIND_CLOSED_RANGE_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y a ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. OPTIONAL {?x a ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?x a <class>. ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y a ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),

    ;


    @Getter
    private final String sparqlQuery;

    SchemaExtractorQueries(@Nonnull String sparqlQuery) {
        this.sparqlQuery = sparqlQuery;
    }

}

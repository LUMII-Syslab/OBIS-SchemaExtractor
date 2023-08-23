package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;

import javax.annotation.Nonnull;

public enum SchemaExtractorQueries {

    FIND_CLASSES_WITH_INSTANCE_COUNT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE { ?x <classificationProperty> ?class. } GROUP BY ?class"
    ),

    FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS(
            "SELECT DISTINCT ?classB WHERE {" + "\n\t"
                    + "?x <classificationPropertyA> ?classA." + "\n\t"
                    + "?x <classificationPropertyB> ?classB." + "\n\t"
                    + "FILTER (?classA = <domainClass>)" + "\n\t"
                    + "FILTER (?classA != ?classB)" + "\n"
                    + "}"
    ),

    CHECK_CLASS_INTERSECTION(
            "SELECT ?x where {?x <classificationPropertyA> <classA>. ?x <classificationPropertyB> <classB>} LIMIT 1"
    ),

    CHECK_SUPERCLASS(
            "SELECT ?x WHERE { ?x <classificationPropertyA> <classA>. OPTIONAL { ?x <classificationPropertyB> ?value. FILTER (?value = <classB>) } FILTER (!BOUND(?value)) } LIMIT 1 "
    ),

    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { SELECT ?x ?value ?dataType WHERE {?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_DOMAIN(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { ?x <classificationProperty> <domainClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_DOMAIN_WITH_LIMITS(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { SELECT ?x ?value ?dataType WHERE {?x <classificationProperty> <domainClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT ?x ?value WHERE {?x <property> ?value.} LIMIT <limit> } FILTER (lang(?value) != \\\"\\\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_DOMAIN(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x <classificationProperty> <domainClass>. ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_DOMAIN_WITH_LIMITS(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT ?x ?value WHERE { ?x <classificationProperty> <domainClass>. ?x <property> ?value. } LIMIT <limit> } FILTER (lang(?value) != \"\") }"
    ),

    FIND_PROPERTY_MAX_CARDINALITY(
            "SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_PROPERTY_MAX_CARDINALITY_FOR_DOMAIN(
            "SELECT ?x WHERE { ?x <classificationProperty> <domainClass>. ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY(
            "SELECT ?y WHERE { ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_RANGE(
            "SELECT ?y WHERE { ?y <classificationProperty> <rangeClass>. ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_PROPERTY_MIN_CARDINALITY(
            "SELECT ?x WHERE { ?x <classificationProperty> <domainClass>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
    ),

    FIND_INVERSE_PROPERTY_MIN_CARDINALITY(
            "SELECT ?y WHERE { ?y <classificationProperty> <rangeClass>. OPTIONAL { ?x ?prop ?y. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
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
            "SELECT distinct ?class WHERE {?x <property> ?y. ?x <classificationProperty> ?class. }"
    ),

    FIND_PROPERTY_DOMAINS_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationProperty> <domainClass>. }"
    ),

    CHECK_CLASS_AS_PROPERTY_DOMAIN(
            "SELECT ?x WHERE {?x <property> ?y. ?x <classificationProperty> <domainClass>. } LIMIT 1"
    ),

    FIND_PROPERTY_RANGES_WITHOUT_TRIPLE_COUNT(
            "SELECT distinct ?class WHERE {?x <property> ?y. OPTIONAL{ ?y <classificationProperty> ?class.} } "
    ),

    FIND_PROPERTY_RANGES_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?y <classificationProperty> <rangeClass>. }"
    ),

    CHECK_CLASS_AS_PROPERTY_RANGE(
            "SELECT ?y WHERE {?x <property> ?y. ?y <classificationProperty> <rangeClass>. } LIMIT 1"
    ),

    FIND_PROPERTY_DOMAIN_RANGE_PAIRS(
            "SELECT ?domainClass ?rangeClass (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertyDomain> ?domainClass. ?y <classificationPropertyRange> ?rangeClass. } GROUP BY ?domainClass ?rangeClass"
    ),
    FIND_PROPERTY_DOMAIN_RANGE_PAIRS_FOR_SPECIFIC_CLASSES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertyDomain> <domainClass>. ?y <classificationPropertyRange> <rangeClass>.}"
    ),

    COUNT_PROPERTY_URL_VALUES(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <property> ?y. FILTER(!isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_URL_VALUES(
            "SELECT ?y where {?x <property> ?y. FILTER(!isLiteral(?y)) } LIMIT 1"
    ),
    COUNT_PROPERTY_URL_VALUES_FOR_DOMAIN(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <classificationProperty> <domainClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_URL_VALUES_FOR_DOMAIN(
            "SELECT ?y WHERE {?x <classificationProperty> <domainClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) } LIMIT 1"
    ),
    COUNT_PROPERTY_LITERAL_VALUES(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <property> ?y. FILTER(isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_LITERAL_VALUES(
            "SELECT ?y WHERE {?x <property> ?y. FILTER(isLiteral(?y)) LIMIT 1}"
    ),
    COUNT_PROPERTY_LITERAL_VALUES_FOR_DOMAIN(
            "SELECT (COUNT(?y) as ?instances) WHERE {?x <classificationProperty> <domainClass>. ?x <property> ?y. FILTER(isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_LITERAL_VALUES_FOR_DOMAIN(
            "SELECT ?y WHERE {?x <classificationProperty> <domainClass>. ?x <property> ?y. FILTER(isLiteral(?y)) } LIMIT 1"
    ),

    FIND_CLOSED_RANGE_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y <classificationProperty> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. OPTIONAL {?x <classificationProperty> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?x <classificationProperty> <domainClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y <classificationPropertyRange> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?y <classificationProperty> <rangeClass>. ?x <property> ?y. OPTIONAL {?x <classificationPropertyDomain> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),

    CHECK_PRINCIPAL_DOMAIN(
            "SELECT ?x WHERE { ?x <property> ?y. ?x <classificationPropertyDomain> <domainClass>. OPTIONAL {?x <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),
    CHECK_PRINCIPAL_RANGE(
            "SELECT ?x WHERE { ?x <property> ?y. ?y <classificationPropertyRange> <rangeClass>. OPTIONAL {?y <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),
    CHECK_PRINCIPAL_RANGE_FOR_DOMAIN(
            "SELECT ?x ?y WHERE { ?x <classificationPropertyDomain> <domainClass>. ?x <property> ?y. ?y <classificationPropertyRange> <rangeClass>. OPTIONAL {?y <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),
    CHECK_PRINCIPAL_DOMAIN_FOR_RANGE(
            "SELECT ?x ?y WHERE { ?x <classificationPropertyDomain> <domainClass>. ?x <property> ?y. ?y <classificationPropertyRange> <rangeClass>. OPTIONAL {?x <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),

    FIND_LABEL(
            "SELECT (STR(?z) as ?value) (LANG(?z) as ?language) WHERE { ?x ?y ?z. FILTER(?x = <resource>) FILTER(?y = <property>) }"
    ),
    FIND_LABEL_WITH_LANG(
            "SELECT (STR(?z) as ?value) (LANG(?z) as ?language) WHERE { ?x ?y ?z. FILTER(?x = <resource>) FILTER(?y = <property>) FILTER (customFilter) }"
    ),

    FIND_INSTANCE_NAMESPACES(
            "SELECT DISTINCT ?x WHERE {?x <classificationProperty> <classA>. } LIMIT 1000"
    ),

    ;


    @Getter
    private final String sparqlQuery;

    SchemaExtractorQueries(@Nonnull String sparqlQuery) {
        this.sparqlQuery = sparqlQuery;
    }

}
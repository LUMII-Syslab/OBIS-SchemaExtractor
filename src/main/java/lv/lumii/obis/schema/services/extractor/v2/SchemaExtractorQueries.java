package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;

import javax.annotation.Nonnull;

public enum SchemaExtractorQueries {

    FIND_CLASSES_WITH_INSTANCE_COUNT(
            "SELECT ?class (COUNT(<DISTINCT> ?x) as ?instances) WHERE { ?x <classificationProperty> ?class. } GROUP BY ?class"
    ),

    FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS(
            "SELECT DISTINCT ?classB WHERE {" + "\n\t"
                    + "?x <classificationPropertyA> ?classA." + "\n\t"
                    + "?x <classificationPropertyB> ?classB." + "\n\t"
                    + "FILTER (?classA = <sourceClass>)" + "\n\t"
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
            "SELECT ?dataType (COUNT(<DISTINCT> ?value) as ?instances) WHERE { ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT ?dataType (COUNT(<DISTINCT> ?value) as ?instances) WHERE { SELECT ?x ?value ?dataType WHERE {?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE(
            "SELECT ?dataType (COUNT(<DISTINCT> ?value) as ?instances) WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS(
            "SELECT ?dataType (COUNT(<DISTINCT> ?value) as ?instances) WHERE { SELECT ?x ?value ?dataType WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> } GROUP BY ?dataType"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING(
            "SELECT (COUNT(<DISTINCT> ?value) as ?instances) WHERE { ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS(
            "SELECT (COUNT(<DISTINCT> ?value) as ?instances) WHERE { { SELECT ?x ?value WHERE {?x <property> ?value.} LIMIT <limit> } FILTER (lang(?value) != \\\"\\\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE(
            "SELECT (COUNT(<DISTINCT> ?value) as ?instances) WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. FILTER (lang(?value) != \"\") }"
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS(
            "SELECT (COUNT(<DISTINCT> ?value) as ?instances) WHERE { { SELECT ?x ?value WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. } LIMIT <limit> } FILTER (lang(?value) != \"\") }"
    ),

    FIND_PROPERTY_MAX_CARDINALITY(
            "SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE(
            "SELECT ?x WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY(
            "SELECT ?y WHERE { ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET(
            "SELECT ?y WHERE { ?y <classificationProperty> <targetClass>. ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1"
    ),

    FIND_PROPERTY_MIN_CARDINALITY(
            "SELECT ?x WHERE { ?x <classificationProperty> <sourceClass>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
    ),

    FIND_INVERSE_PROPERTY_MIN_CARDINALITY(
            "SELECT ?y WHERE { ?y <classificationProperty> <targetClass>. OPTIONAL { ?x ?prop ?y. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 "
    ),

    FIND_PROPERTY_FOLLOWERS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x where { [] <property> ?x } } ?x ?p2 [] } GROUP BY ?p2"
    ),
    FIND_PROPERTY_FOLLOWERS_WITH_LIMITS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x where { [] <property> ?x } LIMIT <limit> } ?x ?p2 [] } GROUP BY ?p2"
    ),
    FIND_PROPERTY_OUTGOING_PROPERTIES(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x WHERE { ?x <property> [] } } ?x ?p2 [] } GROUP BY ?p2"
    ),
    FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x WHERE { ?x <property> [] } LIMIT <limit> } ?x ?p2 [] } GROUP BY ?p2"
    ),
    FIND_PROPERTY_INCOMING_PROPERTIES(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x WHERE { [] <property> ?x . FILTER(!isLiteral(?x))} } [] ?p2 ?x } GROUP BY ?p2"
    ),
    FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x WHERE { [] <property> ?x . FILTER(!isLiteral(?x))} LIMIT <limit> } [] ?p2 ?x } GROUP BY ?p2"
    ),

    FIND_ALL_PROPERTIES(
            "SELECT ?property (COUNT(<DISTINCT> ?x) as ?instances) WHERE {?x ?property ?y} GROUP BY ?property"
    ),

    FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT(
            "SELECT DISTINCT ?class WHERE {?x <property> ?y. ?x <classificationProperty> ?class. }"
    ),

    FIND_PROPERTY_SOURCE_TRIPLE_COUNT(
            "SELECT (COUNT(<DISTINCT> ?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationProperty> <sourceClass>. }"
    ),
    FIND_PROPERTY_SOURCE_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT (COUNT(<DISTINCT> ?x) as ?instances) WHERE { SELECT ?x WHERE {?x <property> ?y. ?x <classificationProperty> <sourceClass>. } LIMIT <limit> }"
    ),

    CHECK_CLASS_AS_PROPERTY_SOURCE(
            "SELECT ?x WHERE {?x <property> ?y. ?x <classificationProperty> <sourceClass>. } LIMIT 1"
    ),

    FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT(
            "SELECT DISTINCT ?class WHERE {?x <property> ?y. OPTIONAL{ ?y <classificationProperty> ?class.} } "
    ),

    FIND_PROPERTY_TARGET_TRIPLE_COUNT(
            "SELECT (COUNT(<DISTINCT> ?x) as ?instances) WHERE {?x <property> ?y. ?y <classificationProperty> <targetClass>. }"
    ),
    FIND_PROPERTY_TARGET_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT (COUNT(<DISTINCT> ?x) as ?instances) WHERE { SELECT ?x WHERE {?x <property> ?y. ?y <classificationProperty> <targetClass>. } LIMIT <limit> }"
    ),

    CHECK_CLASS_AS_PROPERTY_TARGET(
            "SELECT ?y WHERE {?x <property> ?y. ?y <classificationProperty> <targetClass>. } LIMIT 1"
    ),

    FIND_PROPERTY_SOURCE_TARGET_PAIRS(
            "SELECT ?sourceClass ?targetClass (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertySource> ?sourceClass. ?y <classificationPropertyTarget> ?targetClass. } GROUP BY ?sourceClass ?targetClass"
    ),
    FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. ?y <classificationPropertyTarget> <targetClass>.}"
    ),

    COUNT_PROPERTY_URL_VALUES(
            "SELECT (COUNT(<DISTINCT> ?y) as ?instances) WHERE {?x <property> ?y. FILTER(!isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_URL_VALUES(
            "SELECT ?y where {?x <property> ?y. FILTER(!isLiteral(?y)) } LIMIT 1"
    ),
    COUNT_PROPERTY_URL_VALUES_FOR_SOURCE(
            "SELECT (COUNT(<DISTINCT> ?y) as ?instances) WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_URL_VALUES_FOR_SOURCE(
            "SELECT ?y WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) } LIMIT 1"
    ),
    COUNT_PROPERTY_LITERAL_VALUES(
            "SELECT (COUNT(<DISTINCT> ?y) as ?instances) WHERE {?x <property> ?y. FILTER(isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_LITERAL_VALUES(
            "SELECT ?y WHERE {?x <property> ?y. FILTER(isLiteral(?y)) LIMIT 1}"
    ),
    COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE(
            "SELECT (COUNT(<DISTINCT> ?y) as ?instances) WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(isLiteral(?y)) }"
    ),
    CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE(
            "SELECT ?y WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(isLiteral(?y)) } LIMIT 1"
    ),

    FIND_CLOSED_RANGE_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y <classificationProperty> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. OPTIONAL {?x <classificationProperty> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y <classificationPropertyTarget> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?y <classificationProperty> <targetClass>. ?x <property> ?y. OPTIONAL {?x <classificationPropertySource> ?c} FILTER(!BOUND(?c)) } LIMIT 1"
    ),

    CHECK_DOMAIN_FOR_PROPERTY(
            "SELECT ?x WHERE { ?x <property> ?y. OPTIONAL {?x <classificationProperty> ?class1. FILTER (?class1 = <sourceClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1"
    ),
    CHECK_RANGE_FOR_PROPERTY(
            "SELECT ?x WHERE { ?x <property> ?y. OPTIONAL {?y <classificationProperty> ?class1. FILTER (?class1 = <targetClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1"
    ),
    CHECK_RANGE_FOR_PAIR_SOURCE(
            "SELECT ?x WHERE { ?x <property> ?y. ?x <classificationPropertySource> <sourceClass>.  OPTIONAL {?y <classificationPropertyTarget> ?class1. FILTER (?class1 = <targetClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1"
    ),
    CHECK_DOMAIN_FOR_PAIR_TARGET(
            "SELECT ?x WHERE { ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>.  OPTIONAL {?x <classificationPropertySource> ?class1. FILTER (?class1 = <sourceClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1"
    ),

    CHECK_PRINCIPAL_SOURCE(
            "SELECT ?x WHERE { ?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. OPTIONAL {?x <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),
    CHECK_PRINCIPAL_TARGET(
            "SELECT ?x WHERE { ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>. OPTIONAL {?y <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),
    CHECK_PRINCIPAL_TARGET_FOR_SOURCE(
            "SELECT ?x ?y WHERE { ?x <classificationPropertySource> <sourceClass>. ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>. OPTIONAL {?y <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
    ),
    CHECK_PRINCIPAL_SOURCE_FOR_TARGET(
            "SELECT ?x ?y WHERE { ?x <classificationPropertySource> <sourceClass>. ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>. OPTIONAL {?x <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1"
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
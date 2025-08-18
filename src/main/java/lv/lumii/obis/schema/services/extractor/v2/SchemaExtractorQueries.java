package lv.lumii.obis.schema.services.extractor.v2;

import lombok.Getter;

import javax.annotation.Nonnull;

public enum SchemaExtractorQueries {

    FIND_CLASSES(
            "SELECT DISTINCT ?class WHERE { ?x <classificationProperty> ?class. }", QueryType.LARGE
    ),
    FIND_CLASSES_WITH_INSTANCE_COUNT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE { ?x <classificationProperty> ?class. } GROUP BY ?class", QueryType.LARGE
    ),
    FIND_CLASSES_WITH_INSTANCE_COUNT_DISTINCT(
            "SELECT ?class (COUNT(DISTINCT ?x) as ?instances) WHERE { ?x <classificationProperty> ?class. } GROUP BY ?class", QueryType.LARGE
    ),
    FIND_INSTANCE_COUNT_FOR_CLASS(
            "SELECT (COUNT(?x) as ?instances) WHERE { ?x <classificationProperty> <classA>. }", QueryType.LARGE
    ),
    FIND_INSTANCE_COUNT_FOR_CLASS_DISTINCT(
            "SELECT (COUNT(DISTINCT ?x) as ?instances) WHERE { ?x <classificationProperty> <classA>. }", QueryType.LARGE
    ),

    FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS(
            "SELECT DISTINCT ?classB (count(?x) as ?instances) WHERE {" + "\n\t"
                    + "?x <classificationPropertyA> ?classA." + "\n\t"
                    + "?x <classificationPropertyB> ?classB." + "\n\t"
                    + "FILTER (?classA = <sourceClass>)" + "\n\t"
                    + "FILTER (?classA != ?classB)" + "\n"
                    + "} GROUP BY ?classB", QueryType.SMALL
    ),

    CHECK_CLASS_INTERSECTION(
            "SELECT (COUNT(?x) as ?instances) where {?x <classificationPropertyA> <classA>. ?x <classificationPropertyB> <classB>}", QueryType.SMALL
    ),

    CHECK_SUPERCLASS(
            "SELECT ?x WHERE { ?x <classificationPropertyA> <classA>. OPTIONAL { ?x <classificationPropertyB> ?value. FILTER (?value = <classB>) } FILTER (!BOUND(?value)) } LIMIT 1 ", QueryType.SMALL
    ),

    FIND_TRIPLE_COUNT_FOR_PROPERTY(
            "SELECT (COUNT(?x) as ?instances) WHERE { ?x <property> ?y. }", QueryType.LARGE
    ),
    FIND_TRIPLE_COUNT_FOR_PROPERTY_DISTINCT(
            "SELECT (COUNT(*) as ?instances) WHERE {{SELECT DISTINCT ?x ?y WHERE { ?x <property> ?y. }}}", QueryType.LARGE
    ),

    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_DISTINCT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { { SELECT DISTINCT ?x ?value ?dataType WHERE {?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) }}} GROUP BY ?dataType", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { SELECT ?x ?value ?dataType WHERE {?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> } GROUP BY ?dataType", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_WITH_LIMITS_DISTINCT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { {SELECT DISTINCT ?x ?value ?dataType WHERE {?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> }} GROUP BY ?dataType", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) } GROUP BY ?dataType", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_DISTINCT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { {SELECT DISTINCT ?x ?value ?dataType WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType). FILTER (BOUND(?dataType)) }}} GROUP BY ?dataType", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { SELECT ?x ?value ?dataType WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> } GROUP BY ?dataType", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_WITH_TRIPLE_COUNT_FOR_SOURCE_WITH_LIMITS_DISTINCT(
            "SELECT ?dataType (COUNT(?value) as ?instances) WHERE { { SELECT DISTINCT ?x ?value ?dataType WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?value. BIND (datatype(?value) as ?dataType)} LIMIT <limit> }} GROUP BY ?dataType", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x <property> ?value. FILTER (lang(?value) != \"\") }", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_DISTINCT(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT DISTINCT ?x ?value WHERE { ?x <property> ?value. FILTER (lang(?value) != \"\") }} }", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT ?x ?value WHERE {?x <property> ?value.} LIMIT <limit> } FILTER (lang(?value) != \\\"\\\") }", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_WITH_LIMITS_DISTINCT(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT DISTINCT ?x ?value WHERE {?x <property> ?value.} LIMIT <limit> } FILTER (lang(?value) != \\\"\\\") }", QueryType.LARGE
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE(
            "SELECT (COUNT(?value) as ?instances) WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. FILTER (lang(?value) != \"\") }", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_DISTINCT(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT DISTINCT ?x ?value WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. FILTER (lang(?value) != \"\") }}}", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT ?x ?value WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. } LIMIT <limit> } FILTER (lang(?value) != \"\") }", QueryType.SMALL
    ),
    FIND_PROPERTY_DATA_TYPE_LANG_STRING_FOR_SOURCE_WITH_LIMITS_DISTINCT(
            "SELECT (COUNT(?value) as ?instances) WHERE { { SELECT DISTINCT ?x ?value WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value. } LIMIT <limit> } FILTER (lang(?value) != \"\") }", QueryType.SMALL
    ),

    FIND_PROPERTY_MAX_CARDINALITY(
            "SELECT ?x WHERE { ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1", QueryType.LARGE
    ),

    FIND_PROPERTY_MAX_CARDINALITY_FOR_SOURCE(
            "SELECT ?x WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?value1. ?x <property> ?value2. FILTER (?value1 != ?value2) } LIMIT 1", QueryType.SMALL
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY(
            "SELECT ?y WHERE { ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1", QueryType.LARGE
    ),

    FIND_INVERSE_PROPERTY_MAX_CARDINALITY_FOR_TARGET(
            "SELECT ?y WHERE { ?y <classificationProperty> <targetClass>. ?x1 <property> ?y. ?x2 <property> ?y. FILTER (?x1 != ?x2) } LIMIT 1", QueryType.SMALL
    ),

    FIND_PROPERTY_MIN_CARDINALITY(
            "SELECT ?x WHERE { ?x <classificationProperty> <sourceClass>. OPTIONAL { ?x ?prop ?value. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 ", QueryType.SMALL
    ),

    FIND_INVERSE_PROPERTY_MIN_CARDINALITY(
            "SELECT ?y WHERE { ?y <classificationProperty> <targetClass>. OPTIONAL { ?x ?prop ?y. FILTER (?prop = <property>) } FILTER (!BOUND(?prop)) } LIMIT 1 ", QueryType.SMALL
    ),

    FIND_PROPERTY_FOLLOWERS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { <valuesClause> { SELECT DISTINCT ?x where { [] <property> ?x } } ?x ?p2 [] } GROUP BY ?p2", QueryType.LARGE
    ),
    FIND_PROPERTY_FOLLOWERS_WITH_LIMITS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { <valuesClause> { SELECT DISTINCT ?x where { [] <property> ?x } LIMIT <limit> } ?x ?p2 [] } GROUP BY ?p2", QueryType.LARGE
    ),
    FIND_PROPERTY_OUTGOING_PROPERTIES(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { <valuesClause> { SELECT DISTINCT ?x WHERE { ?x <property> [] } } ?x ?p2 [] } GROUP BY ?p2", QueryType.LARGE
    ),
    FIND_PROPERTY_OUTGOING_PROPERTIES_WITH_LIMITS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { <valuesClause> { SELECT DISTINCT ?x WHERE { ?x <property> [] } LIMIT <limit> } ?x ?p2 [] } GROUP BY ?p2", QueryType.LARGE
    ),
    FIND_PROPERTY_INCOMING_PROPERTIES(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { <valuesClause> { SELECT DISTINCT ?x WHERE { [] <property> ?x . FILTER(!isLiteral(?x))} } [] ?p2 ?x } GROUP BY ?p2", QueryType.LARGE
    ),
    FIND_PROPERTY_INCOMING_PROPERTIES_WITH_LIMITS(
            "SELECT ?p2 (COUNT(?x) as ?instances) WHERE { <valuesClause> { SELECT DISTINCT ?x WHERE { [] <property> ?x . FILTER(!isLiteral(?x))} LIMIT <limit> } [] ?p2 ?x } GROUP BY ?p2", QueryType.LARGE
    ),

    FIND_PROPERTIES(
            "SELECT DISTINCT ?property WHERE {?x ?property ?y}", QueryType.LARGE
    ),
    FIND_PROPERTIES_WITH_TRIPLE_COUNT(
            "SELECT ?property (COUNT(?x) as ?instances) WHERE {?x ?property ?y} GROUP BY ?property", QueryType.LARGE
    ),
    FIND_PROPERTIES_WITH_TRIPLE_COUNT_DISTINCT(
            "SELECT ?property (COUNT(DISTINCT ?x) as ?instances) WHERE {?x ?property ?y} GROUP BY ?property", QueryType.LARGE
    ),
    FIND_PROPERTIES_FOR_CLASS(
            "SELECT DISTINCT ?property WHERE { { SELECT ?x ?property ?y WHERE { ?x <classificationProperty> <sourceClass> . ?x ?property ?y . } } }", QueryType.LARGE
    ),
    FIND_PROPERTIES_FOR_CLASS_WITH_LIMIT(
            "SELECT DISTINCT ?property WHERE { { SELECT ?x ?property ?y WHERE { ?x <classificationProperty> <sourceClass> . ?x ?property ?y . } LIMIT <limit> } }", QueryType.LARGE
    ),

    FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationProperty> ?class. } GROUP BY ?class", QueryType.LARGE
    ),
    FIND_PROPERTY_SOURCES_WITH_TRIPLE_COUNT_DISTINCT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x ?y ?class WHERE { ?x <property> ?y. ?x <classificationProperty> ?class. }}} GROUP BY ?class", QueryType.LARGE
    ),
    FIND_PROPERTY_SOURCES_WITHOUT_TRIPLE_COUNT(
            "SELECT DISTINCT ?class WHERE {?x <property> ?y. ?x <classificationProperty> ?class. }", QueryType.LARGE
    ),
    FIND_PROPERTY_SOURCE_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationProperty> <sourceClass>. }", QueryType.SMALL
    ),
    FIND_PROPERTY_SOURCE_TRIPLE_COUNT_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x ?y WHERE {?x <property> ?y. ?x <classificationProperty> <sourceClass>. }}}", QueryType.SMALL
    ),
    FIND_PROPERTY_SOURCE_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT (COUNT(?x) as ?instances) WHERE { { SELECT ?x WHERE {?x <classificationProperty> <sourceClass>. } LIMIT <limit> } ?x <property> ?y. }", QueryType.SMALL
    ),
    FIND_PROPERTY_SOURCE_TRIPLE_COUNT_WITH_LIMITS_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y WHERE {{ SELECT DISTINCT ?x WHERE {?x <classificationProperty> <sourceClass>. } LIMIT <limit> } ?x <property> ?y. }}}", QueryType.SMALL
    ),

    CHECK_CLASS_AS_PROPERTY_SOURCE(
            "SELECT ?x WHERE {?x <property> ?y. ?x <classificationProperty> <sourceClass>. } LIMIT 1", QueryType.SMALL
    ),

    FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?y <classificationProperty> ?class. } GROUP BY ?class", QueryType.LARGE
    ),
    FIND_PROPERTY_TARGETS_WITH_TRIPLE_COUNT_DISTINCT(
            "SELECT ?class (COUNT(?y) as ?instances) WHERE { {SELECT DISTINCT ?x ?y ?class WHERE {?x <property> ?y. ?y <classificationProperty> ?class. }}} GROUP BY ?class", QueryType.LARGE
    ),
    FIND_PROPERTY_TARGETS_WITHOUT_TRIPLE_COUNT(
            "SELECT DISTINCT ?class WHERE {?x <property> ?y. ?y <classificationProperty> ?class. } ", QueryType.LARGE
    ),
    FIND_PROPERTY_TARGET_TRIPLE_COUNT(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?y <classificationProperty> <targetClass>. }", QueryType.SMALL
    ),
    FIND_PROPERTY_TARGET_TRIPLE_COUNT_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x WHERE { ?x <property> ?y. ?y <classificationProperty> <targetClass>. }}}", QueryType.SMALL
    ),
    FIND_PROPERTY_TARGET_TRIPLE_COUNT_WITH_LIMITS(
            "SELECT (COUNT(?x) as ?instances) WHERE { { SELECT ?y WHERE {?y <classificationProperty> <targetClass>. } LIMIT <limit> } ?x <property> ?y. }", QueryType.SMALL
    ),
    FIND_PROPERTY_TARGET_TRIPLE_COUNT_WITH_LIMITS_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { SELECT DISTINCT ?x ?y WHERE {{ SELECT DISTINCT ?y WHERE {?y <classificationProperty> <targetClass>. } LIMIT <limit> } ?x <property> ?y. }}", QueryType.SMALL
    ),

    CHECK_CLASS_AS_PROPERTY_TARGET(
            "SELECT ?y WHERE {?x <property> ?y. ?y <classificationProperty> <targetClass>. } LIMIT 1", QueryType.SMALL
    ),

    FIND_PROPERTY_SOURCE_TARGET_PAIRS(
            "SELECT ?sourceClass ?targetClass (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertySource> ?sourceClass. ?y <classificationPropertyTarget> ?targetClass. } GROUP BY ?sourceClass ?targetClass", QueryType.LARGE
    ),
    FIND_PROPERTY_SOURCE_TARGET_PAIRS_DISTINCT(
            "SELECT ?sourceClass ?targetClass (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x ?y ?sourceClass ?targetClass WHERE {?x <property> ?y. ?x <classificationPropertySource> ?sourceClass. ?y <classificationPropertyTarget> ?targetClass. }}} GROUP BY ?sourceClass ?targetClass", QueryType.LARGE
    ),
    FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_SOURCE(
            "SELECT ?targetClass (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. ?y <classificationPropertyTarget> ?targetClass. } GROUP BY ?targetClass", QueryType.SMALL
    ),
    FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_SOURCE_DISTINCT(
            "SELECT ?targetClass (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y ?targetClass WHERE {?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. ?y <classificationPropertyTarget> ?targetClass .}}} GROUP BY ?targetClass", QueryType.SMALL
    ),
    FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. ?y <classificationPropertyTarget> <targetClass>.}", QueryType.SMALL
    ),
    FIND_PROPERTY_SOURCE_TARGET_PAIRS_FOR_SPECIFIC_CLASSES_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y WHERE {?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. ?y <classificationPropertyTarget> <targetClass>.}}}", QueryType.SMALL
    ),

    COUNT_PROPERTY_URL_VALUES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. FILTER(!isLiteral(?y)) }", QueryType.LARGE
    ),
    COUNT_PROPERTY_URL_VALUES_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { { SELECT DISTINCT ?x ?y WHERE {?x <property> ?y. FILTER(!isLiteral(?y)) }}}", QueryType.LARGE
    ),
    CHECK_PROPERTY_URL_VALUES(
            "SELECT ?y where {?x <property> ?y. FILTER(!isLiteral(?y)) } LIMIT 1", QueryType.LARGE
    ),
    FIND_PROPERTY_URL_VALUES_FOR_SOURCES(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationProperty> ?class. FILTER(!isLiteral(?y))} GROUP BY ?class", QueryType.LARGE
    ),
    FIND_PROPERTY_URL_VALUES_FOR_SOURCES_DISTINCT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y ?class WHERE {?x <property> ?y. ?x <classificationProperty> ?class. FILTER(!isLiteral(?y))}}} GROUP BY ?class", QueryType.LARGE
    ),
    COUNT_PROPERTY_URL_VALUES_FOR_SOURCE(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) }", QueryType.SMALL
    ),
    COUNT_PROPERTY_URL_VALUES_FOR_SOURCE_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) }}}", QueryType.SMALL
    ),
    CHECK_PROPERTY_URL_VALUES_FOR_SOURCE(
            "SELECT ?y WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) } LIMIT 1", QueryType.SMALL
    ),
    COUNT_PROPERTY_LITERAL_VALUES(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <property> ?y. FILTER(isLiteral(?y)) }", QueryType.LARGE
    ),
    COUNT_PROPERTY_LITERAL_VALUES_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y WHERE {?x <property> ?y. FILTER(isLiteral(?y)) }}}", QueryType.LARGE
    ),
    CHECK_PROPERTY_LITERAL_VALUES(
            "SELECT ?y WHERE {?x <property> ?y. FILTER(isLiteral(?y))} LIMIT 1", QueryType.LARGE
    ),
    FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE {?x <property> ?y. ?x <classificationProperty> ?class. FILTER(isLiteral(?y))} GROUP BY ?class", QueryType.LARGE
    ),
    FIND_PROPERTY_LITERAL_VALUES_FOR_SOURCES_DISTINCT(
            "SELECT ?class (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y ?class WHERE {?x <property> ?y. ?x <classificationProperty> ?class. FILTER(isLiteral(?y))}}} GROUP BY ?class", QueryType.LARGE
    ),
    COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE(
            "SELECT (COUNT(?x) as ?instances) WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(isLiteral(?y)) }", QueryType.SMALL
    ),
    COUNT_PROPERTY_LITERAL_VALUES_FOR_SOURCE_DISTINCT(
            "SELECT (COUNT(?x) as ?instances) WHERE { {SELECT DISTINCT ?x ?y WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(isLiteral(?y)) }}}", QueryType.SMALL
    ),
    CHECK_PROPERTY_LITERAL_VALUES_FOR_SOURCE(
            "SELECT ?y WHERE {?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(isLiteral(?y)) } LIMIT 1", QueryType.SMALL
    ),

    FIND_CLOSED_RANGE_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y <classificationProperty> ?c} FILTER(!BOUND(?c)) } LIMIT 1", QueryType.LARGE
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY(
            "SELECT ?x ?y WHERE { ?x <property> ?y. OPTIONAL {?x <classificationProperty> ?c} FILTER(!BOUND(?c)) } LIMIT 1", QueryType.LARGE
    ),
    FIND_CLOSED_RANGE_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?x <classificationProperty> <sourceClass>. ?x <property> ?y. FILTER(!isLiteral(?y)) OPTIONAL {?y <classificationPropertyTarget> ?c} FILTER(!BOUND(?c)) } LIMIT 1", QueryType.SMALL
    ),
    FIND_CLOSED_DOMAIN_FOR_PROPERTY_AND_CLASS(
            "SELECT ?x ?y WHERE { ?y <classificationProperty> <targetClass>. ?x <property> ?y. OPTIONAL {?x <classificationPropertySource> ?c} FILTER(!BOUND(?c)) } LIMIT 1", QueryType.SMALL
    ),

    CHECK_DOMAIN_FOR_PROPERTY(
            "SELECT ?x WHERE { ?x <property> ?y. OPTIONAL {?x <classificationProperty> ?class1. FILTER (?class1 = <sourceClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1", QueryType.SMALL
    ),
    CHECK_RANGE_FOR_PROPERTY(
            "SELECT ?x WHERE { ?x <property> ?y. OPTIONAL {?y <classificationProperty> ?class1. FILTER (?class1 = <targetClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1", QueryType.SMALL
    ),
    CHECK_RANGE_FOR_PAIR_SOURCE(
            "SELECT ?x WHERE { ?x <property> ?y. ?x <classificationPropertySource> <sourceClass>.  OPTIONAL {?y <classificationPropertyTarget> ?class1. FILTER (?class1 = <targetClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1", QueryType.SMALL
    ),
    CHECK_DOMAIN_FOR_PAIR_TARGET(
            "SELECT ?x WHERE { ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>.  OPTIONAL {?x <classificationPropertySource> ?class1. FILTER (?class1 = <sourceClass>)}  FILTER(!BOUND(?class1)) } LIMIT 1", QueryType.SMALL
    ),

    CHECK_PRINCIPAL_SOURCE(
            "SELECT ?x WHERE { ?x <property> ?y. ?x <classificationPropertySource> <sourceClass>. OPTIONAL {?x <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1", QueryType.SMALL
    ),
    CHECK_PRINCIPAL_TARGET(
            "SELECT ?x WHERE { ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>. OPTIONAL {?y <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1", QueryType.SMALL
    ),
    CHECK_PRINCIPAL_TARGET_FOR_SOURCE(
            "SELECT ?x ?y WHERE { ?x <classificationPropertySource> <sourceClass>. ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>. OPTIONAL {?y <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1", QueryType.SMALL
    ),
    CHECK_PRINCIPAL_SOURCE_FOR_TARGET(
            "SELECT ?x ?y WHERE { ?x <classificationPropertySource> <sourceClass>. ?x <property> ?y. ?y <classificationPropertyTarget> <targetClass>. OPTIONAL {?x <classificationPropertyOther> ?cc. FILTER (customFilter)} FILTER (!BOUND(?cc))} LIMIT 1", QueryType.SMALL
    ),

    FIND_LABEL(
            "SELECT (STR(?z) as ?value) (LANG(?z) as ?language) WHERE { ?x ?y ?z. FILTER(?x = <resource1>) FILTER(?y = <resource2>) }", QueryType.LARGE
    ),
    FIND_LABEL_WITH_LANG(
            "SELECT (STR(?z) as ?value) (LANG(?z) as ?language) WHERE { ?x ?y ?z. FILTER(?x = <resource1>) FILTER(?y = <resource2>) FILTER (customFilter) }", QueryType.LARGE
    ),

    FIND_INSTANCE_NAMESPACES(
            "SELECT DISTINCT ?x WHERE {?x <classificationProperty> <classA>. } LIMIT 1000", QueryType.LARGE
    ),

    FIND_INSTANCE_NAMESPACES_FOR_CLASS(
            "SELECT ?nspace (COUNT(?subject) AS ?nsCount) WHERE { ?subject <classificationProperty> <classA>. FILTER (isIRI(?subject)) BIND(REPLACE(STR(?subject), \"(.*[/#]).*\", \"$1\") AS ?nspace) } GROUP BY ?nspace ORDER BY DESC(?nsCount)", QueryType.LARGE
    ),
    FIND_INSTANCE_NAMESPACES_FOR_CLASS_WITH_LIMIT(
            "SELECT ?nspace (COUNT(?subject) AS ?nsCount) WHERE { { SELECT ?subject WHERE { ?subject <classificationProperty> <classA>. FILTER (isIRI(?subject)) } LIMIT <limit> } BIND(REPLACE(STR(?subject), \"(.*[/#]).*\", \"$1\") AS ?nspace) } GROUP BY ?nspace ORDER BY DESC(?nsCount)", QueryType.LARGE
    ),

    FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_SUBJECTS(
            "SELECT ?nspace (COUNT(?subject) AS ?nsCount) WHERE { ?subject <property> ?object . FILTER (isIRI(?subject)) BIND(REPLACE(STR(?subject), \"(.*[/#]).*\", \"$1\") AS ?nspace)} GROUP BY ?nspace ORDER BY DESC(?nsCount)", QueryType.LARGE
    ),
    FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_SUBJECTS_WITH_LIMIT(
            "SELECT ?nspace (COUNT(?subject) AS ?nsCount) WHERE { { SELECT ?subject WHERE { ?subject <property> ?object . FILTER (isIRI(?subject)) } LIMIT <limit> } BIND(REPLACE(STR(?subject), \"(.*[/#]).*\", \"$1\") AS ?nspace)} GROUP BY ?nspace ORDER BY DESC(?nsCount)", QueryType.LARGE
    ),

    FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_OBJECTS(
            "SELECT ?nspace (COUNT(?object) AS ?nsCount) WHERE { ?subject <property> ?object . FILTER (isIRI(?object)) BIND(REPLACE(STR(?object), \"(.*[/#]).*\", \"$1\") AS ?nspace)} GROUP BY ?nspace ORDER BY DESC(?nsCount)", QueryType.LARGE
    ),
    FIND_INSTANCE_NAMESPACES_FOR_PROPERTY_OBJECTS_WITH_LIMIT(
            "SELECT ?nspace (COUNT(?object) AS ?nsCount) WHERE { { SELECT ?object WHERE { ?subject <property> ?object . FILTER (isIRI(?object)) } LIMIT <limit> } BIND(REPLACE(STR(?object), \"(.*[/#]).*\", \"$1\") AS ?nspace)} GROUP BY ?nspace ORDER BY DESC(?nsCount)", QueryType.LARGE
    ),

    ENDPOINT_HEALTH_CHECK(
           "SELECT * WHERE {?a ?b ?c} LIMIT 1", QueryType.SMALL
    ),

    ;

    public enum QueryType {LARGE, SMALL}

    @Getter
    private final String sparqlQuery;

    @Getter
    private final QueryType queryType;

    SchemaExtractorQueries(@Nonnull String sparqlQuery, @Nonnull QueryType queryType) {
        this.sparqlQuery = sparqlQuery;
        this.queryType = queryType;
    }

}
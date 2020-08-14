package lv.lumii.obis.schema.services.enhancer;

import lombok.Setter;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.common.dto.SparqlEndpointConfig;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.enhancer.dto.OWLOntologyEnhancerRequest;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

import static lv.lumii.obis.schema.constants.SchemaConstants.DEFAULT_MAX_CARDINALITY;
import static lv.lumii.obis.schema.constants.SchemaConstants.DEFAULT_MIN_CARDINALITY;

@Service
public class OWLOntologyEnhancer {

    @Autowired
    @Setter
    private SparqlEndpointProcessor sparqlEndpointProcessor;

    @Nonnull
    public Schema enhanceSchema(@Nonnull Schema inputSchema, @Nonnull OWLOntologyEnhancerRequest enhancerRequest) {
        SparqlEndpointConfig endpointConfig = new SparqlEndpointConfig(enhancerRequest.getEndpointUrl(), enhancerRequest.getGraphName(), false);

        Map<String, Long> allProperties = getAllProperties(endpointConfig, enhancerRequest);

        updateClassInstanceCount(inputSchema, endpointConfig);
        updateAbstractProperties(inputSchema.getAttributes(), allProperties);
        updateAbstractProperties(inputSchema.getAssociations(), allProperties);
        updateDataTypePropertyDomains(inputSchema, endpointConfig);
        updateObjectTypePropertyDomains(inputSchema, endpointConfig);
        updateObjectTypePropertyRanges(inputSchema, endpointConfig);
        updateObjectTypePropertyDomainRangePairs(inputSchema, endpointConfig);

        if (BooleanUtils.isTrue(enhancerRequest.getCalculateCardinalities())) {
            processCardinalities(inputSchema.getAttributes(), endpointConfig);
            processCardinalities(inputSchema.getAssociations(), endpointConfig);
        }

        buildEnhancerProperties(enhancerRequest, inputSchema);

        return inputSchema;
    }

    @Nonnull
    private Map<String, Long> getAllProperties(@Nonnull final SparqlEndpointConfig endpointConfig, @Nonnull OWLOntologyEnhancerRequest enhancerRequest) {
        String query = SchemaEnhancerQueries.FIND_ALL_PROPERTIES_WITH_INSTANCE_COUNT;
        if (enhancerRequest.getAbstractPropertyThreshold() != null) {
            query = SchemaEnhancerQueries.FIND_ALL_PROPERTIES_WITH_INSTANCE_COUNT_WITH_LIMIT;
            query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_INSTANCES_COUNT_MIN, enhancerRequest.getAbstractPropertyThreshold().toString());
        }
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_ALL_PROPERTIES_WITH_INSTANCE_COUNT", query);
        Map<String, Long> allProperties = new HashMap<>();
        for (QueryResult queryResult : queryResults) {
            String propertyName = queryResult.get(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY);
            String instancesCountStr = queryResult.get(SchemaEnhancerQueries.QUERY_BINDING_NAME_INSTANCES_COUNT);
            allProperties.put(propertyName, SchemaUtil.getLongValueFromString(instancesCountStr));
        }
        return allProperties;
    }

    private void updateAbstractProperties(@Nonnull List<? extends SchemaProperty> properties, @Nonnull final Map<String, Long> allProperties) {
        properties.forEach(property -> {
            if (!allProperties.containsKey(property.getFullName())) {
                property.setIsAbstract(Boolean.TRUE);
            } else {
                property.setIsAbstract(Boolean.FALSE);
                property.setInstanceCount(allProperties.get(property.getFullName()));
            }
        });
    }

    private void updateClassInstanceCount(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        inputSchema.getClasses().forEach(schemaClass -> {
            String query = SchemaEnhancerQueries.FIND_CLASS_INSTANCE_COUNT;
            query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_DOMAIN_CLASS, schemaClass.getFullName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_CLASS_INSTANCE_COUNT", query);
            if (!queryResults.isEmpty()) {
                String instancesCountStr = queryResults.get(0).get(SchemaEnhancerQueries.QUERY_BINDING_NAME_INSTANCES_COUNT);
                schemaClass.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));
                if (schemaClass.getInstanceCount() == 0) {
                    schemaClass.setIsAbstract(true);
                }
            } else {
                schemaClass.setIsAbstract(true);
            }
        });
    }

    private void updateDataTypePropertyDomains(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        inputSchema.getAttributes().stream()
                .filter(schemaAttribute -> BooleanUtils.isNotTrue(schemaAttribute.getIsAbstract()) && noActualClassAssignment(schemaAttribute.getSourceClasses()))
                .forEach(schemaAttribute -> {
                    String query = SchemaEnhancerQueries.FIND_DATA_TYPE_PROPERTY_DOMAINS;
                    query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY, schemaAttribute.getFullName());
                    List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_DATA_TYPE_PROPERTY_DOMAINS", query);
                    if (!queryResults.isEmpty()) {
                        Set<String> newDomains = getDomainsFromQueryResults(queryResults);
                        newDomains = getQualifiedDomains(newDomains, inputSchema);
                        newDomains = getCleanedDomains(newDomains, inputSchema);
                        if (!newDomains.isEmpty()) {
                            schemaAttribute.getSourceClasses().addAll(newDomains);
                        }
                    } else {
                        schemaAttribute.setIsAbstract(true);
                    }
                });
    }

    private void updateObjectTypePropertyDomains(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        inputSchema.getAssociations().stream()
                .filter(schemaRole -> BooleanUtils.isNotTrue(schemaRole.getIsAbstract()) && noActualDomainInClassPair(schemaRole.getClassPairs()))
                .forEach(schemaRole -> {
                    String targetClass = schemaRole.getClassPairs().get(0).getTargetClass();
                    String query = SchemaEnhancerQueries.FIND_OBJECT_TYPE_PROPERTY_DOMAINS;
                    query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY, schemaRole.getFullName());
                    query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_RANGE_CLASS, targetClass);
                    List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_OBJECT_TYPE_PROPERTY_DOMAINS", query);
                    if (!queryResults.isEmpty()) {
                        Set<String> newDomains = getDomainsFromQueryResults(queryResults);
                        newDomains = getQualifiedDomains(newDomains, inputSchema);
                        newDomains = getCleanedDomains(newDomains, inputSchema);
                        if (!newDomains.isEmpty()) {
                            schemaRole.getClassPairs().clear();
                            newDomains.forEach(newDomain -> schemaRole.getClassPairs().add(new ClassPair(newDomain, targetClass)));
                        }
                    } else {
                        schemaRole.setIsAbstract(true);
                    }
                });
    }

    private void updateObjectTypePropertyRanges(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        inputSchema.getAssociations().stream()
                .filter(schemaRole -> BooleanUtils.isNotTrue(schemaRole.getIsAbstract()) && noActualRangeInClassPair(schemaRole.getClassPairs()))
                .forEach(schemaRole -> {
                    String sourceClass = schemaRole.getClassPairs().get(0).getSourceClass();
                    String query = SchemaEnhancerQueries.FIND_OBJECT_TYPE_PROPERTY_RANGES;
                    query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY, schemaRole.getFullName());
                    query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_DOMAIN_CLASS, sourceClass);
                    List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_OBJECT_TYPE_PROPERTY_RANGES", query);
                    if (!queryResults.isEmpty()) {
                        Set<String> newRanges = getRangesFromQueryResults(queryResults);
                        newRanges = getQualifiedDomains(newRanges, inputSchema);
                        newRanges = getCleanedDomains(newRanges, inputSchema);
                        if (!newRanges.isEmpty()) {
                            schemaRole.getClassPairs().clear();
                            newRanges.forEach(newRange -> schemaRole.getClassPairs().add(new ClassPair(sourceClass, newRange)));
                        }
                    } else {
                        schemaRole.setIsAbstract(true);
                    }
                });
    }

    private void updateObjectTypePropertyDomainRangePairs(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        inputSchema.getAssociations().stream()
                .filter(schemaRole -> BooleanUtils.isNotTrue(schemaRole.getIsAbstract()) && noActualDomainRangePair(schemaRole.getClassPairs()))
                .forEach(schemaRole -> {
                    String query = SchemaEnhancerQueries.FIND_OBJECT_TYPE_PROPERTY_DOMAINS_RANGES;
                    query = query.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY, schemaRole.getFullName());
                    List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_OBJECT_TYPE_PROPERTY_DOMAINS_RANGES", query);
                    if (!queryResults.isEmpty()) {
                        Set<String> newDomains = getDomainsFromQueryResults(queryResults);
                        newDomains = getQualifiedDomains(newDomains, inputSchema);
                        newDomains = getCleanedDomains(newDomains, inputSchema);
                        Set<String> newRanges = getRangesFromQueryResults(queryResults);
                        newRanges = getQualifiedDomains(newRanges, inputSchema);
                        newRanges = getCleanedDomains(newRanges, inputSchema);
                        if (!newDomains.isEmpty() && !newRanges.isEmpty()) {
                            schemaRole.getClassPairs().clear();
                            for (String newDomain : newDomains) {
                                for (String newRange : newRanges) {
                                    if (hasQueryResultsDomainAndRange(queryResults, newDomain, newRange)) {
                                        schemaRole.getClassPairs().add(new ClassPair(newDomain, newRange));
                                    }
                                }
                            }
                        }
                    } else {
                        schemaRole.setIsAbstract(true);
                    }
                });
    }

    private Set<String> getDomainsFromQueryResults(@Nonnull List<QueryResult> queryResults) {
        return queryResults.stream()
                .map(queryResult -> queryResult.get(SchemaEnhancerQueries.QUERY_BINDING_NAME_DOMAIN_CLASS))
                .filter(className -> StringUtils.isNotEmpty(className) && !SchemaConstants.THING_URI.equalsIgnoreCase(className))
                .collect(Collectors.toSet());
    }

    private Set<String> getRangesFromQueryResults(@Nonnull List<QueryResult> queryResults) {
        return queryResults.stream()
                .map(queryResult -> queryResult.get(SchemaEnhancerQueries.QUERY_BINDING_NAME_RANGE_CLASS))
                .filter(className -> StringUtils.isNotEmpty(className) && !SchemaConstants.THING_URI.equalsIgnoreCase(className))
                .collect(Collectors.toSet());
    }

    private boolean hasQueryResultsDomainAndRange(@Nonnull List<QueryResult> queryResults, @Nonnull String domain, @Nonnull String range) {
        return queryResults.stream()
                .anyMatch(queryResult ->
                        domain.equalsIgnoreCase(queryResult.get(SchemaEnhancerQueries.QUERY_BINDING_NAME_DOMAIN_CLASS))
                                && range.equalsIgnoreCase(queryResult.get(SchemaEnhancerQueries.QUERY_BINDING_NAME_RANGE_CLASS)));
    }

    private Set<String> getQualifiedDomains(@Nonnull Set<String> newDomains, @Nonnull Schema inputSchema) {
        Map<String, Set<String>> mapOfSuperClasses = inputSchema.getClasses().stream()
                .collect(Collectors.toMap(SchemaClass::getFullName, SchemaClass::getSuperClasses));
        return newDomains.stream()
                .filter(potentialDomain -> qualifiesAsNewDomain(potentialDomain, newDomains, mapOfSuperClasses))
                .collect(Collectors.toSet());
    }

    private Set<String> getCleanedDomains(@Nonnull Set<String> newDomains, @Nonnull Schema inputSchema) {
        Map<String, Set<String>> mapOfEquivalentClasses = inputSchema.getClasses().stream()
                .collect(Collectors.toMap(SchemaClass::getFullName, SchemaClass::getEquivalentClasses));
        return newDomains.stream()
                .filter(potentialDomain -> qualifiesAsEquivalentClass(potentialDomain, newDomains, mapOfEquivalentClasses))
                .collect(Collectors.toSet());
    }

    private boolean qualifiesAsNewDomain(@Nonnull String potentialDomain, @Nonnull Set<String> allPotentialDomains, @Nonnull Map<String, Set<String>> mapOfClasses) {
        // if current schema classes does not contain new domain, mark it as non-qualified
        if (!mapOfClasses.containsKey(potentialDomain)) {
            return false;
        }
        // if potential domain does not have any real super classes, mark it as qualified
        Set<String> superClasses = mapOfClasses.get(potentialDomain);
        if (noActualClassAssignment(superClasses)) {
            return true;
        }
        // if none of potential domain super class is represented in potential domain list, mark it as qualified
        boolean isRepresented = superClasses.stream().anyMatch(allPotentialDomains::contains);

        return !isRepresented;
    }

    private boolean qualifiesAsEquivalentClass(@Nonnull String potentialDomain, @Nonnull Set<String> allPotentialDomains, @Nonnull Map<String, Set<String>> mapOfClasses) {
        // if potential domain does not have any equivalent classes, mark it as qualified
        Set<String> equivalentClasses = mapOfClasses.get(potentialDomain);
        if (noActualClassAssignment(equivalentClasses)) {
            return true;
        }
        // if at least one potential domain equivalent class is represented in potential domain list, mark it as qualified
        return equivalentClasses.stream().anyMatch(allPotentialDomains::contains);
    }

    private boolean noActualClassAssignment(@Nonnull Set<String> classes) {
        return classes.isEmpty() || (classes.size() == 1 && classes.contains(SchemaConstants.THING_URI));
    }

    private boolean noActualDomainInClassPair(@Nonnull List<ClassPair> classPairs) {
        return classPairs.size() == 1
                && (StringUtils.isEmpty(classPairs.get(0).getSourceClass()) || SchemaConstants.THING_URI.equalsIgnoreCase(classPairs.get(0).getSourceClass()))
                && (StringUtils.isNotEmpty(classPairs.get(0).getTargetClass()) && !SchemaConstants.THING_URI.equalsIgnoreCase(classPairs.get(0).getTargetClass()));
    }

    private boolean noActualRangeInClassPair(@Nonnull List<ClassPair> classPairs) {
        return classPairs.size() == 1
                && (StringUtils.isEmpty(classPairs.get(0).getTargetClass()) || SchemaConstants.THING_URI.equalsIgnoreCase(classPairs.get(0).getTargetClass()))
                && (StringUtils.isNotEmpty(classPairs.get(0).getSourceClass()) && !SchemaConstants.THING_URI.equalsIgnoreCase(classPairs.get(0).getSourceClass()));
    }

    private boolean noActualDomainRangePair(@Nonnull List<ClassPair> classPairs) {
        return classPairs.isEmpty() || classPairs.size() == 1
                && (StringUtils.isEmpty(classPairs.get(0).getTargetClass()) || SchemaConstants.THING_URI.equalsIgnoreCase(classPairs.get(0).getTargetClass()))
                && (StringUtils.isEmpty(classPairs.get(0).getSourceClass()) || SchemaConstants.THING_URI.equalsIgnoreCase(classPairs.get(0).getSourceClass()));
    }

    private void processCardinalities(@Nonnull List<? extends SchemaProperty> properties, @Nonnull final SparqlEndpointConfig endpointConfig) {
        properties.forEach(property -> {
            setMaxCardinality(property, endpointConfig);
            setMinCardinality(property, endpointConfig);
        });
    }

    private void setMaxCardinality(@Nonnull SchemaProperty property, @Nonnull final SparqlEndpointConfig endpointConfig) {
        if (property.getMaxCardinality() != null || BooleanUtils.isTrue(property.getIsAbstract())) {
            return;
        }
        String query = SchemaEnhancerQueries.FIND_PROPERTY_MAX_CARDINALITY.replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY, property.getFullName());
        List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_PROPERTY_MAX_CARDINALITY", query);
        if (queryResults.isEmpty()) {
            property.setMaxCardinality(1);
            return;
        }
        property.setMaxCardinality(DEFAULT_MAX_CARDINALITY);
    }

    private void setMinCardinality(@Nonnull SchemaProperty property, @Nonnull final SparqlEndpointConfig endpointConfig) {
        if (property.getMinCardinality() != null || BooleanUtils.isTrue(property.getIsAbstract())) {
            return;
        }
        Integer minCardinality = 1;
        List<QueryResult> queryResults;
        Set<String> uniqueDomains;
        if (property instanceof SchemaRole) {
            uniqueDomains = ((SchemaRole) property).getClassPairs().stream().map(ClassPair::getSourceClass).filter(Objects::nonNull).collect(Collectors.toSet());
        } else {
            uniqueDomains = ((SchemaAttribute) property).getSourceClasses().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        }
        for (String domain : uniqueDomains) {
            if (DEFAULT_MIN_CARDINALITY.equals(minCardinality)) {
                break;
            }
            String query = SchemaEnhancerQueries.FIND_PROPERTY_MIN_CARDINALITY.
                    replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_PROPERTY, property.getFullName()).
                    replace(SchemaEnhancerQueries.QUERY_BINDING_NAME_DOMAIN_CLASS, domain);
            queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_PROPERTY_MIN_CARDINALITY", query);
            if (!queryResults.isEmpty()) {
                minCardinality = DEFAULT_MIN_CARDINALITY;
            }
        }
        property.setMinCardinality(minCardinality);
    }

    private void buildEnhancerProperties(@Nonnull OWLOntologyEnhancerRequest request, @Nonnull Schema schema) {
        schema.getParameters().add(
                new SchemaParameter(SchemaParameter.PARAM_NAME_ENDPOINT, request.getEndpointUrl()));
        schema.getParameters().add(
                new SchemaParameter(SchemaParameter.PARAM_NAME_GRAPH_NAME, request.getGraphName()));
        if (request.getAbstractPropertyThreshold() != null) {
            schema.getParameters().add(
                    new SchemaParameter(SchemaParameter.PARAM_NAME_ABSTRACT_PROPERTY_THRESHOLD, request.getAbstractPropertyThreshold().toString()));
        }
        schema.getParameters().add(
                new SchemaParameter(SchemaParameter.PARAM_NAME_CALCULATE_CARDINALITIES, request.getCalculateCardinalities() != null ? request.getCalculateCardinalities().toString() : "false"));
    }
}

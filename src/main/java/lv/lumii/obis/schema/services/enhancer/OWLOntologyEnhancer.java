package lv.lumii.obis.schema.services.enhancer;

import lombok.Setter;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.ClassPair;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.model.SchemaClass;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.QueryResult;
import lv.lumii.obis.schema.services.common.SparqlEndpointConfig;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.enhancer.dto.OWLOntologyEnhancerRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OWLOntologyEnhancer {

    @Autowired
    @Setter
    private SparqlEndpointProcessor sparqlEndpointProcessor;

    @Nonnull
    public Schema enhanceSchema(@Nonnull Schema inputSchema, @Nonnull OWLOntologyEnhancerRequest enhancerRequest) {
        SparqlEndpointConfig endpointConfig = new SparqlEndpointConfig(enhancerRequest.getEndpointUrl(), enhancerRequest.getGraphName(), false);

        updateClassInstanceCount(inputSchema, endpointConfig);
        updateDataTypePropertyDomains(inputSchema, endpointConfig);
        updateObjectTypePropertyDomainRangePairs(inputSchema, endpointConfig);

        return inputSchema;
    }

    private void updateClassInstanceCount(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        inputSchema.getClasses().forEach(schemaClass -> {
            String query = SchemaEnhancerQuery.FIND_CLASS_INSTANCE_COUNT;
            query = query.replace(SchemaEnhancerQuery.QUERY_BINDING_NAME_DOMAIN_CLASS, schemaClass.getFullName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_CLASS_INSTANCE_COUNT", query);
            if (!queryResults.isEmpty()) {
                String instancesCountStr = queryResults.get(0).get(SchemaEnhancerQuery.QUERY_BINDING_NAME_INSTANCES_COUNT);
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
                .filter(schemaAttribute -> noActualClassAssignment(schemaAttribute.getSourceClasses()))
                .forEach(schemaAttribute -> {
                    String query = SchemaEnhancerQuery.FIND_DATA_TYPE_PROPERTY_DOMAINS;
                    query = query.replace(SchemaEnhancerQuery.QUERY_BINDING_NAME_PROPERTY, schemaAttribute.getFullName());
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

    private void updateObjectTypePropertyDomainRangePairs(@Nonnull Schema inputSchema, @Nonnull final SparqlEndpointConfig endpointConfig) {
        // process associations with missing domains
        inputSchema.getAssociations().stream()
                .filter(schemaRole -> noActualDomainInClassPair(schemaRole.getClassPairs()))
                .forEach(schemaRole -> {
                    String targetClass = schemaRole.getClassPairs().get(0).getTargetClass();
                    String query = SchemaEnhancerQuery.FIND_OBJECT_TYPE_PROPERTY_DOMAINS;
                    query = query.replace(SchemaEnhancerQuery.QUERY_BINDING_NAME_PROPERTY, schemaRole.getFullName());
                    query = query.replace(SchemaEnhancerQuery.QUERY_BINDING_NAME_RANGE_CLASS, targetClass);
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

    private Set<String> getDomainsFromQueryResults(@Nonnull List<QueryResult> queryResults) {
        return queryResults.stream()
                .map(queryResult -> queryResult.get(SchemaEnhancerQuery.QUERY_BINDING_NAME_DOMAIN_CLASS))
                .filter(className -> StringUtils.isNotEmpty(className) && !SchemaConstants.THING_URI.equalsIgnoreCase(className))
                .collect(Collectors.toSet());
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

}

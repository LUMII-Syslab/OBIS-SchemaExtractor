package lv.lumii.obis.schema.services.extractor;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.constants.SchemaConstants;
import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.common.QueryResult;
import lv.lumii.obis.schema.services.extractor.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries.*;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

/**
 * Service to get data by executing many small queries, specific to some class.
 * For example - ask not all data type properties but for one specific class.
 */
@Slf4j
@Service
public class SchemaExtractorManyQueries extends SchemaExtractor {

	@Override
	protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequest request) {
		List<QueryResult> queryResults;
		for(SchemaClass classA: classes){
            String query = SchemaExtractorQueries.FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS.getSparqlQuery();
            query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, classA.getFullName());
            queryResults = sparqlEndpointProcessor.read(request, FIND_INTERSECTION_CLASSES_FOR_KNOWN_CLASS.name(), query);
            updateGraphOfClassesWithNeighbors(classA.getFullName(), queryResults, graphOfClasses, request);
            queryResults.clear();
        }
	}

	@Override
	protected Map<String, SchemaPropertyNodeInfo> findAllDataTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request) {
		Map<String, SchemaPropertyNodeInfo> properties = new HashMap<>();
		List<QueryResult> queryResults;
		for(SchemaClass domainClass: classes){
			String query = SchemaExtractorQueries.FIND_ALL_DATATYPE_PROPERTIES_FOR_KNOWN_CLASS.getSparqlQuery();
			query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getFullName());
			queryResults = sparqlEndpointProcessor.read(request, FIND_ALL_DATATYPE_PROPERTIES_FOR_KNOWN_CLASS.name(), query);
			processAllDataTypeProperties(domainClass.getFullName(), properties, queryResults);
			queryResults.clear();
		}

		if(isFalse(request.getExcludePropertiesWithoutClasses())){
			queryResults = sparqlEndpointProcessor.read(request, FIND_ALL_DATATYPE_PROPERTIES_WITHOUT_DOMAIN);
			processAllDataTypeProperties(null, properties, queryResults);
			queryResults.clear();
		}
		return properties;
	}

	@Override
	protected Map<String, SchemaPropertyNodeInfo> findAllObjectTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request) {
		Map<String, SchemaPropertyNodeInfo> properties = new HashMap<>();
		List<QueryResult> queryResults;
		for(SchemaClass domainClass: classes){
			String query = SchemaExtractorQueries.FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE_FOR_KNOWN_CLASS.getSparqlQuery();
			query = query.replace(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN, domainClass.getFullName());
			queryResults = sparqlEndpointProcessor.read(request, FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE_FOR_KNOWN_CLASS.name(), query);
			processAllObjectTypePropertiesWithKnownDomain(domainClass.getFullName(), queryResults, properties, request);
			queryResults.clear();
		}
		if(isFalse(request.getExcludePropertiesWithoutClasses())){
			queryResults = sparqlEndpointProcessor.read(request, FIND_OBJECT_PROPERTIES_WITHOUT_DOMAIN);
			processAllObjectTypePropertiesWithKnownDomain(null, queryResults, properties, request);
			queryResults.clear();
			queryResults = sparqlEndpointProcessor.read(request, FIND_OBJECT_PROPERTIES_WITHOUT_RANGE);
			processAllObjectTypePropertiesWithoutRange(queryResults, properties, request);
			queryResults.clear();
		}
		return properties;
	}

	private void updateGraphOfClassesWithNeighbors(@Nonnull String domainClass, @Nonnull List<QueryResult> queryResults, @Nonnull Map<String, SchemaClassNodeInfo> graphOfClasses,
													 @Nonnull SchemaExtractorRequest request){
		for(QueryResult queryResult: queryResults){
			String classB = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_B);
			if(classB != null && !isExcludedResource(classB, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())){
				if(graphOfClasses.containsKey(domainClass)){
					graphOfClasses.get(domainClass).getNeighbors().add(classB);
				}
			}
		}
	}

	private void processAllDataTypeProperties(@Nullable String domainClass, @Nonnull Map<String, SchemaPropertyNodeInfo> properties, @Nonnull List<QueryResult> queryResults){
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
			String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

			if(StringUtils.isEmpty(propertyName)){
				continue;
			}
			if(!properties.containsKey(propertyName)){
				properties.put(propertyName, new SchemaPropertyNodeInfo());
			}
			SchemaPropertyNodeInfo property = properties.get(propertyName);
			SchemaClassNodeInfo classInfo = new SchemaClassNodeInfo();
			classInfo.setClassName(domainClass);
			classInfo.setInstanceCount(Long.valueOf(instances));
			property.getDomainClasses().add(classInfo);
		}
	}

	private void processAllObjectTypePropertiesWithKnownDomain(@Nullable String domainClass, @Nonnull List<QueryResult> queryResults,
															   @Nonnull Map<String, SchemaPropertyNodeInfo> properties,
															   @Nonnull SchemaExtractorRequest request){
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
			String rangeClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_RANGE);
			String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

			if(StringUtils.isEmpty(propertyName)){
				continue;
			}
			if(rangeClass != null && isExcludedResource(rangeClass, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())){
				continue;
			}

			addObjectProperty(propertyName, domainClass, rangeClass, instances, properties);
		}
	}

	private void processAllObjectTypePropertiesWithoutRange(@Nonnull List<QueryResult> queryResults,
															 @Nonnull Map<String, SchemaPropertyNodeInfo> properties,
															 @Nonnull SchemaExtractorRequest request){
		for(QueryResult queryResult: queryResults){
			String propertyName = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_PROPERTY);
			String domainClass = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_CLASS_DOMAIN);
			String instances = queryResult.get(SchemaConstants.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);

			if(StringUtils.isEmpty(propertyName)){
				continue;
			}
			if(domainClass != null && isExcludedResource(domainClass, request.getExcludeSystemClasses(), request.getExcludeMetaDomainClasses())){
				continue;
			}

			addObjectProperty(propertyName, domainClass, null, instances, properties);
		}
	}

}

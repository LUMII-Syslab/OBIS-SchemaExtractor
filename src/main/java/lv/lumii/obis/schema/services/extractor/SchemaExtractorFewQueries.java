package lv.lumii.obis.schema.services.extractor;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.extractor.dto.*;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.*;

import static lv.lumii.obis.schema.services.extractor.SchemaExtractorQueries.*;

/**
 * Service to get data by executing just a few but complex queries.
 * For example - ask all possible intersection classes, all possible data type or object properties.
 */
@Slf4j
@Service
public class SchemaExtractorFewQueries extends SchemaExtractor {

	@Override
	protected void findIntersectionClassesAndUpdateClassNeighbors(@Nonnull List<SchemaClass> classes, @Nonnull Map<String, SchemaClassNodeInfo> graphOfClasses, @Nonnull SchemaExtractorRequest request) {
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_INTERSECTION_CLASSES);
		updateGraphOfClassesWithNeighbors(queryResults, graphOfClasses, request);
		queryResults.clear();
	}

	@Override
	protected Map<String, SchemaPropertyNodeInfo> findAllDataTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request) {
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_ALL_DATATYPE_PROPERTIES);
		Map<String, SchemaPropertyNodeInfo> properties = new HashMap<>();
		processAllDataTypeProperties(queryResults, properties, request);
		return properties;
	}

	@Override
	protected Map<String, SchemaPropertyNodeInfo> findAllObjectTypeProperties(@Nonnull List<SchemaClass> classes, @Nonnull SchemaExtractorRequest request) {
		List<QueryResult> queryResults = sparqlEndpointProcessor.read(request, FIND_OBJECT_PROPERTIES_WITH_DOMAIN_RANGE);
		Map<String, SchemaPropertyNodeInfo> properties = new HashMap<>();
		processAllObjectTypeProperties(queryResults, properties, request);
		return properties;
	}

}

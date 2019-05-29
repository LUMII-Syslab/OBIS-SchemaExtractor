package lv.lumii.obis.schema.services;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.dto.SchemaExtractorRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.engine.http.QueryEngineHTTP;

import lv.lumii.obis.schema.services.dto.QueryResult;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;

@Slf4j
@Service
public class SparqlEndpointProcessor {

	@Nonnull
	public List<QueryResult> read(@Nonnull SchemaExtractorRequest request, @Nonnull SchemaExtractorQueries queryItem){
		return read(request, queryItem.name(), queryItem.getSparqlQuery());
	}

	@Nonnull
	public List<QueryResult> read(@Nonnull SchemaExtractorRequest request, @Nonnull String queryName, @Nonnull String sparqlQuery){
		Query query = QueryFactory.create(sparqlQuery);
		if(request.getEnableLogging()){
			log.info(queryName + "\n" + sparqlQuery);
		}
		QueryExecution qexec = getQueryExecutor(request, query);
		List<QueryResult> queryResults = new ArrayList<>();
		try {
			ResultSet results = qexec.execSelect();
			
			if(results == null){
				return queryResults;
			}
			
			while (results.hasNext()){
				QuerySolution s = results.next();
				if(s != null){
					QueryResult queryResult = new QueryResult();
					for(String name: results.getResultVars()){
						RDFNode value = s.get(name);
						String val = null;
						if(value != null){
							val = value.toString().split("\\^\\^")[0];
						}
						queryResult.add(name, val);
					}
					queryResults.add(queryResult);
				}
			}
			return queryResults;
		} catch (Exception e){
			log.error("Schema Extractor Exception - " + e.getMessage());
			e.printStackTrace();
			return queryResults;
		} finally {
			qexec.close();
		}		
	}
	
	private QueryExecution getQueryExecutor(@Nonnull SchemaExtractorRequest request, @Nonnull Query query){
		QueryEngineHTTP qexec;
		if(StringUtils.isEmpty(request.getEndpointUrl())){
			qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(request.getEndpointUrl(), query);
		} else {
			qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(request.getEndpointUrl(), query, request.getGraphName());
		}
		qexec.addDefaultGraph(request.getGraphName());
		return qexec;
	}

}

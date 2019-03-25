package lv.lumii.obis.schema.services;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
public class SparqlEndpointProcessor {

	private String sparqlEndpointUrl;
	private String graphName;
	
	public SparqlEndpointProcessor(String sparqlEndpointUrl, String graphName) {
		super();
		this.sparqlEndpointUrl = sparqlEndpointUrl;
		this.graphName = graphName;
	}

	public List<QueryResult> read(String sparqlQuery, String queryName, boolean logEnabled){
		Query query = QueryFactory.create(sparqlQuery);
		if(logEnabled){
			log.info(queryName + "\n" + sparqlQuery);
		}
		QueryExecution qexec = getQueryExecutor(query);	
		List<QueryResult> queryResults = new ArrayList<>();
		try {
			ResultSet results = qexec.execSelect();
			
			if(results == null){
				return null;
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
	
	public QueryExecution getQueryExecutor(Query query){
		QueryEngineHTTP qexec;
		if(graphName == null || StringUtils.isEmpty(graphName)){
			qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(sparqlEndpointUrl, query);
		} else {
			qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(sparqlEndpointUrl, query, graphName);
		}
		qexec.addDefaultGraph(graphName);
		return qexec;
	}

}

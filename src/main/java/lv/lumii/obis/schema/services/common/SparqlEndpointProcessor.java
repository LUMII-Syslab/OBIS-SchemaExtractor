package lv.lumii.obis.schema.services.common;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.common.dto.QueryResponse;
import lv.lumii.obis.schema.services.common.dto.QueryResult;
import lv.lumii.obis.schema.services.common.dto.SparqlEndpointConfig;
import lv.lumii.obis.schema.services.extractor.v1.SchemaExtractorQueries;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.engine.http.QueryEngineHTTP;

import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Slf4j
@Service
public class SparqlEndpointProcessor {

    private static final int RETRY_COUNT = 2;

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequestDto request, @Nonnull SchemaExtractorQueries queryItem) {
        return read(request, queryItem.name(), queryItem.getSparqlQuery());
    }

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequestDto request, @Nonnull lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries queryItem) {
        return read(request, queryItem.name(), queryItem.getSparqlQuery());
    }

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequestDto request, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        return read(new SparqlEndpointConfig(request.getEndpointUrl(), request.getGraphName(), request.getEnableLogging()),
                queryName, sparqlQuery);
    }

    @Nonnull
    public QueryResponse read(@Nonnull SchemaExtractorRequestDto request, @Nonnull String queryName, @Nonnull String sparqlQuery, boolean withRetry) {
        return read(new SparqlEndpointConfig(request.getEndpointUrl(), request.getGraphName(), request.getEnableLogging()),
                queryName, sparqlQuery, withRetry);
    }

    @Nonnull
    public List<QueryResult> read(@Nonnull SparqlEndpointConfig request, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        return read(request, queryName, sparqlQuery, true).getResults();
    }

    @Nonnull
    private QueryResponse read(@Nonnull SparqlEndpointConfig request, @Nonnull String queryName, @Nonnull String sparqlQuery, boolean withRetry) {
        QueryResponse response = new QueryResponse();

        Query query;
        try {
            query = QueryFactory.create(sparqlQuery);
        } catch (Exception e) {
            log.error(String.format("SPARQL query syntax or formatting exception for the query %s", queryName));
            log.error("\n" + sparqlQuery);
            response.setHasErrors(true);
            return response;
        }

        if (request.isEnableLogging()) {
            log.info(queryName + "\n" + sparqlQuery);
        }

        List<QueryResult> results = requestData(request, query, queryName, sparqlQuery, 1, withRetry);
        if (results != null) {
            response.setHasErrors(false);
            response.setResults(results);
        } else {
            response.setHasErrors(true);
            response.setResults(new ArrayList<>());
        }

        return response;
    }

    @Nullable
    private List<QueryResult> requestData(@Nonnull SparqlEndpointConfig request, @Nonnull Query query, @Nonnull String queryName, @Nonnull String sparqlQuery,
                                          int attempt, boolean withRetry) {
        List<QueryResult> queryResults = null;
        ResultSet resultSet;
        QueryEngineHTTP queryExecutor = getQueryExecutor(request.getEndpointUrl(), request.getGraphName(), query);
        try {
            resultSet = queryExecutor.execSelect();
            if (attempt > 1) {
                log.info(String.format("SPARQL Endpoint returned a valid response after an attempt number %d", attempt));
            }
            queryResults = new ArrayList<>();
            if (resultSet != null) {
                while (resultSet.hasNext()) {
                    QuerySolution s = resultSet.next();
                    if (s != null) {
                        QueryResult queryResult = new QueryResult();
                        for (String name : resultSet.getResultVars()) {
                            RDFNode value = s.get(name);
                            String val = null;
                            if (value != null) {
                                val = value.toString().split("\\^\\^")[0];
                            }
                            queryResult.add(name, val);
                        }
                        queryResults.add(queryResult);
                    }
                }
            }
        } catch (Exception e) {
            if (withRetry) {
                log.error(String.format("SPARQL Endpoint Exception status '%s'. This was attempt number %d for the query %s", e.getMessage(), attempt, queryName));
                log.error("\n" + sparqlQuery);
                if (attempt < RETRY_COUNT) {
                    return requestData(request, query, queryName, sparqlQuery, ++attempt, withRetry);
                }
            } else {
                log.error(String.format("SPARQL Endpoint Exception status '%s' for the query %s", e.getMessage(), queryName));
                log.error("\n" + sparqlQuery);
            }
        } finally {
            queryExecutor.close();
        }
        return queryResults;
    }

    private QueryEngineHTTP getQueryExecutor(@Nonnull String endpointUrl, @Nullable String graphName, @Nonnull Query query) {
        QueryEngineHTTP qexec;
        if (StringUtils.isEmpty(graphName)) {
            qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(endpointUrl, query);
        } else {
            qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(endpointUrl, query, graphName);
            qexec.addDefaultGraph(graphName);
        }
        //qexec.setTimeout(300000L, 100000L);
        return qexec;
    }

}

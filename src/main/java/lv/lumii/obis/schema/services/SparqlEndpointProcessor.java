package lv.lumii.obis.schema.services;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.dto.SchemaExtractorRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.engine.http.QueryEngineHTTP;

import lv.lumii.obis.schema.services.dto.QueryResult;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Slf4j
@Service
public class SparqlEndpointProcessor {

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequest request, @Nonnull SchemaExtractorQueries queryItem) {
        return read(request, queryItem.name(), queryItem.getSparqlQuery());
    }

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequest request, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        List<QueryResult> queryResults = new ArrayList<>();

        Query query;
        try {
            query = QueryFactory.create(sparqlQuery);
        } catch (Exception e) {
            log.error(String.format("SPARQL query syntax or formatting exception for the query %s", queryName));
            log.error("\n" + sparqlQuery);
            return queryResults;
        }

        if (request.getEnableLogging()) {
            log.info(queryName + "\n" + sparqlQuery);
        }

        QueryEngineHTTP qexec = getQueryExecutor(request, query);
        ResultSet results = requestData(qexec, queryName, sparqlQuery);
        if (results == null) {
            return queryResults;
        }
        while (results.hasNext()) {
            QuerySolution s = results.next();
            if (s != null) {
                QueryResult queryResult = new QueryResult();
                for (String name : results.getResultVars()) {
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
        qexec.close();
        return queryResults;
    }

    @Nullable
    private ResultSet requestData(@Nonnull QueryEngineHTTP qexec, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        return requestData(qexec, 0, queryName, sparqlQuery);
    }

    @Nullable
    private ResultSet requestData(@Nonnull QueryEngineHTTP qexec, int attempt, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        ResultSet resultSet = null;
        try {
            resultSet = qexec.execSelect();
            if (attempt > 0) {
                log.error(String.format("SPARQL Endpoint returned a valid response after an attempt number %d", attempt + 1));
            }
        } catch (Exception e) {
            log.error(String.format("SPARQL Endpoint Exception status '%s'. This was attempt number %d for the query %s", e.getMessage(), attempt + 1, queryName));
            log.error("\n" + sparqlQuery);
            if (attempt < 1) {
                qexec.addParam("timeout", "1200000");
                return requestData(qexec, ++attempt, queryName, sparqlQuery);
            }
            e.printStackTrace();
        }
        return resultSet;
    }

    private QueryEngineHTTP getQueryExecutor(@Nonnull SchemaExtractorRequest request, @Nonnull Query query) {
        QueryEngineHTTP qexec;
        if (StringUtils.isEmpty(request.getEndpointUrl())) {
            qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(request.getEndpointUrl(), query);
        } else {
            qexec = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(request.getEndpointUrl(), query, request.getGraphName());
        }
        qexec.addDefaultGraph(request.getGraphName());
        return qexec;
    }

}

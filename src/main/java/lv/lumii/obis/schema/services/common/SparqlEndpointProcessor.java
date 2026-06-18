package lv.lumii.obis.schema.services.common;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.SparqlEndpointException;
import lv.lumii.obis.schema.services.common.dto.*;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.apache.jena.sparql.exec.http.QuerySendMode;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries.*;
import static lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries.QueryType;

@Slf4j
@Service
public class SparqlEndpointProcessor {

    private static final int RETRY_COUNT = 2;

    private static final String[] FALLBACK_ACCEPT_HEADERS = {
            "application/sparql-results+json",
            "application/sparql-results+xml",
            "text/tab-separated-values",
            "text/csv",
            "application/json",
            "application/xml"
    };

    /**
     * Actual methods for SchemaExtractor V2
     */
    @Nonnull
    public QueryResponse read(@Nonnull SchemaExtractorRequestDto request, @Nonnull SparqlQueryBuilder queryBuilder) {
        Long timeout = calculateTimeout(request, queryBuilder);
        return read(new SparqlEndpointConfig(request.getCorrelationId(), request.getEndpointUrl(), request.getGraphName(), request.getEnableLogging(),
                request.getPostMethod(), request.getAcceptHeaderForSparqlResults(), timeout, request.getDelayOnFailure(), request.getWaitingTimeForEndpoint()), queryBuilder, true);
    }

    public boolean checkEndpointHealthAndStopExecutionOnError(@Nonnull SparqlEndpointConfig request, boolean applyWaiting) {
        boolean endpointIsHealthyWithoutRetries = true;
        AtomicBoolean isEndpointHealthy = new AtomicBoolean(checkEndpointHealthQuery(request));
        if (applyWaiting) {
            if (!isEndpointHealthy.get()) {
                endpointIsHealthyWithoutRetries = false;
                // wait 1 minute
                waitForEndpoint(request, 60 * 1000L);
                isEndpointHealthy.set(checkEndpointHealthQuery(request));
                if (!isEndpointHealthy.get()) {
                    // wait 15 minutes periods for the time <= request.getWaitingTimeForEndpoint()
                    // if getWaitingTimeForEndpoint == 0 then wait forever
                    long waitingIterations = (request.getWaitingTimeForEndpoint() != null && request.getWaitingTimeForEndpoint() > 15)
                            ? request.getWaitingTimeForEndpoint() / 15 : 1;
                    boolean waitForever = request.getWaitingTimeForEndpoint() == null || request.getWaitingTimeForEndpoint() <= 0;
                    while (waitingIterations > 0 || waitForever) {
                        if (!waitForever) waitingIterations--;
                        waitForEndpoint(request, 15 * 60 * 1000L);
                        isEndpointHealthy.set(checkEndpointHealthQuery(request));
                        if (isEndpointHealthy.get()) break;
                    }
                }
            }
        }
        if (isEndpointHealthy.get()) {
            if (request.getDelayOnFailure() != null && request.getDelayOnFailure() > 0L) {
                log.info(String.format("The endpoint [ %s ] is available and in working state - schema extractor execution [ %s ] will be resumed in %d seconds",
                        SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId(), request.getDelayOnFailure()));
                try {
                    Thread.sleep(request.getDelayOnFailure() * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                log.info(String.format("The endpoint [ %s ] is available and in working state - schema extractor execution [ %s ] is in progress",
                        SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId()));
            }
        } else {
            String stoppingError = String.format("The endpoint [ %s ] is not available, stopping the schema extraction for the request [ %s ]",
                    SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()), request.getCorrelationId());
            log.error(stoppingError);
            throw new SparqlEndpointException(stoppingError);
        }
        return endpointIsHealthyWithoutRetries;
    }

    public boolean checkEndpointHealthQuery(@Nonnull SchemaExtractorRequestDto request) {
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(ENDPOINT_HEALTH_CHECK.getSparqlQuery(), ENDPOINT_HEALTH_CHECK);
        SparqlEndpointConfig config = new SparqlEndpointConfig(request.getCorrelationId(), request.getEndpointUrl(), request.getGraphName(), request.getEnableLogging(),
                request.getPostMethod(), request.getAcceptHeaderForSparqlResults(), null, null, null);

        QueryResponse response = read(config, queryBuilder, false);

        if (isNotAcceptable(response)) {
            log.error("The endpoint does not support multi-value Accept headers");
            for (String acceptHeader : FALLBACK_ACCEPT_HEADERS) {
                log.info("Checking endpoint availability with Accept header = " + acceptHeader);
                config.setAcceptHeaderForSparqlResults(acceptHeader);
                response = read(config, queryBuilder, false);
                if (!isNotAcceptable(response)) {
                    break;
                }
            }
        }

        if (!response.hasErrors() && StringUtils.isNotEmpty(config.getAcceptHeaderForSparqlResults())) {
            request.setAcceptHeaderForSparqlResults(config.getAcceptHeaderForSparqlResults());
            log.info("The endpoint is using Accept header = " + request.getAcceptHeaderForSparqlResults());
        }

        return !response.hasErrors() && !response.getResults().isEmpty();
    }

    private boolean isNotAcceptable(QueryResponse response) {
        return response.hasErrors()
                && response.getQueryResponseError() != null
                && response.getQueryResponseError().getErrorStatusCode() == QueryResponseError.NOT_ACCEPTABLE_CODE
                && QueryResponseError.NOT_ACCEPTABLE_MSG.equals(response.getQueryResponseError().getResponseMessage());
    }

    private boolean checkEndpointHealthQuery(@Nonnull SparqlEndpointConfig config) {
        SparqlQueryBuilder queryBuilder = new SparqlQueryBuilder(ENDPOINT_HEALTH_CHECK.getSparqlQuery(), ENDPOINT_HEALTH_CHECK);
        QueryResponse response = read(config, queryBuilder, false);
        return !response.hasErrors() && !response.getResults().isEmpty();
    }

    private void waitForEndpoint(@Nonnull SparqlEndpointConfig request, @Nonnull Long sleepTime) {
        log.error(String.format("The endpoint is not healthy - [ %s ]. Stopping the execution [ %s ] and will retry after %d %s.",
                SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName()),
                request.getCorrelationId(), sleepTime / 1000 / 60, (sleepTime / 1000 / 60) == 1 ? "minute" : "minutes"));
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Nonnull
    private QueryResponse read(@Nonnull SparqlEndpointConfig request, @Nonnull SparqlQueryBuilder queryBuilder, boolean withRetry) {

        String query = queryBuilder.build();
        if (query == null) {
            QueryResponse response = new QueryResponse();
            response.setHasErrors(true);
            return response;
        }

        if (request.isEnableLogging()) {
            log.info(queryBuilder.getQueryName() + (request.getTimeout() != null ? " (timeout: " + request.getTimeout() + "s)" : "") + "\n" + queryBuilder.getQueryString());
        }

        return requestData(request, queryBuilder.getQueryName(), queryBuilder.getQueryString(), queryBuilder.getQueryType(), request.getTimeout(), 1, withRetry);
    }

    @Nonnull
    private QueryResponse requestData(@Nonnull SparqlEndpointConfig request, @Nonnull String queryName, @Nonnull String sparqlQuery, @Nullable QueryType queryType,
                                      @Nullable Long timeout, int attempt, boolean withRetry) {
        LocalDateTime startTime = LocalDateTime.now();
        QueryResponse response = new QueryResponse();
        List<QueryResult> queryResults = null;
        ResultSet resultSet;
        QueryExecutionHTTP queryExecutor = getQueryExecutor(request.getEndpointUrl(), request.getGraphName(), sparqlQuery, request.isPostRequest(),
                request.getAcceptHeaderForSparqlResults(), timeout);
        try {
            resultSet = queryExecutor.execSelect();
            if (attempt > 1) {
                log.info(String.format("SPARQL Endpoint returned a valid response after an attempt number %d", attempt));
            }
            queryResults = new ArrayList<>();
            if (resultSet != null) {
                List<String> resultVariables = resultSet.getResultVars();
                while (resultSet.hasNext()) {
                    buildQueryResultObject(queryResults, resultVariables, resultSet.next());
                }
            }
        } catch (Exception e) {
            if (e instanceof QueryExceptionHTTP) {
                response.setQueryResponseError(new QueryResponseError(((QueryExceptionHTTP) e).getStatusCode(), ((QueryExceptionHTTP) e).getResponseMessage()));
            } else {
                response.setQueryResponseError(new QueryResponseError(500, e.getMessage()));
            }
            if (withRetry) {
                log.error(String.format("SPARQL Endpoint Exception status '%s'. This was attempt number %d for the query %s", e.getMessage(), attempt, queryName));
                log.error("\n" + sparqlQuery);
                if (attempt < RETRY_COUNT) {
                    log.warn(String.format("SPARQL queries encountered errors, running validation queries to check the endpoint availability - [ %s ]",
                            SchemaUtil.getEndpointLinkText(request.getEndpointUrl(), request.getGraphName())));
                    boolean endpointIsHealthyWithoutRetries = checkEndpointHealthAndStopExecutionOnError(request, true);
                    if (!endpointIsHealthyWithoutRetries || !QueryType.SMALL.equals(queryType)) {
                        return requestData(request, queryName, sparqlQuery, queryType, timeout, ++attempt, withRetry);
                    }
                }
            } else {
                log.error(String.format("SPARQL Endpoint Exception status '%s' for the query %s", e.getMessage(), queryName));
                log.error("\n" + sparqlQuery);
            }
        } finally {
            queryExecutor.close();
        }

        LocalDateTime endTime = LocalDateTime.now();
        long executionTimeInSeconds = Duration.between(startTime, endTime).getSeconds();
        response.setExecutionTime(executionTimeInSeconds);

        if (queryResults != null) {
            response.setHasErrors(false);
            response.setResults(queryResults);
        } else {
            response.setHasErrors(true);
            response.setResults(new ArrayList<>());
        }

        return response;
    }

    private void buildQueryResultObject(@Nonnull List<QueryResult> queryResults, List<String> resultVariables, QuerySolution resultItem) {
        if (resultItem == null || resultVariables == null || resultVariables.isEmpty()) {
            return;
        }
        QueryResult queryResult = new QueryResult();
        for (String key : resultVariables) {
            RDFNode value = resultItem.get(key);
            if (value == null) {
                continue;
            }
            QueryResultObject queryResultObject = new QueryResultObject();
            if (value instanceof Resource) {
                Resource uriValue = value.asResource();
                queryResultObject.setValue(uriValue.getURI());
                queryResultObject.setFullName(uriValue.getURI());
                queryResultObject.setLocalName(uriValue.getLocalName());
                queryResultObject.setNamespace(uriValue.getNameSpace());
                queryResultObject.setIsLiteral(false);
            } else if (value instanceof Literal) {
                Literal literal = value.asLiteral();
                queryResultObject.setValue(literal.getString());
                queryResultObject.setFullName(literal.getString());
                queryResultObject.setLocalName(literal.getString());
                queryResultObject.setDataType(literal.getDatatypeURI());
                queryResultObject.setIsLiteral(true);
                if (StringUtils.isNotEmpty(queryResultObject.getDataType())) {
                    queryResultObject.setFullName("\"" + queryResultObject.getLocalName() + "\"^^<" + queryResultObject.getDataType() + ">");
                }
            } else {
                queryResultObject.setValue(value.toString().split("\\^\\^")[0]);
            }
            queryResult.addResultObject(key, queryResultObject);
        }
        queryResults.add(queryResult);
    }

    private QueryExecutionHTTP getQueryExecutor(@Nonnull String endpointUrl, @Nullable String graphName, @Nonnull String query, boolean isPostMethod,
                                                @Nullable String acceptHeaderForSparqlResults, @Nullable Long timeout) {

        QueryExecutionHTTPBuilder builder = QueryExecutionHTTP.create().endpoint(endpointUrl).queryString(query);

        // If no MIME type is configured, leave the Accept header unset so the SPARQL client uses its default content negotiation.
        if (StringUtils.isNotEmpty(acceptHeaderForSparqlResults)) {
            builder = builder.acceptHeader(acceptHeaderForSparqlResults);
        }
        if (StringUtils.isNotEmpty(graphName)) {
            builder = builder.addDefaultGraphURI(graphName);
        }
        if (isPostMethod) {
            builder = builder.sendMode(QuerySendMode.asPostForm);
        } else {
            builder = builder.sendMode(QuerySendMode.asGetAlways);
        }
        if (timeout != null) {
            builder.timeout(timeout * 1000);
        }
        return builder.build();
    }

    @Nullable
    private Long calculateTimeout(@Nonnull SchemaExtractorRequestDto request, @Nonnull SparqlQueryBuilder queryBuilder) {
        QueryType queryType = queryBuilder.getQueryType();
        if (QueryType.LARGE.equals(queryType) && request.getLargeQueryTimeout() > 0L) {
            return request.getLargeQueryTimeout();
        }
        if (QueryType.SMALL.equals(queryType) && request.getSmallQueryTimeout() > 0L) {
            return request.getSmallQueryTimeout();
        }
        return null;
    }

    /**
     * Deprecated methods for SchemaExtractor V1
     */

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequestDto request, @Nonnull lv.lumii.obis.schema.services.extractor.v1.SchemaExtractorQueries queryItem) {
        return read(request, queryItem.name(), queryItem.getSparqlQuery());
    }

    @Nonnull
    public List<QueryResult> read(@Nonnull SchemaExtractorRequestDto request, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        SparqlEndpointConfig config = new SparqlEndpointConfig(request.getCorrelationId(), request.getEndpointUrl(), request.getGraphName(), request.getEnableLogging(), request.getPostMethod(), null);
        config.setAcceptHeaderForSparqlResults(request.getAcceptHeaderForSparqlResults());
        return read(config, queryName, sparqlQuery);
    }

    @Nonnull
    public List<QueryResult> read(@Nonnull SparqlEndpointConfig request, @Nonnull String queryName, @Nonnull String sparqlQuery) {
        return read(request, queryName, sparqlQuery, true).getResults();
    }

    @Nonnull
    private QueryResponse read(@Nonnull SparqlEndpointConfig request, @Nonnull String queryName, @Nonnull String sparqlQuery, boolean withRetry) {

        Query query;
        try {
            query = QueryFactory.create(sparqlQuery);
        } catch (Exception e) {
            log.error(String.format("SPARQL query syntax or formatting exception for the query %s", queryName));
            log.error("\n" + sparqlQuery);
            QueryResponse response = new QueryResponse();
            response.setHasErrors(true);
            return response;
        }

        if (request.isEnableLogging()) {
            log.info(queryName + "\n" + sparqlQuery);
        }

        return requestData(request, queryName, sparqlQuery, null, null, 1, true);
    }

}

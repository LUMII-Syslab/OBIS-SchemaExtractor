package lv.lumii.obis.schema.services.common;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static lv.lumii.obis.schema.constants.SchemaConstants.SPARQL_QUERY_BINDING_NAME_DISTINCT;
import static lv.lumii.obis.schema.constants.SchemaConstants.SPARQL_QUERY_BINDING_NAME_DISTINCT_FULL;
import static lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto.DistinctQueryMode;

@Slf4j
public class SparqlQueryBuilder {

    private final String query;
    private final SchemaExtractorQueries backupQuery;
    private String resultQuery;
    private Map<String, String> contextMap;
    private String instanceValuesClause;
    private String loggedInstanceValuesClause;
    private int instanceCount;
    private int shownInstanceCount;

    public SparqlQueryBuilder(@Nullable String query, @Nonnull SchemaExtractorQueries backupQuery) {
        this.query = query;
        this.backupQuery = backupQuery;
    }

    public SparqlQueryBuilder withContextParam(@Nonnull String key, @Nullable String value) {
        if (contextMap == null) {
            contextMap = new HashMap<>();
        }
        contextMap.put(key, value);
        return this;
    }

    public SparqlQueryBuilder withContextParam(@Nonnull String key, @Nullable String value, @Nonnull Boolean isLiteralValue) {
        String formattedValue;
        if (BooleanUtils.isTrue(isLiteralValue)) {
            formattedValue = value;
        } else {
            formattedValue = SchemaUtil.addAngleBracketsToString(value);
        }
        return withContextParam(key, formattedValue);
    }

    public SparqlQueryBuilder withDistinct(@Nullable DistinctQueryMode distinctMode, @Nullable Long maxInstanceCountLimit, @Nullable Integer instanceCount) {
        if (DistinctQueryMode.yes.equals(distinctMode) ||
                (DistinctQueryMode.auto.equals(distinctMode) && instanceCount == null) ||
                (DistinctQueryMode.auto.equals(distinctMode) && maxInstanceCountLimit != null && instanceCount <= maxInstanceCountLimit)) {
            withContextParam(SPARQL_QUERY_BINDING_NAME_DISTINCT_FULL, SPARQL_QUERY_BINDING_NAME_DISTINCT);
        } else {
            withContextParam(SPARQL_QUERY_BINDING_NAME_DISTINCT_FULL, StringUtils.EMPTY);
        }
        return this;
    }

    // Keep full instance bindings for execution and attach bounded representations for every server-log path.
    public SparqlQueryBuilder withInstanceValues(List<String> values) {
        instanceCount = values.size();
        shownInstanceCount = Math.min(instanceCount, instanceCount <= 10 ? 2 : 5);
        instanceValuesClause = "VALUES ?x { " + String.join(" ", values) + " }";
        loggedInstanceValuesClause = "VALUES ?x { " + String.join(" ", values.subList(0, shownInstanceCount)) + " }";
        return withContextParam("<instanceValuesClause>", instanceValuesClause);
    }

    // Expose the abbreviated query separately so callers never execute the shortened diagnostic form.
    public String getQueryStringForLogging() {
        return forLogging(resultQuery);
    }

    // Abbreviate only registered instance clauses while preserving independent class and property scopes.
    private String forLogging(String text) {
        if (text == null || instanceValuesClause == null || shownInstanceCount == instanceCount) return text;
        return text.replace(instanceValuesClause, loggedInstanceValuesClause)
                + "\n[instance VALUES abbreviated: " + shownInstanceCount + " of " + instanceCount + " shown]";
    }
    public String getQueryName() {
        return this.backupQuery.name();
    }

    public SchemaExtractorQueries.QueryType getQueryType() {
        return this.backupQuery.getQueryType();
    }

    public String getQueryString() {
        return this.resultQuery;
    }

    public Map<String, String> getContextMap() {
        if (contextMap == null) {
            contextMap = new HashMap<>();
        }
        return contextMap;
    }

    @Nullable
    public String build() {
        String query = buildQuery(this.query);
        if (query == null) {
            log.info("External query is not defined or has errors, using built-in query " + this.backupQuery.name());
            query = buildQuery(this.backupQuery.getSparqlQuery());
        }
        return query;
    }

    @Nullable
    // Retain the attempted query for diagnostics while preventing parser failures from logging complete instance lists.
    private String buildQuery(@Nullable String queryToBuild) {
        if (StringUtils.isEmpty(queryToBuild)) {
            return null;
        }
        try {
            for (String param : this.getContextMap().keySet()) {
                queryToBuild = queryToBuild.replace(param, this.getContextMap().get(param));
            }
            this.resultQuery = queryToBuild;
            QueryFactory.create(queryToBuild);
            return this.resultQuery;
        } catch (QueryException e) {
            log.error(String.format("SPARQL query syntax or parsing exception for the query %s", this.backupQuery.name()));
            log.error("\n" + forLogging(queryToBuild));
        }
        return null;
    }
}

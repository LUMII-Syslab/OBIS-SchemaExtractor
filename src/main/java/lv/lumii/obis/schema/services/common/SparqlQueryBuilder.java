package lv.lumii.obis.schema.services.common;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.services.extractor.v2.SchemaExtractorQueries;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SparqlQueryBuilder {

    private final String query;
    private final SchemaExtractorQueries backupQuery;
    private String resultQuery;
    private Map<String, String> contextMap;

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

    public String getQueryName() {
        return this.backupQuery.name();
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
    public Query build() {
        Query query = buildQuery(this.query);
        if (query == null) {
            log.info("External query is not defined or has errors, using built-in query " + this.backupQuery.name());
            query = buildQuery(this.backupQuery.getSparqlQuery());
        }
        return query;
    }

    @Nullable
    private Query buildQuery(@Nullable String queryToBuild) {
        if (StringUtils.isEmpty(queryToBuild)) {
            return null;
        }
        try {
            for (String param : this.getContextMap().keySet()) {
                queryToBuild = queryToBuild.replace(param, this.getContextMap().get(param));
            }
            Query builtQuery = QueryFactory.create(queryToBuild);
            this.resultQuery = queryToBuild;
            return builtQuery;
        } catch (Exception e) {
            log.error(String.format("SPARQL query syntax or formatting exception for the query %s", this.backupQuery.name()));
            log.error("\n" + queryToBuild);
        }
        return null;
    }
}

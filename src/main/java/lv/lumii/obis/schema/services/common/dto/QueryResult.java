package lv.lumii.obis.schema.services.common.dto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

public class QueryResult {

    private Map<String, QueryResultObject> queryResultObjects;

    @Nullable
    public QueryResultObject getResultObject(@Nonnull String key) {
        return getResultObjects().get(key);
    }

    public void addResultObject(@Nonnull String key, @Nonnull QueryResultObject resultObject) {
        getResultObjects().put(key, resultObject);
    }

    @Deprecated
    @Nullable
    public String get(@Nonnull String key) {
        return getValue(key);
    }

    @Nullable
    public String getValue(@Nonnull String key) {
        return getResultObjects().get(key) != null ? getResultObjects().get(key).getValue() : null;
    }

    @Nullable
    public String getValueDataType(@Nonnull String key) {
        return getResultObjects().get(key) != null ? getResultObjects().get(key).getDataType() : null;
    }

    @Nullable
    public String getValueLocalName(@Nonnull String key) {
        return getResultObjects().get(key) != null ? getResultObjects().get(key).getLocalName() : null;
    }

    @Nullable
    public String getValueFullName(@Nonnull String key) {
        return getResultObjects().get(key) != null ? getResultObjects().get(key).getFullName() : null;
    }

    @Nullable
    public String getValueNamespace(String key) {
        return getResultObjects().get(key) != null ? getResultObjects().get(key).getNamespace() : null;
    }

    @Nonnull
    public Map<String, QueryResultObject> getResultObjects() {
        if (queryResultObjects == null) {
            queryResultObjects = new LinkedHashMap<>();
        }
        return queryResultObjects;
    }

}

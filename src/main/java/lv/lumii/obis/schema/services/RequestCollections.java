package lv.lumii.obis.schema.services;

import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Remove blank collection entries at request boundaries, before applying defaults. */
public final class RequestCollections {
    private RequestCollections() {}

    @SuppressWarnings("unchecked")
    public static <T> T normalize(T request) {
        return (T) normalizeValue(request, new IdentityHashMap<>());
    }

    private static boolean isBlank(Object value) {
        return value == null || value instanceof String text && text.codePoints().allMatch(
                c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF);
    }

    private static Object normalizeValue(Object value, IdentityHashMap<Object, Object> seen) {
        if (value == null || value instanceof String || value.getClass().isEnum()) return value;
        if (seen.containsKey(value)) return seen.get(value);

        if (value instanceof Collection<?> items) {
            Collection<Object> result = value instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
            seen.put(value, result);
            for (Object item : items) {
                if (!isBlank(item)) {
                    Object cleaned = normalizeValue(item, seen);
                    if (cleaned != null) result.add(cleaned);
                }
            }
            Object cleaned = result.isEmpty() ? null : result;
            seen.put(value, cleaned);
            return cleaned;
        }
        if (value.getClass().isArray()) {
            if (value.getClass().getComponentType().isPrimitive()) return value;
            ArrayList<Object> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                Object item = Array.get(value, i);
                if (!isBlank(item)) {
                    Object cleaned = normalizeValue(item, seen);
                    if (cleaned != null) items.add(cleaned);
                }
            }
            Object result = items.isEmpty() ? null : items.toArray(
                    (Object[]) Array.newInstance(value.getClass().getComponentType(), items.size()));
            seen.put(value, result);
            return result;
        }
        if (value instanceof Map<?, ?> items) {
            Map<Object, Object> result = new LinkedHashMap<>();
            seen.put(value, result);
            items.forEach((key, item) -> result.put(key, normalizeValue(item, seen)));
            return result;
        }
        // Inspect our mutable request DTOs, not files, framework objects, or JDK internals.
        if (value.getClass().getPackageName().startsWith("lv.lumii.obis.")) {
            seen.put(value, value);
            ReflectionUtils.doWithFields(value.getClass(), field -> {
                ReflectionUtils.makeAccessible(field);
                Object original = ReflectionUtils.getField(field, value);
                Object cleaned = normalizeValue(original, seen);
                if (cleaned != original) ReflectionUtils.setField(field, value, cleaned);
            }, field -> !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()));
        }
        return value;
    }
}

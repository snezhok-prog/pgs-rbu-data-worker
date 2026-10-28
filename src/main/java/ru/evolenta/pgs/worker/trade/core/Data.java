package ru.evolenta.pgs.worker.trade.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-compatible values with nulls retained; no object-to-string coercion. */
public final class Data {
    private Data() {}

    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> input))
            throw new IllegalArgumentException("Expected an object");
        var result = new LinkedHashMap<String, Object>();
        input.forEach(
                (key, item) -> {
                    if (!(key instanceof String text))
                        throw new IllegalArgumentException("Object keys must be strings");
                    result.put(text, item);
                });
        return result;
    }

    public static List<?> list(Object value) {
        if (!(value instanceof List<?> input))
            throw new IllegalArgumentException("Expected an array");
        return input;
    }

    public static String text(Object value) {
        if (value == null) return "";
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
            return value.toString();
        throw new IllegalArgumentException("Object or array cannot be converted to a cell string");
    }

    public static Map<String, Object> immutableMap(Map<String, Object> input) {
        var result = new LinkedHashMap<String, Object>();
        input.forEach((key, value) -> result.put(key, immutable(value)));
        return Collections.unmodifiableMap(result);
    }

    public static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) return immutableMap(object(map));
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            list.forEach(item -> result.add(immutable(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    public static Map<String, Object> merge(
            Map<String, Object> original, Map<String, Object> patch) {
        var result = new LinkedHashMap<>(original);
        patch.forEach(
                (key, value) -> {
                    Object before = result.get(key);
                    result.put(
                            key,
                            value instanceof Map<?, ?> && before instanceof Map<?, ?>
                                    ? merge(object(before), object(value))
                                    : value);
                });
        return result;
    }
}

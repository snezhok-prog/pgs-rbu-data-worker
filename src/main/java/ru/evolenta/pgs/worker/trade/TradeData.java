package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class TradeData {
    static final ObjectMapper JSON = new ObjectMapper();
    private TradeData() {}
    static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    static String key(Object value) { return text(value).toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " "); }
    static String base(Object value) { return text(value).replaceFirst("^\\d+\\.\\s*", ""); }
    static boolean present(Object value) { return value != null && !text(value).isEmpty(); }
    static List<?> list(Object raw) { return raw instanceof List<?> value ? value : List.of(); }
    static Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> value) value.forEach((k, v) -> {
            if (!(k instanceof String name)) throw new IllegalArgumentException("Non-string JSON object key");
            result.put(name, v);
        });
        return result;
    }
    static List<Map<String, Object>> maps(Object raw) { return list(raw).stream().map(TradeData::map).toList(); }
    static Object at(Object raw, String path) {
        Object current = raw;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> values) current = values.get(part);
            else if (current instanceof List<?> values && part.matches("\\d+") && Integer.parseInt(part) < values.size()) current = values.get(Integer.parseInt(part));
            else return null;
        }
        return current;
    }
    static List<Object> valuesAt(Object raw, String path) { return valuesAt(raw, path.split("\\."), 0); }
    private static List<Object> valuesAt(Object raw, String[] parts, int index) {
        if (raw instanceof List<?> items) {
            List<Object> result = new ArrayList<>();
            items.forEach(item -> result.addAll(valuesAt(item, parts, index)));
            return result;
        }
        if (index == parts.length) return raw == null ? List.of() : List.of(raw);
        return raw instanceof Map<?, ?> values ? valuesAt(values.get(parts[index]), parts, index + 1) : List.of();
    }
    static JsonNode resource(String name) {
        try (var stream = TradeData.class.getResourceAsStream("/trade/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing trade resource " + name);
            return JSON.readTree(stream);
        } catch (IOException error) { throw new IllegalStateException("Cannot read trade resource " + name, error); }
    }
    static String sha256(String text) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException error) { throw new IllegalArgumentException("Cannot encode trade data", error); }
    }
    static String operationTime(ru.evolenta.pgs.worker.trade.core.OperationContext context) {
        String time = text(context.attributes().get("operationTime"));
        try { return java.time.Instant.parse(time).toString(); }
        catch (java.time.DateTimeException error) { throw new IllegalArgumentException("Не определено стабильное время операции ISO Instant.", error); }
    }
    static Object compact(Object raw) {
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> result = new TreeMap<>();
            map(raw).forEach((name, value) -> { Object clean = compact(value); if (present(clean) && !(clean instanceof Map<?, ?> m && m.isEmpty()) && !(clean instanceof List<?> l && l.isEmpty())) result.put(name, clean); });
            return result;
        }
        if (raw instanceof List<?> values) return values.stream().map(TradeData::compact).filter(TradeData::present).toList();
        return raw;
    }
}

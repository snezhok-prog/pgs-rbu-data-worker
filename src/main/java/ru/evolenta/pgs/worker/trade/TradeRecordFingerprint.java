package ru.evolenta.pgs.worker.trade;

import java.util.Map;
import java.util.TreeMap;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

public final class TradeRecordFingerprint {
    private TradeRecordFingerprint() {}
    public static String of(Map<String, Object> record) { return ofValue(record); }
    public static String ofValue(Object value) { return sha256(TradeHashes.groovyJson(stable(value))); }
    private static Object stable(Object value) {
        if (value instanceof Map<?, ?>) { Map<String, Object> result = new TreeMap<>(); map(value).forEach((key, item) -> result.put(key, stable(item))); return result; }
        if (value instanceof java.util.List<?> items) return items.stream().map(TradeRecordFingerprint::stable).toList();
        return value;
    }
}

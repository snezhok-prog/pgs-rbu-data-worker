package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

final class TradeMappings {
    private final RegistryAccess registry;
    private final Map<String, Map<String, Object>> cache = new LinkedHashMap<>();
    TradeMappings(RegistryAccess registry) { this.registry = registry; }
    Map<String, Object> mapped(String group, Object value) {
        if (!present(value)) return null;
        if (group.equals("PRODUCT_TYPE")) return switch (text(value)) {
            case "Товары" -> Map.of("code", "Goods", "name", "Товары");
            case "Услуги" -> Map.of("code", "Services", "name", "Услуги");
            default -> throw new IllegalArgumentException("Недопустимый вид продукции: " + value);
        };
        if (group.equals("PAYMENT_PERIOD")) {
            String code = Map.of("ежемесячно", "monthly", "ежеквартально", "quarterly", "другое", "other", "раз в полгода", "semiannually", "поэтапно", "step").get(key(value));
            if (code == null) throw new IllegalArgumentException("Недопустимая периодичность внесения платежей: " + value);
            return Map.of("code", code, "name", text(value));
        }
        if (group.equals("PROJECT_STATUS")) {
            String code = Map.of("Утверждено", "StatusProjectPlaceApproved", "Черновик", "StatusProjectPlaceDraft").get(text(value));
            if (code == null) throw new IllegalArgumentException("Недопустимый статус проектного места: " + value);
            return Map.of("code", code, "name", text(value), "_id", text(dictionary("nsiUnifiedStatusProjectPlace", code).get("_id")));
        }
        JsonNode hit = null;
        var fields = TradeResources.MAPPINGS.path(group).fields();
        while (fields.hasNext()) { var entry = fields.next(); if (key(entry.getKey()).equals(key(value))) { hit = entry.getValue(); break; } }
        if (hit == null) throw new IllegalArgumentException("Значение «" + value + "» отсутствует в таблице соответствий " + group);
        Map<String, Object> result = hit.isObject() ? JSON.convertValue(hit, new TypeReference<Map<String, Object>>() {}) : new LinkedHashMap<>(Map.of("code", hit.asText(), "name", text(value)));
        String collection = Map.of("SPEC_PROJECT_STATUS", "nsiUnifiedStatusProjectPlace", "MARKET_TYPE", "nsiUnifiedspecialMarket", "MARKET_SPECIALIZATION", "nsiUnifiedspecialMarket").get(group);
        if (collection != null) {
            var current = dictionary(collection, text(result.get("code")));
            if (group.equals("MARKET_TYPE")) result.put("_id", current.get("_id"));
            else {
                var parent = dictionary(collection, text(result.get("parentCode")));
                if (!text(current.get("parentId")).equals(text(parent.get("_id")))) throw new IllegalArgumentException("Нарушена связь родителя справочника: " + group);
                result.put("parentId", parent.get("_id")); result.remove("parentCode");
            }
        }
        return result;
    }
    private Map<String, Object> dictionary(String collection, String code) {
        String key = collection + ':' + code;
        return cache.computeIfAbsent(key, ignored -> {
            List<Map<String, Object>> found = registry.search(collection, TradeContext.query("code", "eq", code), 2);
            if (found.size() != 1 || !present(found.getFirst().get("_id")) || !code.equals(text(found.getFirst().get("code")))) throw new IllegalArgumentException("В справочнике не найдена единственная запись: " + collection + "/" + code);
            return TradeContext.requireLive(found.getFirst(), text(found.getFirst().get("_id")));
        });
    }
    static BigDecimal number(Object value) {
        if (!present(value)) return null;
        String normalized = text(value).replace(" ", "").replace(',', '.');
        if (!normalized.matches("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?")) throw new IllegalArgumentException("Некорректное числовое значение: " + value);
        return new BigDecimal(normalized);
    }
    static String date(Object value) {
        if (!present(value)) return null;
        try { return LocalDate.parse(text(value), DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)).toString(); }
        catch (DateTimeException first) {
            try { return LocalDate.parse(text(value)).toString(); }
            catch (DateTimeException second) { throw new IllegalArgumentException("Некорректная дата: " + value, second); }
        }
    }
    static String ruDate(Object value) { String date = date(value); return date == null ? null : LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")); }
    static Boolean yesNo(Object value) {
        if (!present(value)) return null;
        return switch (key(value)) { case "да" -> true; case "нет" -> false; default -> throw new IllegalArgumentException("Ожидалось значение Да или Нет: " + value); };
    }
    static String time(Object value) {
        if (!present(value)) return null;
        String raw = text(value);
        if (raw.matches("(?:[01]?\\d|2[0-3]):[0-5]\\d")) return raw.length() == 4 ? "0" + raw : raw;
        BigDecimal fraction = number(raw);
        if (fraction.compareTo(BigDecimal.ZERO) < 0 || fraction.compareTo(BigDecimal.ONE) >= 0) throw new IllegalArgumentException("Некорректное время: " + value);
        int minutes = fraction.multiply(BigDecimal.valueOf(1440)).setScale(0, RoundingMode.HALF_UP).intValue() % 1440;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
    static Map<String, Object> address(Object value) {
        if (!present(value)) return null;
        var match = java.util.regex.Pattern.compile("^\\s*(\\d{6})(?:\\s*,\\s*|\\s+)(.*)$").matcher(text(value));
        return match.matches() ? Map.of("postalCode", match.group(1), "fullAddress", text(match.group(2))) : Map.of("fullAddress", text(value));
    }
}

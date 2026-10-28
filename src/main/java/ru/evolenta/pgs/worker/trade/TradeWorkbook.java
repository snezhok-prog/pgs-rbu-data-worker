package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Column;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Row;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Sheet;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

final class TradeWorkbook {
    final WorkbookDraft draft;
    TradeWorkbook(WorkbookDraft draft) { this.draft = draft; }
    Sheet sheet(String code) { return draft.sheets().stream().filter(s -> s.code().equals(code)).findFirst().orElse(null); }
    String sheetName(String code) { return text(map(draft.metadata().get("sheetNames")).get(code)); }
    static Object value(Row row, Column column) {
        if (column != null) for (var cell : row.cells()) if (cell.column().equals(column.column())) return cell.value();
        return null;
    }
    static String name(Column column) { return text(column.metadata().get("fieldName")); }
    static int order(String column) {
        int result = 0;
        for (char ch : column.replaceAll("[^A-Za-z]", "").toUpperCase(java.util.Locale.ROOT).toCharArray()) result = result * 26 + ch - 64;
        return result;
    }
    static List<Column> named(Sheet sheet, String field, String block, boolean strictBlock) {
        List<Column> candidates = sheet.columns().stream().filter(c -> key(name(c)).equals(key(field))).toList();
        if (candidates.isEmpty()) candidates = sheet.columns().stream().filter(c -> key(base(name(c))).equals(key(base(field)))).toList();
        if (!block.isEmpty()) {
            List<Column> preferred = candidates.stream().filter(c -> key(c.metadata().get("subBlock")).equals(key(block)) || key(c.metadata().get("block")).equals(key(block))).toList();
            if (!preferred.isEmpty() || strictBlock) candidates = preferred;
        }
        return candidates;
    }
    static Column column(Sheet sheet, JsonNode field) {
        List<Column> candidates = named(sheet, field.path("name").asText(), field.path("block").asText(), false);
        String attribute = field.path("attribute").asText();
        Column exact = candidates.stream().filter(c -> c.attribute().equals(attribute)).findFirst().orElse(null);
        if (exact != null) return exact;
        String ordinal = ordinal(field.path("name").asText());
        return candidates.stream().filter(c -> ordinal.isEmpty() || name(c).startsWith(ordinal)).findFirst().orElse(candidates.isEmpty() ? null : candidates.getFirst());
    }
    static Object related(Sheet sheet, Row row, JsonNode field, Column current, String requested, JsonNode params) {
        String preferred = params.path("condition_block").asText(field.path("block").asText());
        List<Column> columns = new ArrayList<>(named(sheet, requested, preferred, false));
        String ordinal = ordinal(field.path("name").asText());
        if (ordinal.isEmpty()) ordinal = ordinal(name(current));
        final String requestedOrdinal = ordinal;
        List<Column> sameOrdinal = columns.stream().filter(c -> name(c).startsWith(requestedOrdinal)).toList();
        if (!ordinal.isEmpty() && !sameOrdinal.isEmpty()) columns = new ArrayList<>(sameOrdinal);
        int currentOrder = order(current.column());
        if (params.path("search_direction").asText().equals("right")) {
            List<Column> right = columns.stream().filter(c -> order(c.column()) > currentOrder).toList();
            if (!right.isEmpty()) { columns = new ArrayList<>(right); columns.sort(Comparator.comparingInt(c -> order(c.column()))); }
        } else if (columns.size() > 1 && currentOrder > 0) columns.sort(Comparator.comparingInt(c -> Math.abs(order(c.column()) - currentOrder)));
        return columns.isEmpty() ? null : value(row, columns.getFirst());
    }
    static Object namedValue(Sheet sheet, Row row, String field, String block) {
        List<Column> columns = named(sheet, field, block, !block.isEmpty());
        for (Column column : columns) { Object value = value(row, column); if (present(value)) return value; }
        return null;
    }
    static Map<String, Object> relatedValues(Sheet sheet, Row row, JsonNode field, Column column, JsonNode params) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : List.of("condition_field", "other_field", "group_field")) {
            String requested = params.path(name).asText();
            if (!requested.isEmpty()) {
                JsonNode relation = params;
                if (name.equals("other_field")) relation = JSON.createObjectNode().put("condition_block", params.path("other_block").asText(field.path("block").asText()));
                values.put(requested, related(sheet, row, field, column, requested, relation));
            }
        }
        for (JsonNode requested : params.path("group_fields")) values.put(requested.asText(), related(sheet, row, field, column, requested.asText(), params));
        return values;
    }
    static List<Object> groupValues(Sheet sheet, Row row, Column column, JsonNode field, JsonNode params, String method, String organizationId) {
        List<Object> values = new ArrayList<>();
        if (method.equals("check_same_value_in_column")) { sheet.rows().forEach(r -> values.add(value(r, column))); return values; }
        if (method.equals("check_unique_within_group")) {
            String groupName = params.path("group_field").asText();
            String selected = groupName.equals("__selected_organization__") ? key(organizationId) : key(related(sheet, row, field, column, groupName, params));
            if (selected.isEmpty()) return values;
            for (Row candidate : sheet.rows()) {
                String other = groupName.equals("__selected_organization__") ? key(organizationId) : key(related(sheet, candidate, field, column, groupName, params));
                if (other.equals(selected)) values.add(value(candidate, column));
            }
            return values;
        }
        JsonNode names = params.path(method.equals("check_unique_in_row") ? "target_fields" : "group_fields");
        for (Column candidate : sheet.columns()) for (JsonNode requested : names) {
            if (key(base(name(candidate))).equals(key(base(requested.asText())))) { values.add(value(row, candidate)); break; }
        }
        return values;
    }
    private static String ordinal(String name) { var match = java.util.regex.Pattern.compile("^\\d+\\.").matcher(name); return match.find() ? match.group() : ""; }
}

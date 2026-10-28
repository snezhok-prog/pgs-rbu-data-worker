package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import ru.evolenta.pgs.worker.trade.core.ValidationIssue;
import ru.evolenta.pgs.worker.trade.core.ValidationReport;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Column;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Row;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Sheet;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

public final class TradeValidation {
    private final TradeRuleEngine engine;
    public TradeValidation() { this(new TradeRuleEngine()); }
    public TradeValidation(TradeRuleEngine engine) { this.engine = engine; }

    public ValidationReport validate(WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        List<ValidationIssue> issues = new ArrayList<>();
        JsonNode config;
        try { config = TradeResources.profile(draft.dataType()); }
        catch (IllegalArgumentException error) { return new ValidationReport(List.of(new ValidationIssue("DATA_TYPE", "title", 0, "", error.getMessage()))); }
        TradeWorkbook view = new TradeWorkbook(draft);
        for (JsonNode required : config.path("requiredSheets")) if (view.sheet(required.asText()) == null) add(issues, "MISSING_SHEET", required.asText(), 0, "", "Отсутствует обязательный лист.");
        structure(view, config, issues);
        TradeContext trusted = null;
        try { trusted = TradeContext.load(context, registry); }
        catch (IllegalArgumentException error) { add(issues, "CONTEXT_INVALID", "title", 0, "", error.getMessage()); }
        if (draft.fileSha256().isEmpty()) add(issues, "FILE_FINGERPRINT_MISSING", "title", 0, "", "Не рассчитана контрольная сумма исходного XLSX.");
        Map<String, Object> title = map(draft.metadata().get("title"));
        for (String name : List.of("region", "municipality")) {
            int row = name.equals("region") ? 6 : 17;
            Object value = title.get(name);
            engine.characters(value).forEach(issue -> add(issues, issue.code(), "title", row, "E", issue.message()));
            if (!present(value)) add(issues, "TITLE_REQUIRED", "title", row, "E", "Не заполнено поле титульного листа: " + name);
            if (trusted != null && present(value) && !key(value).equals(key(name.equals("region") ? trusted.regionName() : trusted.municipalityName()))) add(issues, "CONTEXT_MISMATCH", "title", row, "E", "Титульный лист не совпадает с выбранным контекстом: " + name);
        }
        List<String> regions = references(draft, config, "Субъекты РФ");
        if (regions.isEmpty()) add(issues, "REFERENCE_LIST_MISSING", "title", 6, "E", "На листе значений для копирования отсутствует перечень субъектов РФ.");
        else if (present(title.get("region")) && regions.stream().noneMatch(region -> text(region).equals(text(title.get("region"))))) add(issues, "INVALID_REFERENCE", "title", 6, "E", "Субъект отсутствует в справочнике рабочей книги.");
        String organizationId = trusted == null ? "" : trusted.organizationId();
        issues.addAll(fieldRules(draft, config, trusted == null ? List.of() : trusted.files(), organizationId));
        photos(view, trusted == null ? List.of() : trusted.files(), issues);
        if (trusted != null) contextAndDuplicates(view, context, trusted, registry, issues);
        Map<String, Integer> sheetOrder = new LinkedHashMap<>(); sheetOrder.put("title", 0);
        draft.sheets().forEach(sheet -> sheetOrder.put(sheet.code(), sheetOrder.size()));
        issues.sort(Comparator.comparingInt((ValidationIssue issue) -> sheetOrder.getOrDefault(issue.sheet(), 9999)).thenComparingInt(ValidationIssue::row).thenComparingInt(issue -> TradeWorkbook.order(issue.column())));
        return new ValidationReport(issues);
    }

    List<ValidationIssue> fieldRules(WorkbookDraft draft, JsonNode config, List<Map<String, Object>> files, String organizationId) {
        List<ValidationIssue> issues = new ArrayList<>();
        TradeWorkbook view = new TradeWorkbook(draft);
        for (JsonNode field : config.path("fields")) {
            Sheet sheet = view.sheet(field.path("sheet").asText());
            if (sheet == null) continue;
            Column column = TradeWorkbook.column(sheet, field);
            if (column == null) { add(issues, "MISSING_COLUMN", sheet.code(), 4, "", "Отсутствует колонка «" + field.path("name").asText() + "»."); continue; }
            List<String> allowed = references(draft, config, field.path("referenceId").asText());
            for (Row row : sheet.rows()) {
                Object value = TradeWorkbook.value(row, column);
                if (value instanceof Map<?, ?> || value instanceof List<?>) { add(issues, "CELL_OBJECT", sheet.code(), row.excelRow(), column.column(), "Ожидалось скалярное значение ячейки."); continue; }
                if (!field.path("unrestrictedText").asBoolean()) engine.characters(value).forEach(issue -> add(issues, issue.code(), sheet.code(), row.excelRow(), column.column(), issue.message()));
                for (JsonNode rule : field.path("rules")) {
                    String method = rule.path("method").asText();
                    JsonNode params = rule.path("params");
                    if (method.equals("check_equal_to_field") && field.path("name").asText().equals("Геокоординаты полигона размещения НТО (точка 5)") && params.path("other_field").asText().equals("Геокоординаты полигона размещения НТО (точка 1)")) continue;
                    if (method.equals("check_required_one_of_group") && !firstGroupColumn(sheet, column, params.path("group_fields"))) continue;
                    var input = new TradeRuleEngine.Input(TradeWorkbook.relatedValues(sheet, row, field, column, params), allowed, files, TradeWorkbook.groupValues(sheet, row, column, field, params, method, organizationId), !config.path("singleRecord").asBoolean());
                    for (var issue : engine.check(method, value, params, input)) add(issues, issue.code(), sheet.code(), row.excelRow(), column.column(), "Поле «" + field.path("name").asText() + "»: " + issue.message());
                }
            }
        }
        if (draft.dataType().equals("НТО")) polygon(view, issues);
        return issues;
    }

    private static boolean firstGroupColumn(Sheet sheet, Column column, JsonNode names) {
        for (Column candidate : sheet.columns()) for (JsonNode name : names) if (key(base(TradeWorkbook.name(candidate))).equals(key(base(name.asText())))) return candidate.column().equals(column.column());
        return false;
    }
    static List<String> references(WorkbookDraft draft, JsonNode config, String requested) {
        String name = requested.replaceFirst("_variant\\d+$", "");
        Map<String, Object> lists = map(draft.metadata().get("referenceLists"));
        Object entries = lists.get("entries");
        for (Map<String, Object> entry : entries instanceof Map<?, ?> ? List.of(map(entries)) : maps(entries)) if (key(entry.get("name")).equals(key(name)) && entry.get("values") instanceof List<?> values) return values.stream().map(TradeData::text).toList();
        for (var entry : lists.entrySet()) if (key(entry.getKey()).equals(key(name)) && entry.getValue() instanceof List<?> values) return values.stream().map(TradeData::text).toList();
        JsonNode embedded = config.path("referenceLists").path(requested);
        if (embedded.isObject()) embedded = embedded.path("values");
        List<String> values = new ArrayList<>(); if (embedded.isArray()) embedded.forEach(value -> values.add(value.asText()));
        return values;
    }
    private static void structure(TradeWorkbook view, JsonNode config, List<ValidationIssue> issues) {
        config.path("structure").fields().forEachRemaining(entry -> {
            Sheet sheet = view.sheet(entry.getKey()); if (sheet == null) return;
            JsonNode expected = entry.getValue();
            boolean validName = false; for (JsonNode name : expected.path("sheetNames")) if (name.asText().equals(view.sheetName(sheet.code()))) validName = true;
            boolean valid = validName && sheet.columns().size() == expected.path("columns").size();
            for (int index = 0; index < Math.min(sheet.columns().size(), expected.path("columns").size()); index++) {
                Column column = sheet.columns().get(index); JsonNode wanted = expected.path("columns").get(index);
                for (String field : List.of("column", "attribute", "fieldName", "requiredMarker")) {
                    Object actual = field.equals("column") ? column.column() : field.equals("attribute") ? column.attribute() : column.metadata().get(field);
                    if (!structureText(actual).equals(structureText(wanted.path(field).asText("")))) valid = false;
                }
            }
            if (!valid) add(issues, "STRUCTURE_CHANGED", sheet.code(), 4, "", "Изменено имя листа, порядок, заголовок, атрибут или обязательность колонки.");
        });
    }
    private static String structureText(Object value) { return value == null ? "" : value.toString().replace("\r\n", "\n").replace('\r', '\n'); }
    private static void photos(TradeWorkbook view, List<Map<String, Object>> files, List<ValidationIssue> issues) {
        String code = Map.of("НТО", "sheet2", "Рынки", "sheet3", "Ярмарки", "sheet4").get(view.draft.dataType());
        Sheet sheet = view.sheet(code); if (sheet == null) return;
        List<Column> columns = sheet.columns().stream().filter(c -> TradeWorkbook.name(c).startsWith("Фотография объекта")).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row row : sheet.rows()) for (Column column : columns) { String name = TradePhotoRules.name(TradeWorkbook.value(row, column)); if (!name.isEmpty()) counts.merge(name, 1, Integer::sum); }
        for (Row row : sheet.rows()) {
            List<TradePhotoRules.PhotoCell> cells = columns.stream().map(column -> new TradePhotoRules.PhotoCell(column.column(), TradeWorkbook.value(row, column), row.cells().stream().anyMatch(cell -> cell.column().equals(column.column()) && cell.kind().equals("embeddedObject")), Map.<String, Object>of())).toList();
            TradePhotoRules.check(cells, files, counts, view.draft.dataType()).forEach(issue -> add(issues, issue.code(), sheet.code(), row.excelRow(), issue.column(), issue.message()));
        }
    }
    private static void polygon(TradeWorkbook view, List<ValidationIssue> issues) {
        Sheet sheet = view.sheet("sheet2"); if (sheet == null) return;
        for (Row row : sheet.rows()) {
            List<Object> points = new ArrayList<>(); for (int point = 1; point <= 5; point++) points.add(TradeWorkbook.namedValue(sheet, row, "Геокоординаты полигона размещения НТО (точка " + point + ")", ""));
            if (points.stream().allMatch(TradeData::present)) {
                String[] first = text(points.getFirst()).split("\\s*,\\s*", -1), fifth = text(points.getLast()).split("\\s*,\\s*", -1);
                if (first.length == 2 && fifth.length == 2) for (int axis = 0; axis < 2; axis++) {
                    BigDecimal a = TradeRuleEngine.number(first[axis]), b = TradeRuleEngine.number(fifth[axis]);
                    if (a != null && b != null && a.subtract(b).abs().compareTo(new BigDecimal("0.000001")) > 0) { add(issues, "POLYGON_NOT_CLOSED", sheet.code(), row.excelRow(), "", "При заполнении точек 1–5 точка 5 должна совпадать с точкой 1 с допуском 0.000001."); break; }
                }
            }
        }
    }
    private static void contextAndDuplicates(TradeWorkbook view, OperationContext context, TradeContext trusted, RegistryAccess registry, List<ValidationIssue> issues) {
        Map<String, String> collections = Map.of("НТО", "NTOmesto,reestrbiddingReestr", "Рынки", "reestrpermitsReestr,reestrmarketReestr", "Ярмарки", "FairMesto,FairPermits,Fair");
        String[] targets = collections.get(view.draft.dataType()).split(",");
        for (Sheet sheet : view.draft.sheets()) {
            int index = Integer.parseInt(sheet.code().replace("sheet", "")) - 2;
            List<Map<String, Object>> existing = index >= 0 && index < targets.length ? registry.search(targets[index], TradeContext.query("unit.id", "eq", trusted.organizationId()), 5000) : List.of();
            if (existing.size() >= 5000) add(issues, "REGISTRY_LIMIT", sheet.code(), 0, "", "Не удалось безопасно проверить полный набор существующих записей.");
            for (Row row : sheet.rows()) {
                String region = text(TradeWorkbook.namedValue(sheet, row, "Субъект РФ", ""));
                String municipality = text(TradeWorkbook.namedValue(sheet, row, "Муниципальный район/округ, городской округ или внутригородская территория", ""));
                if (!region.isEmpty() && !key(region).equals(key(trusted.regionName()))) add(issues, "CONTEXT_MISMATCH", sheet.code(), row.excelRow(), "", "Субъект строки отличается от выбранного.");
                if (!municipality.isEmpty() && !key(municipality).equals(key(trusted.municipalityName()))) add(issues, "CONTEXT_MISMATCH", sheet.code(), row.excelRow(), "", "МО строки отличается от выбранного.");
                String content = TradeHashes.row(sheet, row, false), legacy = TradeHashes.row(sheet, row, true);
                for (var record : existing) {
                    Map<String, Object> source = map(record.get("migrationSource"));
                    if (text(source.get("appealId")).equals(context.appealId())) continue;
                    if (present(source.get("rowContentHash")) ? content.equals(text(source.get("rowContentHash"))) : legacy.equals(text(source.get("rowHash")))) { add(issues, "EXISTING_RECORD", sheet.code(), row.excelRow(), "", "Запись уже существует в целевом реестре."); break; }
                }
            }
        }
    }
    private static void add(List<ValidationIssue> issues, String code, String sheet, int row, String column, String message) { issues.add(new ValidationIssue(code, sheet, row, column, message)); }
}

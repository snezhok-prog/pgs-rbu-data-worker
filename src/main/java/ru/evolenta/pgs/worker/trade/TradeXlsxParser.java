package ru.evolenta.pgs.worker.trade;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft;
import ru.evolenta.pgs.worker.trade.core.XlsxWorkbookReader;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

/** Bounded core XLSX reader followed by the published trade parser's normalization. */
public final class TradeXlsxParser {
    public static final XlsxWorkbookReader.Limits LIMITS = new XlsxWorkbookReader.Limits(20 * 1024 * 1024, 5000, 600, 2_000_000, 100L * 1024 * 1024, 10000);
    public WorkbookDraft read(InputStream input, String selectedDataType, Map<String, Object> fileMetadata) throws IOException {
        String type = canonicalType(selectedDataType);
        requireFileIdentity(fileMetadata);
        String filename = text(fileMetadata.get("originalName"));
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("Необходимо загрузить файл формата .xlsx.");
        Map<String, String> names = new LinkedHashMap<>();
        TradeResources.profile(type).path("structure").fields().forEachRemaining(entry -> names.put(entry.getKey(), entry.getValue().path("sheetNames").path(0).asText()));
        return normalize(new XlsxWorkbookReader().read(input, type, names, LIMITS), fileMetadata);
    }
    public WorkbookDraft normalize(WorkbookDraft raw, Map<String, Object> fileMetadata) {
        requireFileIdentity(fileMetadata);
        String type = canonicalType(raw.dataType());
        Map<String, Object> metadata = new LinkedHashMap<>(raw.metadata());
        List<String> available = list(metadata.get("availableSheets")).stream().map(TradeData::text).toList();
        String copyName = type.equals("Ярмарки") ? "5. Значения для копирования" : "4. Значения для копирования";
        for (String required : List.of("1. Титульный лист", copyName)) if (!available.contains(required)) throw new IllegalArgumentException("Отсутствует обязательный лист: " + required);
        Map<String, Object> auxiliary = map(metadata.get("auxiliarySheets"));
        metadata.put("title", title(list(auxiliary.get("1. Титульный лист"))));
        metadata.put("referenceLists", referenceLists(list(auxiliary.get(copyName))));
        metadata.put("parserVersion", "rbu-trade-4.0"); metadata.put("status", "PARSED");
        Map<String, Object> file = new LinkedHashMap<>(fileMetadata); file.put("sha256", raw.fileSha256()); file.put("size", metadata.get("sourceFileBytes")); metadata.put("file", file);
        List<WorkbookDraft.Sheet> sheets = new ArrayList<>();
        int count = 0;
        for (var source : raw.sheets()) {
            List<WorkbookDraft.Column> columns = new ArrayList<>(); String block = "", subBlock = "";
            for (var column : source.columns()) {
                String name = text(column.metadata().get("fieldName"));
                if (name.isEmpty() || key(name).equals("название поля") || key(column.attribute()).equals("№")) continue;
                var properties = new LinkedHashMap<>(column.metadata());
                if (present(properties.get("block"))) block = properties.get("block").toString();
                if (present(properties.get("subBlock"))) subBlock = properties.get("subBlock").toString();
                properties.put("block", block); properties.put("subBlock", subBlock);
                columns.add(new WorkbookDraft.Column(column.column(), column.attribute(), properties));
            }
            List<WorkbookDraft.Row> rows = new ArrayList<>();
            for (var row : source.rows()) {
                List<WorkbookDraft.Cell> cells = new ArrayList<>();
                for (var cell : row.cells()) {
                    var column = columns.stream().filter(item -> item.column().equals(cell.column())).findFirst().orElse(null); if (column == null) continue;
                    String normalized = businessValue(cell, text(column.metadata().get("fieldName")));
                    if (!normalized.isEmpty() || cell.kind().equals("embeddedObject")) cells.add(new WorkbookDraft.Cell(cell.column(), normalized, cell.kind(), cell.rawValue()));
                }
                if (!cells.isEmpty()) { rows.add(new WorkbookDraft.Row(row.excelRow(), cells)); count++; }
            }
            sheets.add(new WorkbookDraft.Sheet(source.code(), columns, rows));
        }
        if (count == 0 || count > 5000) throw new IllegalArgumentException("Количество строк данных должно быть от 1 до 5000.");
        var draft = new WorkbookDraft(type, raw.fileSha256(), sheets, metadata);
        long records = new TradeModule().recordCount(draft);
        if (records > TradeModule.MAX_REGISTRY_RECORDS) throw new IllegalArgumentException("Файл создаёт " + records + " реестровых записей ПГС. Допустимо не более 2500.");
        metadata.put("totalRows", count); metadata.put("plannedRegistryRecords", records); metadata.put("registryRecordLimit", 2500);
        return new WorkbookDraft(type, raw.fileSha256(), sheets, metadata);
    }
    private static void requireFileIdentity(Map<String, Object> file) {
        if (!present(file.get("id")) && !present(file.get("_id")) && !present(file.get("fileId"))) throw new IllegalArgumentException("Исходный файл не содержит сохранённый идентификатор.");
    }
    private static String businessValue(WorkbookDraft.Cell cell, String fieldName) {
        if (cell.value() == null) return "";
        String raw = cell.kind().equals("number") && cell.rawValue() != null ? cell.rawValue() : cell.value().toString();
        if (raw.matches("-?\\d+(?:\\.\\d+)?")) {
            String name = key(fieldName);
            BigDecimal number = new BigDecimal(raw);
            if (name.contains("дата")) return LocalDate.of(1899, 12, 30).plusDays(number.setScale(0, RoundingMode.FLOOR).longValueExact()).format(DateTimeFormatter.ofPattern("dd.MM.uuuu"));
            if (name.contains("время") && cell.kind().equals("number") && number.compareTo(BigDecimal.ZERO) >= 0 && number.compareTo(BigDecimal.ONE) < 0) {
                int minutes = (int) (Math.round(number.doubleValue() * 1440) % 1440);
                return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
            }
        }
        return raw;
    }
    private static Map<String, Object> title(List<?> rows) {
        return Map.of("region", titleValue(rows, List.of("регион", "субъект рф", "субъект российской федерации")), "municipality", titleValue(rows, List.of("муниципальное образование")));
    }
    private static String titleValue(List<?> rows, List<String> aliases) {
        for (int at = 0; at < rows.size(); at++) {
            List<?> row = list(rows.get(at));
            for (int column = 0; column < row.size(); column++) if (aliases.contains(key(row.get(column)))) {
                for (int offset = 1; offset <= 3; offset++) if (column + offset < row.size() && present(row.get(column + offset))) return row.get(column + offset).toString();
                for (int next = at + 1; next < Math.min(rows.size(), at + 4); next++) {
                    List<?> later = list(rows.get(next)); for (int offset = 0; offset <= 3; offset++) if (column + offset < later.size() && present(later.get(column + offset))) return later.get(column + offset).toString();
                }
            }
        }
        return "";
    }
    private static Map<String, Object> referenceLists(List<?> rows) {
        int header = 2;
        for (int row = 0; row < Math.min(5, rows.size()); row++) if (list(rows.get(row)).stream().anyMatch(value -> key(value).equals("субъекты рф"))) { header = row; break; }
        List<?> headers = header < rows.size() ? list(rows.get(header)) : List.of();
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            String name = text(headers.get(column)).replaceAll("[\\r\\n\\t\\u00A0]+", " ").replaceAll("\\s+", " "); if (name.isEmpty()) continue;
            List<String> target = values.computeIfAbsent(name, ignored -> new ArrayList<>());
            for (int row = header + 1; row < rows.size(); row++) {
                List<?> data = list(rows.get(row)); if (column >= data.size() || !present(data.get(column))) continue;
                String value = data.get(column).toString(); if (target.stream().noneMatch(existing -> key(existing).equals(key(value)))) target.add(value);
            }
        }
        return Map.of("formatVersion", "xml-safe-v1", "entries", values.entrySet().stream().map(entry -> Map.of("name", entry.getKey(), "values", entry.getValue())).toList());
    }
    private static String canonicalType(String value) {
        return switch (key(value)) { case "нто", "нестационарные торговые объекты" -> "НТО"; case "рынки", "розничные рынки", "рынок" -> "Рынки"; case "ярмарки", "ярмарка" -> "Ярмарки"; default -> throw new IllegalArgumentException("Неподдерживаемый вид данных: " + value); };
    }
}

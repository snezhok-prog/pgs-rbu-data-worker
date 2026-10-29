package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import ru.evolenta.pgs.worker.trade.core.*;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

/** Pet workbook semantics over the bounded common XLSX reader. */
public final class PetXlsxParser {
    public static final Map<String, String> SHEETS =
            Map.of(
                    "sheet2",
                    "2. Реестр заказ-нарядов на отло",
                    "sheet3",
                    "3. Реестр животных без владельц",
                    "sheet4",
                    "4. Реестр карточек учета животн");

    public WorkbookDraft read(
            InputStream input, String fileName, String storageFileId, OperationContext context)
            throws IOException {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new IllegalArgumentException("Необходимо загрузить файл формата .xlsx.");
        if (storageFileId == null || storageFileId.isBlank())
            throw new IllegalArgumentException("У файла отсутствует идентификатор хранилища");
        var raw =
                new XlsxWorkbookReader()
                        .read(input, "Мой питомец", SHEETS, XlsxWorkbookReader.Limits.standard());
        var metadata = new LinkedHashMap<>(raw.metadata());
        metadata.put(
                "file",
                obj(
                        "id",
                        storageFileId,
                        "originalName",
                        fileName,
                        "size",
                        metadata.get("sourceFileBytes"),
                        "sha256",
                        raw.fileSha256()));
        metadata.put(
                "context",
                obj(
                        "organization",
                        context.attributes().get("organization"),
                        "region",
                        context.attributes().get("region"),
                        "municipality",
                        context.attributes().get("municipality")));
        metadata.put("status", "PARSED");
        return normalize(
                new WorkbookDraft(raw.dataType(), raw.fileSha256(), raw.sheets(), metadata));
    }

    public WorkbookDraft normalize(WorkbookDraft raw) {
        var sheets = new ArrayList<WorkbookDraft.Sheet>();
        for (String code : List.of("sheet2", "sheet3", "sheet4")) {
            var source =
                    raw.sheets().stream()
                            .filter(sheet -> sheet.code().equals(code))
                            .findFirst()
                            .orElse(null);
            if (source == null) {
                sheets.add(new WorkbookDraft.Sheet(code, List.of(), List.of()));
                continue;
            }
            var columns = new ArrayList<WorkbookDraft.Column>();
            String block = "", subBlock = "";
            for (var column : source.columns()) {
                var metadata = new LinkedHashMap<>(column.metadata());
                if (!clean(metadata.get("block")).isEmpty()) block = text(metadata.get("block"));
                if (!clean(metadata.get("subBlock")).isEmpty())
                    subBlock = text(metadata.get("subBlock"));
                String name = text(metadata.get("fieldName"));
                if (clean(name).equals("Наименование приюта")) {
                    name = name.stripTrailing();
                    metadata.put("fieldName", name);
                }
                if (clean(name).isEmpty()
                        || key(column.attribute()).equals(key("№"))
                        || key(name).equals(key("Название поля"))) continue;
                metadata.put("block", block);
                metadata.put("subBlock", resolveSubBlock(code, column.attribute(), subBlock));
                columns.add(
                        new WorkbookDraft.Column(column.column(), column.attribute(), metadata));
            }
            var rows = new ArrayList<WorkbookDraft.Row>();
            for (var row : source.rows()) {
                if (row.excelRow() < 6) continue;
                var cells = new ArrayList<WorkbookDraft.Cell>();
                for (var column : columns) {
                    var original =
                            row.cells().stream()
                                    .filter(cell -> cell.column().equals(column.column()))
                                    .findFirst()
                                    .orElse(null);
                    if (original == null) continue;
                    if (original.kind().equals("embeddedObject")
                            || original.value() instanceof Map<?, ?>
                            || original.value() instanceof List<?>) {
                        cells.add(original);
                        continue;
                    }
                    Object lexical = original.kind().equals("number") ? original.rawValue() : null;
                    String value = text(lexical == null ? original.value() : lexical);
                    if (value.isEmpty()) continue;
                    String name = key(column.metadata().get("fieldName"));
                    if (value.matches("^-?\\d+(?:\\.\\d+)?$")) {
                        if (name.contains("дата")) value = excelDate(value);
                        else if (name.contains("время")) value = excelTime(value);
                    }
                    cells.add(
                            new WorkbookDraft.Cell(
                                    column.column(), value, original.kind(), original.rawValue()));
                }
                if (!cells.isEmpty()) rows.add(new WorkbookDraft.Row(row.excelRow(), cells));
            }
            sheets.add(new WorkbookDraft.Sheet(code, columns, rows));
        }
        var metadata = new LinkedHashMap<>(raw.metadata());
        var auxiliary = map(metadata.get("auxiliarySheets"));
        if (auxiliary.containsKey("1. Титульный лист"))
            metadata.put("title", title(list(auxiliary.get("1. Титульный лист"))));
        if (auxiliary.containsKey("5. Значения для копирования"))
            metadata.put(
                    "referenceLists",
                    references(list(auxiliary.get("5. Значения для копирования"))));
        metadata.putIfAbsent(
                "referenceLists", obj("formatVersion", "xml-safe-v1", "entries", List.of()));
        metadata.put("parserVersion", "rbu-pet-1.0");
        metadata.put("petNormalizedVersion", "rbu-pet-java-normalize-1");
        return new WorkbookDraft(raw.dataType(), raw.fileSha256(), sheets, metadata);
    }

    private static String resolveSubBlock(String code, String attribute, String original) {
        if (!code.equals("sheet4") || !attribute.matches("\\d+")) return original;
        int number = Integer.parseInt(attribute);
        int[][] ranges = {
            {32, 102},
            {103, 163},
            {164, 244},
            {245, 252},
            {253, 266},
            {267, 277},
            {278, 285},
            {286, 292},
            {293, 363}
        };
        String[] names = {
            "Дегельминтизация",
            "Дезинсекция",
            "Вакцинация",
            "Стерилизация (кастрация)",
            "Освидетельствование",
            "Эвтаназия",
            "Утилизация",
            "Нанесение идентификационной метки",
            "Иное мероприятие"
        };
        for (int index = 0; index < ranges.length; index++)
            if (number >= ranges[index][0] && number <= ranges[index][1])
                return "Мероприятия над животным (" + names[index] + ")";
        return original;
    }

    static String excelDate(String value) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) return value;
            return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(number)).format(DATE);
        } catch (NumberFormatException | java.time.DateTimeException error) {
            return value;
        }
    }

    static String excelTime(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) return value;
        long minutes = Math.round((number - Math.floor(number)) * 24 * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static Map<String, Object> title(List<?> rows) {
        return obj(
                "region",
                afterLabel(
                        rows,
                        Set.of(
                                key("Регион"),
                                key("Субъект РФ"),
                                key("Субъект Российской Федерации"))),
                "municipality",
                afterLabel(rows, Set.of(key("Муниципальное образование"))));
    }

    private static String afterLabel(List<?> rows, Set<String> aliases) {
        for (int row = 0; row < rows.size(); row++) {
            var values = list(rows.get(row));
            for (int column = 0; column < values.size(); column++) {
                if (!aliases.contains(key(values.get(column)))) continue;
                for (int offset = 1; offset <= 3 && column + offset < values.size(); offset++)
                    if (!clean(values.get(column + offset)).isEmpty())
                        return clean(values.get(column + offset));
                for (int next = row + 1; next < Math.min(rows.size(), row + 4); next++) {
                    var nextValues = list(rows.get(next));
                    for (int offset = 0;
                            offset <= 3 && column + offset < nextValues.size();
                            offset++)
                        if (!clean(nextValues.get(column + offset)).isEmpty())
                            return clean(nextValues.get(column + offset));
                }
            }
        }
        return "";
    }

    private static Map<String, Object> references(List<?> rows) {
        int header = 2;
        for (int row = 0; row < Math.min(rows.size(), 5); row++)
            if (list(rows.get(row)).stream()
                    .anyMatch(
                            value ->
                                    Set.of(key("Субъект РФ"), key("Субъекты РФ"))
                                            .contains(key(value)))) {
                header = row;
                break;
            }
        var headers = header < rows.size() ? list(rows.get(header)) : List.of();
        var result = new LinkedHashMap<String, List<String>>();
        for (int column = 0; column < headers.size(); column++) {
            String name = clean(headers.get(column));
            if (name.isEmpty()) continue;
            var values = result.computeIfAbsent(name, ignored -> new ArrayList<>());
            var seen = new HashSet<String>();
            values.forEach(value -> seen.add(key(value)));
            for (int row = header + 1; row < rows.size(); row++) {
                var source = list(rows.get(row));
                if (column >= source.size() || clean(source.get(column)).isEmpty()) continue;
                String value = text(source.get(column));
                if (seen.add(key(value))) values.add(value);
            }
        }
        return obj(
                "formatVersion",
                "xml-safe-v1",
                "entries",
                result.entrySet().stream()
                        .map(entry -> obj("name", entry.getKey(), "values", entry.getValue()))
                        .toList());
    }
}

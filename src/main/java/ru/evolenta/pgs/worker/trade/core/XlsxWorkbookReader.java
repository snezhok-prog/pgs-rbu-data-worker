package ru.evolenta.pgs.worker.trade.core;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/**
 * Bounded XLSX input only. Formula cached values are read; formulas are never evaluated. Source:
 * https://poi.apache.org/components/spreadsheet/how-to.html
 */
public final class XlsxWorkbookReader {
    public record Limits(
            int fileBytes,
            int dataRows,
            int columns,
            int cells,
            long inflatedBytes,
            int zipEntries) {
        public Limits {
            if (fileBytes < 1
                    || dataRows < 1
                    || columns < 1
                    || cells < 1
                    || inflatedBytes < 1
                    || zipEntries < 1)
                throw new IllegalArgumentException("Invalid workbook limits");
        }

        public static Limits standard() {
            return new Limits(20 * 1024 * 1024, 5000, 600, 2_000_000, 128L * 1024 * 1024, 4000);
        }
    }

    public WorkbookDraft read(
            InputStream input, String dataType, Map<String, String> sheetNames, Limits limits)
            throws IOException {
        byte[] bytes = readBounded(input, limits.fileBytes());
        checkArchive(bytes, limits);
        var sheets = new ArrayList<WorkbookDraft.Sheet>();
        var auxiliary = new LinkedHashMap<String, Object>();
        var names = new ArrayList<String>();
        var found = new HashSet<String>();
        int totalCells = 0;
        int materializedCells = 0;
        int dataRows = 0;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (var generic : workbook) {
                XSSFSheet sheet = (XSSFSheet) generic;
                names.add(sheet.getSheetName());
                String code =
                        sheetNames.entrySet().stream()
                                .filter(entry -> entry.getValue().equals(sheet.getSheetName()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                int populated = 0;
                for (var row : sheet)
                    populated = Math.addExact(populated, row.getPhysicalNumberOfCells());
                totalCells = Math.addExact(totalCells, populated);
                if (totalCells > limits.cells())
                    throw new IllegalArgumentException("Превышено допустимое число ячеек XLSX");
                if (code == null) {
                    var rows = new ArrayList<List<Object>>();
                    if (sheet.getLastRowNum() > 100000)
                        throw new IllegalArgumentException("Слишком длинный вспомогательный лист");
                    for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                        var row = sheet.getRow(index);
                        var values = new ArrayList<Object>();
                        int end = row == null ? 0 : Math.max(0, row.getLastCellNum());
                        if (end > limits.columns())
                            throw new IllegalArgumentException("Превышено число колонок XLSX");
                        materializedCells = Math.addExact(materializedCells, end);
                        if (materializedCells > limits.cells())
                            throw new IllegalArgumentException("Превышен диапазон ячеек XLSX");
                        for (int column = 0; column < end; column++)
                            values.add(cell(row.getCell(column)));
                        rows.add(values);
                    }
                    auxiliary.put(sheet.getSheetName(), rows);
                    continue;
                }
                if (!found.add(code))
                    throw new IllegalArgumentException("Неоднозначное сопоставление листа " + code);
                int lastColumn = 0;
                for (int header = 0; header < 5; header++) {
                    var row = sheet.getRow(header);
                    if (row != null) lastColumn = Math.max(lastColumn, row.getLastCellNum());
                }
                if (lastColumn > limits.columns())
                    throw new IllegalArgumentException("Превышено число колонок XLSX");
                var columns = new ArrayList<WorkbookDraft.Column>();
                for (int index = 1; index < lastColumn; index++) {
                    var meta = new LinkedHashMap<String, Object>();
                    meta.put("block", text(sheet, 1, index));
                    meta.put("subBlock", text(sheet, 2, index));
                    meta.put("fieldName", text(sheet, 3, index));
                    meta.put("requiredMarker", text(sheet, 4, index));
                    columns.add(
                            new WorkbookDraft.Column(
                                    CellReference.convertNumToColString(index),
                                    text(sheet, 0, index).trim(),
                                    meta));
                }
                Set<String> pictures = pictures(sheet);
                var rows = new ArrayList<WorkbookDraft.Row>();
                if (sheet.getLastRowNum() > limits.dataRows() + 4) {
                    // Count nonempty data rows below instead of rejecting template formatting.
                    int nonempty = 0;
                    for (var row : sheet)
                        if (row.getRowNum() >= 5 && row.getPhysicalNumberOfCells() > 0) nonempty++;
                    if (nonempty > limits.dataRows())
                        throw new IllegalArgumentException("Превышено число строк XLSX");
                }
                for (var genericRow : sheet) {
                    var row = sheet.getRow(genericRow.getRowNum());
                    if (row.getRowNum() < 5) continue;
                    if (row.getLastCellNum() > limits.columns())
                        throw new IllegalArgumentException("Превышено число колонок XLSX");
                    var cells = new ArrayList<WorkbookDraft.Cell>();
                    boolean nonempty = false;
                    int end = Math.max(lastColumn, row.getLastCellNum());
                    materializedCells = Math.addExact(materializedCells, Math.max(0, end - 1));
                    if (materializedCells > limits.cells())
                        throw new IllegalArgumentException("Превышен диапазон ячеек XLSX");
                    for (int index = 1; index < end; index++) {
                        XSSFCell source = row.getCell(index);
                        String letter = CellReference.convertNumToColString(index);
                        boolean embedded =
                                pictures.contains((row.getRowNum() + 1) + ":" + letter)
                                        || embedded(source);
                        Object value = embedded ? null : cell(source);
                        String kind = embedded ? "embeddedObject" : kind(source);
                        if (value != null && !Data.text(value).isBlank()
                                || kind.equals("embeddedObject")) nonempty = true;
                        cells.add(
                                new WorkbookDraft.Cell(
                                        letter,
                                        value,
                                        kind,
                                        source == null ? null : source.getRawValue()));
                    }
                    if (!nonempty) continue;
                    if (++dataRows > limits.dataRows())
                        throw new IllegalArgumentException("Превышено число строк XLSX");
                    rows.add(new WorkbookDraft.Row(row.getRowNum() + 1, cells));
                }
                sheets.add(new WorkbookDraft.Sheet(code, columns, rows));
            }
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("availableSheets", names);
        metadata.put("sheetNames", sheetNames);
        metadata.put("auxiliarySheets", auxiliary);
        metadata.put("title", title(auxiliary));
        metadata.put("readerVersion", "rbu-java-xlsx-1");
        metadata.put("sourceFileBytes", bytes.length);
        return new WorkbookDraft(dataType, sha256(bytes), sheets, metadata);
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        var output = new ByteArrayOutputStream(Math.min(limit, 65536));
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > limit - count)
                throw new IllegalArgumentException("Размер XLSX превышает допустимый");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void checkArchive(byte[] bytes, Limits limits) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K')
            throw new IllegalArgumentException(
                    "Ожидался файл XLSX, а не XLS/ODS или другой формат");
        long inflated = 0;
        int count = 0;
        var names = new HashSet<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[16384];
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > limits.zipEntries() || !names.add(entry.getName()))
                    throw new IllegalArgumentException("Недопустимая структура XLSX");
                if (entry.getName().equalsIgnoreCase("xl/vbaProject.bin"))
                    throw new IllegalArgumentException("Файлы XLSM с макросами не поддерживаются");
                int read;
                long entryBytes = 0;
                while ((read = zip.read(buffer)) != -1) {
                    inflated += read;
                    entryBytes += read;
                    if (entryBytes > Math.min(limits.inflatedBytes(), 50L * 1024 * 1024))
                        throw new IllegalArgumentException("Превышен размер элемента XLSX");
                    if (inflated > limits.inflatedBytes())
                        throw new IllegalArgumentException("Превышен размер распакованного XLSX");
                }
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("xl/workbook.xml"))
            throw new IllegalArgumentException("Не найдено содержимое XLSX");
    }

    private static String text(XSSFSheet sheet, int row, int column) {
        var source = sheet.getRow(row);
        return source == null ? "" : Data.text(cell(source.getCell(column)));
    }

    private static Object cell(XSSFCell cell) {
        if (cell == null) return null;
        CellType type =
                cell.getCellType() == CellType.FORMULA
                        ? cell.getCachedFormulaResultType()
                        : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                String raw = cell.getRawValue();
                yield raw == null ? null : new BigDecimal(raw).stripTrailingZeros();
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case BLANK, _NONE -> null;
            case ERROR ->
                    throw new IllegalArgumentException(
                            "Ячейка Excel содержит ошибку " + cell.getAddress());
            default -> throw new IllegalArgumentException("Неподдерживаемый тип ячейки Excel");
        };
    }

    private static String kind(XSSFCell cell) {
        if (cell == null) return "blank";
        CellType type =
                cell.getCellType() == CellType.FORMULA
                        ? cell.getCachedFormulaResultType()
                        : cell.getCellType();
        return switch (type) {
            case NUMERIC -> "number";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            default -> "blank";
        };
    }

    private static boolean embedded(XSSFCell cell) {
        if (cell == null) return false;
        if (cell.getCTCell().isSetVm()) return true;
        return cell.getCellType() == CellType.FORMULA
                && java.util.regex.Pattern.compile("(?is)\\b(?:IMAGE|DISPIMG)\\s*\\(")
                        .matcher(cell.getCellFormula())
                        .find();
    }

    private static Set<String> pictures(XSSFSheet sheet) {
        var result = new HashSet<String>();
        var drawing = sheet.getDrawingPatriarch();
        if (drawing != null)
            for (var shape : drawing.getShapes())
                if (shape instanceof XSSFPicture picture) {
                    var anchor = picture.getClientAnchor();
                    result.add(
                            (anchor.getRow1() + 1)
                                    + ":"
                                    + CellReference.convertNumToColString(anchor.getCol1()));
                }
        return result;
    }

    private static Map<String, Object> title(Map<String, Object> auxiliary) {
        var result = new LinkedHashMap<String, Object>();
        for (var raw : auxiliary.values()) {
            List<?> rows = Data.list(raw);
            for (int row = 0; row < rows.size(); row++) {
                List<?> values = Data.list(rows.get(row));
                for (int column = 0; column < values.size(); column++) {
                    String label =
                            Data.text(values.get(column))
                                    .trim()
                                    .replaceAll("\\s+", " ")
                                    .toLowerCase(Locale.ROOT);
                    String key =
                            Set.of("регион", "субъект рф", "субъект российской федерации")
                                            .contains(label)
                                    ? "region"
                                    : label.equals("муниципальное образование")
                                            ? "municipality"
                                            : null;
                    if (key == null || result.containsKey(key)) continue;
                    String value = "";
                    for (int offset = 1; offset <= 3 && column + offset < values.size(); offset++) {
                        value = Data.text(values.get(column + offset)).trim();
                        if (!value.isEmpty()) break;
                    }
                    if (value.isEmpty())
                        for (int next = row + 1;
                                next <= Math.min(row + 3, rows.size() - 1);
                                next++) {
                            List<?> nextValues = Data.list(rows.get(next));
                            for (int offset = 0;
                                    offset <= 3 && column + offset < nextValues.size();
                                    offset++) {
                                value = Data.text(nextValues.get(column + offset)).trim();
                                if (!value.isEmpty()) break;
                            }
                            if (!value.isEmpty()) break;
                        }
                    if (!value.isEmpty()) result.put(key, value);
                }
            }
        }
        return result;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

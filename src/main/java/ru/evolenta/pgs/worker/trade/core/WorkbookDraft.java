package ru.evolenta.pgs.worker.trade.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkbookDraft(
        String dataType, String fileSha256, List<Sheet> sheets, Map<String, Object> metadata) {
    public WorkbookDraft {
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(fileSha256, "fileSha256");
        sheets = List.copyOf(sheets);
        metadata = Data.immutableMap(metadata);
        var identities = new HashSet<String>();
        for (var sheet : sheets)
            if (!identities.add(sheet.code()))
                throw new IllegalArgumentException("Duplicate sheet " + sheet.code());
    }

    public record Column(String column, String attribute, Map<String, Object> metadata) {
        public Column {
            if (column == null || column.isBlank())
                throw new IllegalArgumentException("Missing column");
            Objects.requireNonNull(attribute, "attribute");
            metadata = Data.immutableMap(metadata);
        }
    }

    public record Cell(String column, Object value, String kind, String rawValue) {
        public Cell(String column, Object value, String kind) {
            this(column, value, kind, null);
        }

        public Cell {
            if (column == null || column.isBlank())
                throw new IllegalArgumentException("Missing cell column");
            value = Data.immutable(value);
            kind = kind == null ? "string" : kind;
        }
    }

    public record Row(int excelRow, List<Cell> cells) {
        public Row {
            if (excelRow < 1) throw new IllegalArgumentException("Excel row must be positive");
            cells = List.copyOf(cells);
            var identities = new HashSet<String>();
            for (var cell : cells)
                if (!identities.add(cell.column()))
                    throw new IllegalArgumentException("Duplicate cell " + cell.column());
        }
    }

    public record Sheet(String code, List<Column> columns, List<Row> rows) {
        public Sheet {
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("Missing sheet code");
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
            var identities = new HashSet<String>();
            for (var column : columns)
                if (!identities.add(column.column()))
                    throw new IllegalArgumentException("Duplicate column " + column.column());
        }
    }

    public Object value(Sheet sheet, Row row, int attribute) {
        return value(sheet, row, Integer.toString(attribute));
    }

    public Object value(Sheet sheet, Row row, String attribute) {
        String column = null;
        for (var candidate : sheet.columns()) {
            if (!candidate.attribute().equals(attribute)) continue;
            if (column != null)
                throw new IllegalArgumentException("Ambiguous attribute " + attribute);
            column = candidate.column();
        }
        if (column == null) return null;
        for (var cell : row.cells()) if (cell.column().equals(column)) return cell.value();
        return null;
    }

    public static WorkbookDraft from(Map<String, Object> input) {
        var metadata = new LinkedHashMap<>(input);
        String type = Data.text(metadata.remove("dataType"));
        String hash = Data.text(metadata.remove("fileSha256"));
        String nestedHash =
                metadata.get("file") instanceof Map<?, ?> file ? Data.text(file.get("sha256")) : "";
        if (!hash.isBlank() && !nestedHash.isBlank() && !hash.equals(nestedHash))
            throw new IllegalArgumentException("Conflicting workbook file hashes");
        if (hash.isBlank()) hash = nestedHash;
        Object rawSheets = metadata.remove("sheets");
        var sheets = new ArrayList<Sheet>();
        for (var rawSheet : Data.list(rawSheets)) {
            var sheet = Data.object(rawSheet);
            var columns = new ArrayList<Column>();
            for (var rawColumn : Data.list(sheet.get("columns"))) {
                var column = Data.object(rawColumn);
                String letter = Data.text(column.remove("column"));
                String attribute = Data.text(column.remove("attribute"));
                Object nested = column.remove("metadata");
                if (nested != null)
                    column = new LinkedHashMap<>(Data.merge(Data.object(nested), column));
                columns.add(new Column(letter, attribute, column));
            }
            var rows = new ArrayList<Row>();
            for (var rawRow : Data.list(sheet.get("rows"))) {
                var row = Data.object(rawRow);
                var cells = new ArrayList<Cell>();
                for (var rawCell : Data.list(row.get("cells"))) {
                    var cell = Data.object(rawCell);
                    String kind =
                            cell.containsKey("kind")
                                    ? Data.text(cell.get("kind"))
                                    : Boolean.TRUE.equals(cell.get("embeddedObject"))
                                            ? "embeddedObject"
                                            : "string";
                    cells.add(
                            new Cell(
                                    Data.text(cell.get("column")),
                                    cell.get("value"),
                                    kind,
                                    cell.get("rawValue") == null
                                            ? null
                                            : Data.text(cell.get("rawValue"))));
                }
                Object number =
                        row.getOrDefault("excelRow", row.getOrDefault("rowNumber", row.get("row")));
                rows.add(new Row(Integer.parseInt(Data.text(number)), cells));
            }
            sheets.add(new Sheet(Data.text(sheet.get("code")), columns, rows));
        }
        return new WorkbookDraft(type, hash, sheets, metadata);
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<>(metadata);
        result.put("dataType", dataType);
        result.put("fileSha256", fileSha256);
        result.put(
                "sheets",
                sheets.stream()
                        .map(
                                sheet ->
                                        Map.of(
                                                "code",
                                                sheet.code(),
                                                "columns",
                                                sheet.columns().stream()
                                                        .map(
                                                                column -> {
                                                                    var fields =
                                                                            new LinkedHashMap<>(
                                                                                    column
                                                                                            .metadata());
                                                                    fields.put(
                                                                            "column",
                                                                            column.column());
                                                                    fields.put(
                                                                            "attribute",
                                                                            column.attribute());
                                                                    return fields;
                                                                })
                                                        .toList(),
                                                "rows",
                                                sheet.rows().stream()
                                                        .map(
                                                                row ->
                                                                        Map.of(
                                                                                "excelRow",
                                                                                row.excelRow(),
                                                                                "cells",
                                                                                row.cells().stream()
                                                                                        .map(
                                                                                                cell -> {
                                                                                                    var
                                                                                                            fields =
                                                                                                                    new LinkedHashMap<
                                                                                                                            String,
                                                                                                                            Object>();
                                                                                                    fields
                                                                                                            .put(
                                                                                                                    "column",
                                                                                                                    cell
                                                                                                                            .column());
                                                                                                    fields
                                                                                                            .put(
                                                                                                                    "value",
                                                                                                                    cell
                                                                                                                            .value());
                                                                                                    fields
                                                                                                            .put(
                                                                                                                    "kind",
                                                                                                                    cell
                                                                                                                            .kind());
                                                                                                    if (cell
                                                                                                                    .rawValue()
                                                                                                            != null)
                                                                                                        fields
                                                                                                                .put(
                                                                                                                        "rawValue",
                                                                                                                        cell
                                                                                                                                .rawValue());
                                                                                                    return fields;
                                                                                                })
                                                                                        .toList()))
                                                        .toList()))
                        .toList());
        return result;
    }
}

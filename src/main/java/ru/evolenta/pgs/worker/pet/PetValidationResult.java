package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import ru.evolenta.pgs.worker.trade.core.*;

import java.math.*;
import java.time.Instant;
import java.util.*;

/** Compatible form data derived from actual validation, without persistence or hidden success. */
public record PetValidationResult(
        WorkbookDraft draft,
        ValidationReport validation,
        boolean blocked,
        Map<String, Object> form,
        Map<String, Object> confirmation) {
    public PetValidationResult {
        form = Data.immutableMap(form);
        confirmation = Data.immutableMap(confirmation);
    }

    static PetValidationResult build(
            WorkbookDraft input,
            ValidationReport validation,
            OperationContext context,
            Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        String time = completedAt.toString();
        var rowKeys = new HashSet<String>();
        for (var sheet : input.sheets())
            for (var row : sheet.rows()) rowKeys.add(sheet.code() + ":" + row.excelRow());
        boolean blocked =
                validation.issues().stream()
                        .anyMatch(issue -> !rowKeys.contains(issue.sheet() + ":" + issue.row()));
        var invalidKeys = new HashSet<String>();
        validation.issues().forEach(issue -> invalidKeys.add(issue.sheet() + ":" + issue.row()));
        var summaries = new ArrayList<Map<String, Object>>();
        int total = 0, valid = 0;
        for (var sheet : input.sheets()) {
            int count = sheet.rows().size();
            int validRows =
                    blocked
                            ? 0
                            : (int)
                                    sheet.rows().stream()
                                            .filter(
                                                    row ->
                                                            !invalidKeys.contains(
                                                                    sheet.code()
                                                                            + ":"
                                                                            + row.excelRow()))
                                            .count();
            String name =
                    clean(
                            first(
                                    map(input.metadata().get("sheetNames")).get(sheet.code()),
                                    PetXlsxParser.SHEETS.get(sheet.code()),
                                    sheet.code()));
            summaries.add(
                    obj(
                            "recordTypeName",
                            name,
                            "sheetName",
                            name,
                            "totalRows",
                            count,
                            "validRows",
                            validRows,
                            "invalidRows",
                            count - validRows));
            total += count;
            valid += validRows;
        }
        var errors = new ArrayList<Map<String, Object>>();
        var errorSummary =
                obj("required", 0, "reference", 0, "format", 0, "duplicate", 0, "logical", 0);
        var title = map(input.metadata().get("title"));
        for (var issue : validation.issues()) {
            var sheet =
                    input.sheets().stream()
                            .filter(candidate -> candidate.code().equals(issue.sheet()))
                            .findFirst()
                            .orElse(null);
            var column =
                    sheet == null
                            ? null
                            : sheet.columns().stream()
                                    .filter(candidate -> candidate.column().equals(issue.column()))
                                    .findFirst()
                                    .orElse(null);
            String fieldName =
                    column == null
                            ? issue.column()
                            : validationClean(column.metadata().get("fieldName"));
            String value = "", sourceField = "", qualifier = "";
            if (sheet != null) {
                var row =
                        sheet.rows().stream()
                                .filter(candidate -> candidate.excelRow() == issue.row())
                                .findFirst()
                                .orElse(null);
                Object source =
                        row == null
                                ? null
                                : row.cells().stream()
                                        .filter(cell -> cell.column().equals(issue.column()))
                                        .findFirst()
                                        .map(WorkbookDraft.Cell::value)
                                        .orElse(null);
                value =
                        source instanceof Map<?, ?> || source instanceof List<?>
                                ? "[встроенный объект]"
                                : text(source);
                String base = PetRuleEngine.baseNameForField(fieldName);
                long duplicates =
                        sheet.columns().stream()
                                .filter(
                                        candidate ->
                                                PetRuleEngine.baseNameForField(
                                                                candidate
                                                                        .metadata()
                                                                        .get("fieldName"))
                                                        .equals(base))
                                .count();
                if (duplicates > 1 && column != null) {
                    qualifier =
                            clean(
                                    first(
                                            column.metadata().get("subBlock"),
                                            column.metadata().get("block")));
                    if (!qualifier.isEmpty()) {
                        sourceField = fieldName;
                        fieldName += " — " + qualifier;
                    }
                }
            } else if (issue.sheet().equals("title")) {
                if (issue.row() == 6 && issue.column().equals("E")) {
                    fieldName = "Субъект РФ";
                    value = text(title.get("region"));
                } else if (issue.row() == 17 && issue.column().equals("E")) {
                    fieldName = "Муниципальное образование";
                    value = text(title.get("municipality"));
                } else if (issue.column().startsWith("organization")) {
                    fieldName = "Организация";
                    value = display(context.attributes().get("organization"));
                } else if (issue.column().equals("file")) {
                    fieldName = "Файл XLSX";
                    value = clean(map(input.metadata().get("file")).get("originalName"));
                }
            }
            String category = category(issue.code());
            String summaryCategory = category.equals("existing_record") ? "duplicate" : category;
            errorSummary.put(summaryCategory, (Integer) errorSummary.get(summaryCategory) + 1);
            var error =
                    obj(
                            "errorId",
                            sha(
                                    obj(
                                            "migrationId",
                                            context.migrationId(),
                                            "fingerprint",
                                            input.fileSha256(),
                                            "sheet",
                                            issue.sheet(),
                                            "row",
                                            issue.row(),
                                            "column",
                                            issue.column(),
                                            "code",
                                            issue.code())),
                            "sheetCode",
                            issue.sheet(),
                            "sheetName",
                            sheet == null
                                    ? "1. Титульный лист"
                                    : first(
                                            map(input.metadata().get("sheetNames"))
                                                    .get(sheet.code()),
                                            PetXlsxParser.SHEETS.get(sheet.code()),
                                            sheet.code()),
                            "rowNumber",
                            issue.row() == 0 ? null : issue.row(),
                            "column",
                            issue.column(),
                            "columnNumber",
                            column == null ? "" : column.attribute(),
                            "fieldName",
                            fieldName,
                            "value",
                            value,
                            "errorCode",
                            issue.code(),
                            "errorType",
                            category.equals("existing_record") ? "Существующая запись" : category,
                            "errorTypeCode",
                            category,
                            "message",
                            qualifier.isEmpty()
                                    ? issue.message()
                                    : issue.message()
                                            + " Блок исходного файла: «"
                                            + qualifier
                                            + "».");
            if (!sourceField.isEmpty()) error.put("sourceFieldName", sourceField);
            errors.add(error);
        }
        int invalid = total - valid;
        String fingerprint = input.fileSha256() + "|" + time + "|rbu-pet-validation-1.0";
        var metadata = new LinkedHashMap<>(input.metadata());
        metadata.putAll(
                obj(
                        "status",
                        "VALIDATED",
                        "validationBlocked",
                        blocked,
                        "validationOutcome",
                        validation.valid() ? "SUCCESS" : "WITH_ERRORS",
                        "validationVersion",
                        "rbu-pet-validation-1.0",
                        "validatedFileSha256",
                        input.fileSha256(),
                        "validatedAt",
                        time,
                        "validationFingerprint",
                        fingerprint,
                        "totalRows",
                        total,
                        "validRows",
                        valid,
                        "invalidRows",
                        invalid));
        var form =
                obj(
                        "processingStatus",
                        validation.valid()
                                ? "Валидация завершена успешно"
                                : "Валидация завершена: обнаружены ошибки",
                        "processedAt",
                        time,
                        "fileName",
                        map(input.metadata().get("file")).get("originalName"),
                        "sourceFileName",
                        map(input.metadata().get("file")).get("originalName"),
                        "totalRows",
                        total,
                        "validRows",
                        valid,
                        "validPercent",
                        percent(valid, total),
                        "invalidRows",
                        invalid,
                        "invalidPercent",
                        percent(invalid, total),
                        "validationSummary",
                        summaries,
                        "errors",
                        errors,
                        "validationErrors",
                        errors,
                        "totalErrors",
                        errors.size(),
                        "errorSummary",
                        errorSummary,
                        "validationBlocked",
                        blocked,
                        "validationVersion",
                        "rbu-pet-validation-1.0",
                        "validationFingerprint",
                        fingerprint,
                        "confirmApply",
                        false);
        var confirmation =
                obj(
                        "confirmApply",
                        false,
                        "confirmationFingerprint",
                        "",
                        "validationFingerprint",
                        fingerprint,
                        "confirmedAt",
                        "");
        return new PetValidationResult(
                new WorkbookDraft(input.dataType(), input.fileSha256(), input.sheets(), metadata),
                validation,
                blocked,
                form,
                confirmation);
    }

    private static BigDecimal percent(int count, int total) {
        return total == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(count * 100L)
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    static String category(String code) {
        if (Set.of("REQUIRED", "TITLE_REQUIRED", "CONDITIONAL_REQUIRED", "ORGANIZATION_REQUIRED")
                .contains(code)) return "required";
        if (code.contains("REFERENCE")
                || code.equals("ORGANIZATION_NOT_FOUND")
                || code.startsWith("ORGANIZATION_GUID_")) return "reference";
        if (code.equals("EXISTING_RECORD")) return "existing_record";
        if (code.contains("DUPLICATE") || code.contains("UNIQUE")) return "duplicate";
        if (code.contains("FORMAT")
                || code.startsWith("IS_")
                || code.startsWith("CHECK_FILE")
                || Set.of(
                                "LEADING_TRAILING_SPACES",
                                "DOUBLE_SPACES",
                                "LINE_BREAK",
                                "INVISIBLE_SYMBOL",
                                "GROUP_TOO_LONG",
                                "MISSING_COLUMN",
                                "FILE_FINGERPRINT_MISSING",
                                "INVALID_CELL_TYPE")
                        .contains(code)) return "format";
        return "logical";
    }
}

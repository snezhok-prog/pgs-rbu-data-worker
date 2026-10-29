package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.evolenta.pgs.worker.trade.core.*;

import java.io.*;
import java.util.*;

class PetXlsxIntegrationTest {
    private static final String GUID = "8cd09210-4b66-45f6-b422-36ee01df6e4a";
    static final RegistryAccess REGISTRY =
            new RegistryAccess() {
                public Optional<Map<String, Object>> findById(String collection, String id) {
                    return switch (collection) {
                        case "organizations" ->
                                id.equals("owner")
                                        ? Optional.of(
                                                Map.of(
                                                        "_id",
                                                        "owner",
                                                        "name",
                                                        "Тестовая организация",
                                                        "guid",
                                                        GUID))
                                        : Optional.empty();
                        case "rbuPetRegionPassports" ->
                                id.equals("region")
                                        ? Optional.of(
                                                Map.of(
                                                        "_id",
                                                        "region",
                                                        "regionName",
                                                        "Тестовый регион"))
                                        : Optional.empty();
                        case "rbuPetRegionMunicipalities" ->
                                id.equals("municipality")
                                        ? Optional.of(
                                                Map.of(
                                                        "_id",
                                                        "municipality",
                                                        "municipalityName",
                                                        "Тестовое МО"))
                                        : Optional.empty();
                        default -> Optional.empty();
                    };
                }

                public List<Map<String, Object>> search(
                        String collection, Map<String, Object> filter, int limit) {
                    return collection.equals("organizations") && filter.get("guid").equals(GUID)
                            ? List.of(Map.of("_id", "owner", "guid", GUID))
                            : List.of();
                }
            };

    @ParameterizedTest
    @ValueSource(strings = {"ROIV", "OMSU"})
    void realTemplateXlsxPassesFullValidationAndBuildsAnOrderForBothScopes(String scope)
            throws Exception {
        var context = context(scope);
        var draft =
                new PetXlsxParser()
                        .read(
                                new ByteArrayInputStream(workbook(false)),
                                "template.xlsx",
                                "storage-id",
                                context);
        var report = new PetModule().validate(draft, context, REGISTRY);
        assertTrue(report.valid(), report.issues().toString());
        var plan = new PetModule().plan(draft, context, REGISTRY);
        assertEquals(1, plan.size());
        assertEquals("animalCatchOrderRegistryCollection", plan.getFirst().collection());
        assertEquals("ORDER-1", plan.getFirst().data().get("requestNumber"));
        assertEquals("owner", map(plan.getFirst().data().get("unit")).get("id"));
        assertEquals("rbu-pet-1.0", draft.metadata().get("parserVersion"));
        assertEquals(64, draft.fileSha256().length());
    }

    @Test
    void changedRealTemplateHeaderBlocksAllCreation() throws Exception {
        var context = context("ROIV");
        var draft =
                new PetXlsxParser()
                        .read(
                                new ByteArrayInputStream(workbook(true)),
                                "template.xlsx",
                                "storage-id",
                                context);
        var report = new PetModule().validate(draft, context, REGISTRY);
        assertTrue(
                report.issues().stream()
                        .anyMatch(issue -> issue.code().equals("INVALID_COLUMN_STRUCTURE")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PetModule().plan(draft, context, REGISTRY));
    }

    @Test
    void rawWhitespaceIsReportedBeforeBusinessRuleFailures() throws Exception {
        var context = context("ROIV");
        var draft =
                new PetXlsxParser()
                        .read(
                                new ByteArrayInputStream(workbook(false)),
                                "template.xlsx",
                                "storage-id",
                                context);
        var sheets = new ArrayList<WorkbookDraft.Sheet>();
        for (var sheet : draft.sheets()) {
            if (!sheet.code().equals("sheet2")) {
                sheets.add(sheet);
                continue;
            }
            var row = sheet.rows().getFirst();
            var cells = new ArrayList<WorkbookDraft.Cell>();
            for (var cell : row.cells())
                cells.add(
                        cell.column().equals("D")
                                ? new WorkbookDraft.Cell("D", "  bad  ", "string")
                                : cell);
            sheets.add(
                    new WorkbookDraft.Sheet(
                            sheet.code(),
                            sheet.columns(),
                            List.of(new WorkbookDraft.Row(row.excelRow(), cells))));
        }
        var changed =
                new WorkbookDraft(draft.dataType(), draft.fileSha256(), sheets, draft.metadata());
        var report = new PetModule().validate(changed, context, REGISTRY);
        var errors =
                report.issues().stream()
                        .filter(
                                issue ->
                                        issue.sheet().equals("sheet2")
                                                && issue.row() == 6
                                                && issue.column().equals("D"))
                        .toList();
        assertEquals(1, errors.size());
        assertEquals("LEADING_TRAILING_SPACES", errors.getFirst().code());
    }

    static OperationContext context(String scope) {
        return new OperationContext(
                "migration",
                "appeal",
                "owner",
                "actor",
                scope,
                Map.of(
                        "organization",
                        Map.of(
                                "organizationId",
                                "owner",
                                "organizationName",
                                "Тестовая организация",
                                "ogrn",
                                "1234567890123"),
                        "region",
                        Map.of("_id", "region", "regionName", "Тестовый регион"),
                        "municipality",
                        Map.of("_id", "municipality", "municipalityName", "Тестовое МО")));
    }

    static byte[] workbook(boolean corruptHeader) throws IOException {
        var config = new PetConfiguration();
        try (var workbook = new XSSFWorkbook();
                var bytes = new ByteArrayOutputStream()) {
            var title = workbook.createSheet("1. Титульный лист");
            title.createRow(5).createCell(3).setCellValue("Субъект РФ");
            title.getRow(5).createCell(4).setCellValue("Тестовый регион");
            title.createRow(16).createCell(3).setCellValue("Муниципальное образование");
            title.getRow(16).createCell(4).setCellValue("Тестовое МО");
            for (String code : List.of("sheet2", "sheet3", "sheet4")) {
                var sheet = workbook.createSheet(PetXlsxParser.SHEETS.get(code));
                for (int row = 0; row < 5; row++) sheet.createRow(row);
                var columns =
                        maps(map(map(config.profile().get("expected")).get(code)).get("columns"));
                for (var column : columns) {
                    int index = PetRuleEngine.columnOrder(text(column.get("column"))) - 1;
                    String[] fields = {"attribute", "block", "subBlock", "name", "requiredMarker"};
                    for (int row = 0; row < fields.length; row++)
                        sheet.getRow(row)
                                .createCell(index)
                                .setCellValue(text(column.get(fields[row])));
                }
                if (code.equals("sheet2")) {
                    if (corruptHeader)
                        sheet.getRow(3).getCell(1).setCellValue("Переименованный столбец");
                    var row = sheet.createRow(5);
                    String[] values = {
                        "Тестовый регион",
                        "Тестовая организация",
                        "1234567890123",
                        "1234567890",
                        "Организация по отлову",
                        "1234567890123",
                        "1234567890",
                        GUID,
                        "ORDER-1"
                    };
                    for (int index = 0; index < values.length; index++)
                        row.createCell(index + 1).setCellValue(values[index]);
                }
            }
            var references = workbook.createSheet("5. Значения для копирования");
            references.createRow(1).createCell(0).setCellValue("Субъект РФ");
            references.createRow(2).createCell(0).setCellValue("Тестовый регион");
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }
}

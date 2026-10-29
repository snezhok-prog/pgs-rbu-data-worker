package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.pet.PetData.*;

import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.trade.core.*;

class PetBusinessRulesTest {
    @Test
    void normalizesShelterHeaderTrailingWhitespaceWithoutRenamingOtherHeaders() throws Exception {
        var raw = new WorkbookDraft("Мой питомец", "a".repeat(64), List.of(
                new WorkbookDraft.Sheet("sheet4", List.of(new WorkbookDraft.Column("NC", "367",
                        Map.of("fieldName", "Наименование приюта  ", "requiredMarker", "Нет"))), List.of())), Map.of());
        var normalized = new PetXlsxParser().normalize(raw);
        assertEquals("Наименование приюта", normalized.sheets().get(2).columns().getFirst().metadata().get("fieldName"));
        var draft = read();
        assertTrue(new PetModule().validate(draft, PetXlsxIntegrationTest.context("ROIV"),
                PetXlsxIntegrationTest.REGISTRY).valid());
    }

    @Test
    void requiresMatchingOrganizationNameAndOgrnOnEveryWorkingSheet() throws Exception {
        var original = read();
        for (String code : List.of("sheet2", "sheet3", "sheet4")) {
            var sheets = new ArrayList<WorkbookDraft.Sheet>();
            for (var sheet : original.sheets()) {
                var cells = new ArrayList<WorkbookDraft.Cell>();
                for (var column : sheet.columns()) {
                    if (column.attribute().equals("2")) cells.add(new WorkbookDraft.Cell(column.column(), "Чужая организация", "string"));
                    if (column.attribute().equals("3")) cells.add(new WorkbookDraft.Cell(column.column(), "9876543210987", "string"));
                }
                sheets.add(new WorkbookDraft.Sheet(sheet.code(), sheet.columns(), sheet.code().equals(code)
                        ? List.of(new WorkbookDraft.Row(6, cells)) : List.of()));
            }
            var report = new PetModule().validate(new WorkbookDraft(original.dataType(), original.fileSha256(), sheets, original.metadata()),
                    PetXlsxIntegrationTest.context("ROIV"), PetXlsxIntegrationTest.REGISTRY);
            var problems = report.issues().stream().filter(issue -> issue.sheet().equals(code)).toList();
            assertTrue(problems.stream().anyMatch(issue -> issue.code().equals("ORGANIZATION_NAME_MISMATCH")), problems.toString());
            assertTrue(problems.stream().anyMatch(issue -> issue.code().equals("ORGANIZATION_OGRN_MISMATCH")), problems.toString());
        }
    }

    @Test
    void tailAtSheetFourAttributeEighteenRemainsRequired() throws Exception {
        var original = read();
        var sheets = new ArrayList<WorkbookDraft.Sheet>();
        String tailColumn = "";
        for (var sheet : original.sheets()) {
            var cells = new ArrayList<WorkbookDraft.Cell>();
            for (var column : sheet.columns()) {
                if (sheet.code().equals("sheet4") && column.attribute().equals("18")) tailColumn = column.column();
                if (column.attribute().equals("2")) cells.add(new WorkbookDraft.Cell(column.column(), "Тестовая организация", "string"));
                if (column.attribute().equals("3")) cells.add(new WorkbookDraft.Cell(column.column(), "1234567890123", "string"));
            }
            sheets.add(new WorkbookDraft.Sheet(sheet.code(), sheet.columns(), sheet.code().equals("sheet4")
                    ? List.of(new WorkbookDraft.Row(6, cells)) : List.of()));
        }
        String expectedColumn = tailColumn;
        var report = new PetModule().validate(new WorkbookDraft(original.dataType(), original.fileSha256(), sheets, original.metadata()),
                PetXlsxIntegrationTest.context("ROIV"), PetXlsxIntegrationTest.REGISTRY);
        assertTrue(report.issues().stream().anyMatch(issue -> issue.sheet().equals("sheet4")
                && issue.row() == 6 && issue.column().equals(expectedColumn) && issue.code().contains("REQUIRED")), report.issues().toString());
    }

    private static WorkbookDraft read() throws Exception {
        return new PetXlsxParser().read(new ByteArrayInputStream(PetXlsxIntegrationTest.workbook(false)),
                "pet.xlsx", "file", PetXlsxIntegrationTest.context("ROIV"));
    }
}

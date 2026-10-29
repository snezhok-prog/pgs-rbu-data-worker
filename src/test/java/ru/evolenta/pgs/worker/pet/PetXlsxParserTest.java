package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import ru.evolenta.pgs.worker.trade.core.*;

import java.math.BigDecimal;
import java.util.*;

class PetXlsxParserTest {
    @Test
    void normalizesDatesTimesMergedHeadersAndKeepsRawExponentForValidation() {
        var columns =
                List.of(
                        column("B", "1", "Дата события", "Основной блок", "Описание"),
                        column("C", "2", "Время события", "", ""),
                        column("D", "3", "ОГРН", "", ""),
                        column("E", "32", "Мероприятие", "", ""),
                        column("F", "33.1", "Дозировка", "", ""),
                        column("G", "", "", "", ""));
        var row =
                new WorkbookDraft.Row(
                        6,
                        List.of(
                                new WorkbookDraft.Cell(
                                        "B", new BigDecimal("25569"), "number", "25569"),
                                new WorkbookDraft.Cell("C", new BigDecimal("0.5"), "number", "0.5"),
                                new WorkbookDraft.Cell(
                                        "D", new BigDecimal("1.23E13"), "number", "1.23E13"),
                                new WorkbookDraft.Cell("E", true, "boolean")));
        var raw =
                new WorkbookDraft(
                        "Мой питомец",
                        "a".repeat(64),
                        List.of(new WorkbookDraft.Sheet("sheet4", columns, List.of(row))),
                        Map.of("readerVersion", "rbu-java-xlsx-1"));
        var normalized = new PetXlsxParser().normalize(raw);
        var sheet =
                normalized.sheets().stream()
                        .filter(candidate -> candidate.code().equals("sheet4"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("01.01.1970", normalized.value(sheet, sheet.rows().getFirst(), 1));
        assertEquals("12:00", normalized.value(sheet, sheet.rows().getFirst(), 2));
        assertEquals("1.23E13", normalized.value(sheet, sheet.rows().getFirst(), 3));
        assertEquals("true", normalized.value(sheet, sheet.rows().getFirst(), 32));
        assertEquals("Основной блок", sheet.columns().get(1).metadata().get("block"));
        assertEquals(
                "Мероприятия над животным (Дегельминтизация)",
                sheet.columns().get(3).metadata().get("subBlock"));
        assertEquals("33.1", sheet.columns().get(4).attribute());
        assertEquals(5, sheet.columns().size());
        assertEquals(
                3,
                normalized.sheets().size(),
                "missing sheets remain visible for business validation");
    }

    @Test
    void parsesTitleOnlyFromTitleSheetAndXmlSafeReferencesFromRealHeaderPosition() {
        var auxiliary =
                Map.<String, Object>of(
                        "1. Титульный лист",
                        List.of(
                                List.of("Субъект РФ", "Регион из титула"),
                                List.of("Муниципальное образование", "МО из титула")),
                        "5. Значения для копирования",
                        List.of(
                                List.of("Примечание"),
                                List.of("Субъект РФ", "Вид"),
                                List.of("Регион из титула", "Собака"),
                                List.of("регион из титула", "собака")));
        var raw =
                new WorkbookDraft(
                        "Мой питомец",
                        "a".repeat(64),
                        List.of(),
                        Map.of("auxiliarySheets", auxiliary));
        var normalized = new PetXlsxParser().normalize(raw);
        assertEquals(
                "Регион из титула", PetData.map(normalized.metadata().get("title")).get("region"));
        assertEquals(
                "МО из титула",
                PetData.map(normalized.metadata().get("title")).get("municipality"));
        var refs = PetData.map(normalized.metadata().get("referenceLists"));
        assertEquals("xml-safe-v1", refs.get("formatVersion"));
        assertEquals(
                1, PetData.list(PetData.maps(refs.get("entries")).getFirst().get("values")).size());
    }

    private static WorkbookDraft.Column column(
            String column, String attribute, String name, String block, String subBlock) {
        return new WorkbookDraft.Column(
                column,
                attribute,
                Map.of(
                        "fieldName",
                        name,
                        "block",
                        block,
                        "subBlock",
                        subBlock,
                        "requiredMarker",
                        "Нет"));
    }
}

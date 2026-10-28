package ru.evolenta.pgs.worker.trade;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeXlsxParserTest {
    @Test void requiresPersistedFileIdentityBeforeReadingUntrustedBytes() {
        var unreadable = new java.io.InputStream() { public int read() { throw new AssertionError("File bytes must not be read before the identity guard."); } };
        assertThrows(IllegalArgumentException.class, () -> new TradeXlsxParser().read(unreadable, "НТО", Map.of("originalName", "fixture.xlsx")));
    }
    @Test void preservesRawTextAndDistinguishesNativeTimeFromNumericText() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var title = book.createSheet("1. Титульный лист"); title.createRow(5).createCell(0).setCellValue("Субъект РФ"); title.getRow(5).createCell(1).setCellValue(" Ростовская область ");
            title.createRow(16).createCell(0).setCellValue("Муниципальное образование"); title.getRow(16).createCell(1).setCellValue("Тестовое МО");
            var refs = book.createSheet("4. Значения для копирования"); refs.createRow(1).createCell(0).setCellValue("Субъекты РФ"); refs.createRow(2).createCell(0).setCellValue("Ростовская область"); refs.createRow(3).createCell(0).setCellValue("ростовская область");
            for (String name : java.util.List.of("2. Реестр мест", "3. Реестр торгов")) {
                var sheet = book.createSheet(name);
                for (int row = 0; row < 5; row++) sheet.createRow(row);
                sheet.getRow(0).createCell(0).setCellValue("№"); sheet.getRow(3).createCell(0).setCellValue("Название поля");
                String[] fields = {"Дата выдачи", "Время начала", "Время окончания", "ИНН", "Комментарий"};
                for (int column = 1; column <= fields.length; column++) { sheet.getRow(0).createCell(column).setCellValue(Integer.toString(column)); sheet.getRow(3).createCell(column).setCellValue(fields[column - 1]); }
                sheet.getRow(1).createCell(1).setCellValue("Блок"); sheet.getRow(2).createCell(1).setCellValue("Подблок");
                var row = sheet.createRow(5); row.createCell(1).setCellValue(45658.75); row.createCell(2).setCellValue(0.5); row.createCell(3).setCellValue("0.5"); row.createCell(4).setCellValue("0001234567"); row.createCell(5).setCellValue(" x  y\n\t");
            }
            book.write(output); bytes = output.toByteArray();
        }
        var draft = new TradeXlsxParser().read(new ByteArrayInputStream(bytes), "НТО", Map.of("id", "file", "originalName", "fixture.xlsx"));
        var sheet = draft.sheets().getFirst(); var row = sheet.rows().getFirst();
        assertEquals("01.01.2025", draft.value(sheet, row, 1));
        assertEquals("12:00", draft.value(sheet, row, 2));
        assertEquals("0.5", draft.value(sheet, row, 3));
        assertEquals("0001234567", draft.value(sheet, row, 4));
        assertEquals(" x  y\n\t", draft.value(sheet, row, 5));
        assertEquals("Блок", sheet.columns().getLast().metadata().get("block"));
        assertEquals(" Ростовская область ", TradeData.at(draft.metadata(), "title.region"));
        assertEquals(1, TradeData.list(TradeData.at(draft.metadata(), "referenceLists.entries.0.values")).size());
        assertEquals(64, draft.fileSha256().length());
    }
}

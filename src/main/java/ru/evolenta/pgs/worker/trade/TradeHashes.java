package ru.evolenta.pgs.worker.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Row;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Sheet;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

final class TradeHashes {
    private TradeHashes() {}
    static String row(Sheet sheet, Row row, boolean includeRowNumber) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (var cell : row.cells()) {
            var column = sheet.columns().stream().filter(c -> c.column().equals(cell.column())).findFirst().orElse(null);
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("column", fingerprintText(cell.column()));
            canonical.put("attribute", column == null ? "" : fingerprintText(column.attribute()));
            for (String name : List.of("block", "subBlock", "fieldName")) canonical.put(name, column == null ? "" : fingerprintText(column.metadata().get(name)));
            canonical.put("value", cell.value() == null ? null : cell.value().toString());
            cells.add(canonical);
        }
        cells.sort(Comparator.comparing(cell -> text(cell.get("column"))));
        Map<String, Object> body = new LinkedHashMap<>();
        if (includeRowNumber) body.put("rowNumber", row.excelRow());
        body.put("cells", cells);
        return sha256(groovyJson(body));
    }
    static String groovyJson(Object value) {
        String raw = json(value);
        StringBuilder encoded = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (ch > 127) encoded.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
            else encoded.append(ch);
        }
        return encoded.toString();
    }
    private static String fingerprintText(Object value) { return text(value).replace('\u00A0', ' ').replaceAll("\\s+", " "); }
}

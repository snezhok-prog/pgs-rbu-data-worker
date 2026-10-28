package ru.evolenta.pgs.worker.trade;

import static org.junit.jupiter.api.Assertions.*;

import static ru.evolenta.pgs.worker.trade.TradeData.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.evolenta.pgs.worker.trade.core.EntityReference;
import ru.evolenta.pgs.worker.trade.core.OperationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Minimal synthetic positive business rows, with every published expected XLSX header retained. */
class TradeProfileXlsxIntegrationTest {
    @ParameterizedTest
    @CsvSource({"Рынки,ROIV", "Рынки,OMSU", "Ярмарки,ROIV", "Ярмарки,OMSU"})
    void readsAndFullyValidatesARealMarketOrFairXlsxBeforePublicPlanning(String type, String scope)
            throws Exception {
        var sourceContext = TradeValidationTest.context(scope);
        var attributes = new LinkedHashMap<>(sourceContext.attributes());
        attributes.put("documentFiles", List.of());
        var context =
                new OperationContext(
                        sourceContext.migrationId(),
                        sourceContext.appealId(),
                        sourceContext.unitId(),
                        sourceContext.actorId(),
                        scope,
                        attributes);
        var draft =
                new TradeXlsxParser()
                        .read(
                                new ByteArrayInputStream(workbook(type, context)),
                                type,
                                Map.of(
                                        "id",
                                        "positive-profile-storage",
                                        "originalName",
                                        "positive-profile.xlsx"));
        int expectedColumns = 0;
        var structures = TradeResources.profile(type).path("structure").elements();
        while (structures.hasNext()) expectedColumns += structures.next().path("columns").size();
        assertEquals(
                expectedColumns, draft.sheets().stream().mapToInt(s -> s.columns().size()).sum());
        assertEquals(
                type.equals("Рынки") ? 63 : 149,
                TradeResources.profile(type).path("fields").size());
        assertEquals(1, draft.sheets().stream().mapToInt(s -> s.rows().size()).sum());
        assertEquals("rbu-trade-4.0", draft.metadata().get("parserVersion"));
        assertEquals(64, draft.fileSha256().length());
        var module = new TradeModule();
        var registry = TradeModuleTest.dictionaryRegistry();
        var validation = module.validate(draft, context, registry);
        assertTrue(validation.valid(), validation.issues().toString());
        var plan = module.plan(draft, context, registry);
        assertEquals(plan, module.plan(draft, context, registry));
        assertEquals(module.recordCount(draft), plan.size());
        if (type.equals("Рынки")) {
            assertEquals(
                    List.of("reestrmarketReestr", "nsiLocalObjectMarket"),
                    plan.stream().map(e -> e.collection()).toList());
            assertEquals(
                    "Тестовый рынок", at(plan.getFirst().data(), "InfoRetailMarket.RetailName"));
            assertEquals(
                    new EntityReference(plan.getFirst().key(), "guid"),
                    plan.getLast().data().get("guid"));
        } else {
            assertEquals(List.of("Fair"), plan.stream().map(e -> e.collection()).toList());
            assertEquals("FAIR-1", at(plan.getFirst().data(), "marketInfo.marketNumber"));
        }
        for (var entity : plan) {
            assertEquals(context.appealId(), at(entity.data(), "migrationSource.appealId"));
            assertEquals(draft.fileSha256(), at(entity.data(), "migrationSource.sourceFileSha256"));
            String owner =
                    entity.data().containsKey("dictionaryUnitId")
                            ? text(entity.data().get("dictionaryUnitId"))
                            : text(at(entity.data(), "unit.id"));
            assertEquals(context.unitId(), owner);
        }
    }

    static byte[] workbook(String type, OperationContext context) throws Exception {
        JsonNode profile = TradeResources.profile(type);
        JsonNode fixture = resource("full-schema-positive.json").path(type);
        String dataSheet = fixture.path("sheet").asText();
        var region = text(at(context.attributes(), "region.name"));
        var municipality = text(at(context.attributes(), "municipality.name"));
        var values = new LinkedHashMap<String, String>();
        values.put("B", region);
        values.put("C", municipality);
        values.put("D", "1111111111111");
        values.put("E", "1111111111");
        fixture.path("columns")
                .fields()
                .forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText()));
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var title = workbook.createSheet("1. Титульный лист");
            title.createRow(5).createCell(0).setCellValue("Субъект РФ");
            title.getRow(5).createCell(1).setCellValue(region);
            title.createRow(16).createCell(0).setCellValue("Муниципальное образование");
            title.getRow(16).createCell(1).setCellValue(municipality);
            var sheets = profile.path("structure").fields();
            while (sheets.hasNext()) {
                var source = sheets.next();
                var destination =
                        workbook.createSheet(source.getValue().path("sheetNames").get(0).asText());
                String collection =
                        type.equals("Рынки")
                                ? Map.of(
                                                "sheet2",
                                                "reestrpermitsReestr",
                                                "sheet3",
                                                "reestrmarketReestr")
                                        .get(source.getKey())
                                : Map.of(
                                                "sheet2",
                                                "FairMesto",
                                                "sheet3",
                                                "FairPermits",
                                                "sheet4",
                                                "Fair")
                                        .get(source.getKey());
                var used = new HashSet<JsonNode>();
                for (int row = 0; row < 5; row++) destination.createRow(row);
                for (JsonNode column : source.getValue().path("columns")) {
                    int index =
                            CellReference.convertColStringToIndex(column.path("column").asText());
                    JsonNode ruleField = null;
                    // Reuse the one-to-one header/block matching from make-synthetic-fixtures.mjs;
                    // its incompatible mapping-only business values are not used here.
                    for (JsonNode field : TradeResources.SINGLE.path(collection).path("fields"))
                        if (!used.contains(field)
                                && key(field.path("name").asText())
                                        .equals(key(column.path("fieldName").asText()))) {
                            if (ruleField == null) ruleField = field;
                            if (field.path("attribute")
                                    .asText()
                                    .equals(column.path("attribute").asText())) {
                                ruleField = field;
                                break;
                            }
                        }
                    if (ruleField != null) used.add(ruleField);
                    var header =
                            List.of(
                                    column.path("attribute").asText(),
                                    ruleField == null ? "" : ruleField.path("block").asText(),
                                    ruleField == null ? "" : ruleField.path("block").asText(),
                                    column.path("fieldName").asText(),
                                    column.path("requiredMarker").asText());
                    for (int row = 0; row < header.size(); row++)
                        destination.getRow(row).createCell(index).setCellValue(header.get(row));
                }
                if (source.getKey().equals(dataSheet)) {
                    var allowed = new HashSet<String>();
                    source.getValue()
                            .path("columns")
                            .forEach(column -> allowed.add(column.path("column").asText()));
                    assertTrue(
                            allowed.containsAll(values.keySet()),
                            "Fixture may only fill actual expected columns");
                    var row = destination.createRow(5);
                    values.forEach(
                            (column, value) ->
                                    row.createCell(CellReference.convertColStringToIndex(column))
                                            .setCellValue(value));
                }
            }
            var references =
                    workbook.createSheet(
                            type.equals("Ярмарки")
                                    ? "5. Значения для копирования"
                                    : "4. Значения для копирования");
            references.createRow(1).createCell(0).setCellValue("Субъекты РФ");
            references.createRow(2).createCell(0).setCellValue(region);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}

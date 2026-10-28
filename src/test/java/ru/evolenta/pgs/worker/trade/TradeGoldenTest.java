package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeGoldenTest {
    @Test void matchesEveryMarketPayloadFieldOnTheExplicitSyntheticMappingFixture() { synthetic("market"); }
    @Test void matchesEveryFairPayloadFieldOnTheExplicitSyntheticMappingFixture() { synthetic("fair"); }
    @Test void omitsFairMarketSlotsWithNoFourteenFieldSignalIncludingOrganizerOnlySlots() {
        var draft = ru.evolenta.pgs.worker.trade.core.WorkbookDraft.from(TradeData.JSON.convertValue(TradeData.resource("fair-draft.json"), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        var sheet = draft.sheets().getFirst(); var row = sheet.rows().getFirst();
        var ignoredColumns = sheet.columns().stream().filter(column -> {
            try { int attribute = Integer.parseInt(column.attribute()); return attribute >= 18 && attribute <= 31 || attribute >= 45 && attribute <= 58; }
            catch (NumberFormatException error) { return false; }
        }).map(ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Column::column).toList();
        var emptySlots = new ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Row(row.excelRow(), row.cells().stream().filter(cell -> !ignoredColumns.contains(cell.column())).toList());
        var context = TradeValidationTest.context("ROIV"); var registry = TradeModuleTest.dictionaryRegistry();
        var mapper = new TradePayloadMapper(draft, context, TradeContext.load(context, registry), registry);
        assertTrue(TradeData.maps(mapper.build("FairMesto", sheet, emptySlots, "empty-slots").get("marketInfo")).isEmpty());
    }
    @Test void matchesEveryNtoPayloadFieldFromThePublishedGroovyBuilders() {
        JsonNode golden = TradeData.resource("nto-groovy-golden.json");
        var draft = TradeValidationTest.fixture();
        var plan = new TradeModule().plan(draft, TradeValidationTest.context("ROIV"), TradeModuleTest.dictionaryRegistry());
        List<String> differences = new ArrayList<>();
        for (var entity : plan) {
            var payload = new LinkedHashMap<>(entity.data());
            if (entity.collection().equals("nsiLocalObjectNTO")) for (String key : List.of("guid", "code", "autokey", "ObjectID")) payload.put(key, plan.getFirst().data().get("guid"));
            JsonNode actual = normalize(TradeData.JSON.valueToTree(payload));
            JsonNode expected = normalize(golden.path(entity.collection()).deepCopy());
            diff(entity.collection(), expected, actual, differences);
        }
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }
    @Test void preservesLegacyGroovyRowFingerprintsExactly() {
        var draft = TradeValidationTest.fixture(); var sheet = draft.sheets().getFirst(); var row = sheet.rows().getFirst();
        var golden = TradeData.resource("nto-groovy-golden.json").path("hashes");
        assertEquals(golden.path("legacy").asText(), TradeHashes.row(sheet, row, true));
        assertEquals(golden.path("content").asText(), TradeHashes.row(sheet, row, false));
    }
    private static void synthetic(String profile) {
        var draft = ru.evolenta.pgs.worker.trade.core.WorkbookDraft.from(TradeData.JSON.convertValue(TradeData.resource(profile + "-draft.json"), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        var base = TradeValidationTest.context("ROIV");
        var attrs = new LinkedHashMap<>(base.attributes()); attrs.put("documentFiles", TradeData.JSON.convertValue(TradeData.resource(profile + "-files.json"), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {}));
        var context = new ru.evolenta.pgs.worker.trade.core.OperationContext(base.migrationId(), base.appealId(), base.unitId(), base.actorId(), base.scope(), attrs);
        var registry = TradeModuleTest.dictionaryRegistry();
        var mapper = new TradePayloadMapper(draft, context, TradeContext.load(context, registry), registry);
        java.util.Map<String, Object> actual = new LinkedHashMap<>();
        var targets = profile.equals("market") ? java.util.Map.of("sheet2", "reestrpermitsReestr", "sheet3", "reestrmarketReestr") : java.util.Map.of("sheet2", "FairMesto", "sheet3", "FairPermits", "sheet4", "Fair");
        for (var sheet : draft.sheets()) {
            var row = sheet.rows().getFirst(); String collection = targets.get(sheet.code());
            var payload = mapper.build(collection, sheet, row, profile + '/' + sheet.code()); actual.put(collection, payload);
            if (collection.equals("reestrmarketReestr")) actual.put("nsiLocalObjectMarket", TradeNsiMapper.market(payload, payload.get("guid")));
            if (collection.equals("FairMesto")) {
                var markets = TradeData.maps(payload.get("marketInfo")); var nsi = new ArrayList<java.util.Map<String, Object>>();
                if (markets.isEmpty()) markets = List.of(java.util.Map.of());
                for (int index = 0; index < markets.size(); index++) nsi.add(TradeNsiMapper.fair(payload, markets.get(index), "fair-nsi-" + index, java.util.Map.of()));
                actual.put("nsiLocalObjectFair", nsi);
            }
        }
        var golden = TradeData.resource(profile + "-groovy-golden.json"); List<String> differences = new ArrayList<>();
        diff(profile, normalize(golden.deepCopy()), normalize(TradeData.JSON.valueToTree(actual)), differences);
        assertTrue(differences.isEmpty(), String.join("\n", differences));
    }
    private static JsonNode normalize(JsonNode node) {
        if (node instanceof ObjectNode object) { object.remove("migrationSource"); var names = new ArrayList<String>(); object.fieldNames().forEachRemaining(names::add); names.forEach(name -> object.set(name, normalize(object.get(name)))); }
        else if (node instanceof ArrayNode array) { for (int index = 0; index < array.size(); index++) array.set(index, normalize(array.get(index))); }
        else if (node.isTextual() && node.asText().matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")) return TradeData.JSON.getNodeFactory().textNode("<guid>");
        return node;
    }
    private static void diff(String path, JsonNode expected, JsonNode actual, List<String> differences) {
        if (expected == null || actual == null) { if (expected != actual) differences.add(path + ": expected " + expected + ", actual " + actual); return; }
        if (expected.isObject() && actual.isObject()) {
            var names = new java.util.TreeSet<String>(); expected.fieldNames().forEachRemaining(names::add); actual.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> diff(path + '.' + name, expected.get(name), actual.get(name), differences));
        } else if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) differences.add(path + ": expected length " + expected.size() + ", actual " + actual.size());
            for (int index = 0; index < Math.min(expected.size(), actual.size()); index++) diff(path + '[' + index + ']', expected.get(index), actual.get(index), differences);
        } else if (expected.isNumber() && actual.isNumber()) { if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) differences.add(path + ": expected " + expected + ", actual " + actual); }
        else if (!expected.equals(actual)) differences.add(path + ": expected " + expected + ", actual " + actual);
    }
}

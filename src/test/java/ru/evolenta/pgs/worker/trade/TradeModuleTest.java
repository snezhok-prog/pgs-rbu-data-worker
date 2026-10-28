package ru.evolenta.pgs.worker.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import static org.junit.jupiter.api.Assertions.*;

class TradeModuleTest {
    @org.junit.jupiter.api.Test void requiresStableOperationTimeInsteadOfInventingNowDuringRetries() {
        var base = TradeValidationTest.context("ROIV"); var attrs = new java.util.LinkedHashMap<>(base.attributes()); attrs.remove("operationTime");
        var context = new ru.evolenta.pgs.worker.trade.core.OperationContext(base.migrationId(), base.appealId(), base.unitId(), base.actorId(), base.scope(), attrs);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new TradeModule().plan(TradeValidationTest.fixture(), context, dictionaryRegistry()));
    }
    @Test void plansRealNtoRegistryPayloadsWithTypesAndNsiGuidLink() {
        RegistryAccess registry = dictionaryRegistry();
        var draft = TradeValidationTest.fixture();
        var context = TradeValidationTest.context("ROIV");
        var module = new TradeModule();
        var plan = module.plan(draft, context, registry);
        assertEquals(List.of("NTOmesto", "nsiLocalObjectNTO", "reestrbiddingReestr"), plan.stream().map(entity -> entity.collection()).toList());
        var place = plan.getFirst().data();
        assertEquals("organization", TradeData.at(place, "unit.id"));
        assertEquals("123456", TradeData.at(place, "ntoInformation.FullAddress.postalCode"));
        assertInstanceOf(BigDecimal.class, TradeData.at(place, "ntoInformation.ntoInfoLandArea"));
        assertTrue(TradeData.at(place, "NTOContract.ContractStartDate").toString().matches("\\d{4}-\\d{2}-\\d{2}"));
        assertNotNull(TradeData.at(place, "ntoInformation.NtoType.code"));
        assertEquals(new ru.evolenta.pgs.worker.trade.core.EntityReference(plan.getFirst().key(), "guid"), plan.get(1).data().get("guid"));
        assertEquals("local", plan.get(1).data().get("dictionaryType"));
        assertEquals("organization", plan.get(1).data().get("dictionaryUnitId"));
        assertEquals(plan, module.plan(draft, context, registry));
        assertEquals("appeal", TradeData.at(place, "migrationSource.appealId"));
        assertEquals(draft.fileSha256(), TradeData.at(place, "migrationSource.sourceFileSha256"));
    }

    @Test void rejectsAmbiguousDictionaryInsteadOfChoosingTheFirstRecord() {
        var registry = new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) { return TradeValidationTest.registry(true).findById(collection, id); }
            public List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit) {
                return collection.startsWith("nsiUnified") ? List.of(Map.of("_id", "a", "code", "StatusProjectPlaceApproved"), Map.of("_id", "b", "code", "StatusProjectPlaceApproved")) : List.of();
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new TradeModule().plan(TradeValidationTest.fixture(), TradeValidationTest.context("ROIV"), registry));
    }
    static RegistryAccess dictionaryRegistry() {
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) { return TradeValidationTest.registry(true).findById(collection, id); }
            public List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit) {
                if (!collection.startsWith("nsiUnified")) return List.of();
                String code = TradeData.text(TradeData.at(filter, "search.search.0.value"));
                String parent = "StatusProjectPlaceApproved";
                for (var group : TradeResources.MAPPINGS) for (var entry : group) if (entry.path("code").asText().equals(code) && entry.hasNonNull("parentCode")) parent = entry.path("parentCode").asText();
                return List.of(Map.of("_id", "dict-" + code, "code", code, "parentId", "dict-" + parent));
            }
        };
    }
}

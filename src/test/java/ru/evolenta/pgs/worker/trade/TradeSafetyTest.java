package ru.evolenta.pgs.worker.trade;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import static org.junit.jupiter.api.Assertions.*;

class TradeSafetyTest {
    private final Map<String, Object> record = Map.of("_id", "record", "guid", "record-guid", "unit", Map.of("id", "organization"), "migrationSource", Map.of("appealId", "import"));

    @Test void discoversNestedGuidReferencesAndBlocksUnknownStatuses() {
        var active = Map.<String, Object>of("_id", "other-appeal", "status", Map.of("code", "new-unknown-status"), "subservices", List.of(Map.of("rbuSingleRecordPlan", Map.of("recordGuid", "record-guid"))));
        assertEquals("other-appeal", TradeReferences.active(record, "self", registry(record, active)).getFirst().appealId());
        var done = Map.<String, Object>of("_id", "done", "recordId", "record", "status", Map.of("isFinish", true));
        assertTrue(TradeReferences.active(record, "self", registry(record, done)).isEmpty());
    }

    @Test void rollbackPreservesChangedForeignAndReferencedRecords() {
        var context = TradeValidationTest.context("ROIV");
        var row = new TradeRollbackService.JournalEntry("NTOmesto", "record", "record-guid", "organization", TradeRecordFingerprint.of(record));
        var source = Map.<String, Object>of("_id", "import", "status", Map.of("code", "complete"), "unit", Map.of("id", "organization"), "subservices", List.of(Map.of("standardCode", "46535", "tradeMigrationSummary", Map.of("status", "APPLIED"), "migrationCreatedRecords", List.of(Map.of("collection", "NTOmesto", "_id", "record")))));
        var safe = new TradeRollbackService().prepare(source, List.of(row), context, registry(record, Map.of()));
        assertEquals("READY", safe.items().getFirst().state());
        assertThrows(IllegalArgumentException.class, () -> new TradeRollbackService().recheck(new TradeRollbackService.Plan(safe.sourceAppealId(), safe.rollbackAppealId(), "tampered", safe.items()), context, registry(record, Map.of())));
        var changed = new java.util.LinkedHashMap<>(record); changed.put("changedAfterImport", true);
        assertEquals("CHANGED", new TradeRollbackService().prepare(source, List.of(row), context, registry(changed, Map.of())).items().getFirst().reasonCode());
        var foreign = new java.util.LinkedHashMap<>(record); foreign.put("unit", Map.of("id", "other"));
        assertEquals("OWNER", new TradeRollbackService().prepare(source, List.of(row), context, registry(foreign, Map.of())).items().getFirst().reasonCode());
        var active = Map.<String, Object>of("_id", "active", "recordId", "record", "status", Map.of("code", "draft"));
        assertEquals("ACTIVE_REFERENCES", new TradeRollbackService().prepare(source, List.of(row), context, registry(record, active)).items().getFirst().reasonCode());
        assertThrows(IllegalArgumentException.class, () -> new TradeRollbackService().prepare(source, java.util.Collections.nCopies(2501, row), context, registry(record, Map.of())));
        var excluded = new java.util.LinkedHashMap<>(record); excluded.put("isDeleted", true);
        assertEquals("ALREADY_EXCLUDED", new TradeRollbackService().prepare(source, List.of(row), context, registry(excluded, Map.of())).items().getFirst().reasonCode());
    }

    @Test void rechecksThePersistedSourceAppealAndExactJournalBeforeReturningDeletionIntents() {
        var source = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(Map.of("_id", "import", "status", Map.of("code", "complete"), "unit", Map.of("id", "organization"), "subservices", List.of(Map.of("standardCode", "46535", "tradeMigrationSummary", Map.of("status", "APPLIED"), "migrationCreatedRecords", List.of(Map.of("collection", "NTOmesto", "_id", "record"))))));
        var registry = new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.of(collection.equals("rbuModulesAppeals") ? source.get() : record); }
            public List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit) { return List.of(); }
        };
        var service = new TradeRollbackService(); var context = TradeValidationTest.context("ROIV");
        var identity = new TradeRollbackService.JournalEntry("NTOmesto", "record", "record-guid", "organization", TradeRecordFingerprint.of(record));
        var plan = service.prepare(source.get(), List.of(identity), context, registry);
        assertEquals(List.of(identity), service.recheck(plan, context, registry));
        var changed = new java.util.LinkedHashMap<>(source.get()); changed.put("status", Map.of("code", "draft")); source.set(changed);
        assertThrows(IllegalArgumentException.class, () -> service.recheck(plan, context, registry));
    }

    private RegistryAccess registry(Map<String, Object> current, Map<String, Object> appeal) {
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) { return Optional.of(current); }
            public List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit) { return collection.equals("rbuModulesAppeals") && !appeal.isEmpty() ? List.of(appeal) : List.of(); }
        };
    }
}

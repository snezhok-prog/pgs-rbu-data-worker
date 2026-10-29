package ru.evolenta.pgs.worker;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;
import static ru.evolenta.pgs.worker.AdvertisingAcceptanceFaultTest.command;

class AdvertisingRollbackSafetyTest {
    @TempDir Path directory;

    @Test void requiresTrustedPrivateRunInsteadOfOnlyPublicSummary() throws Exception {
        try (Scenario scenario = imported()) {
            String run = clean(at(scenario.sourceSub(), "advertisingMigrationSummary.workerRunKey"));
            scenario.journal.save(run, obj());
            assertThrows(IllegalArgumentException.class, scenario::prepare);
            assertEquals(0, scenario.api.deleted);
        }
    }

    @Test void rejectsPublicJournalChangesAfterPreparation() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.prepare();
            scenario.confirm();
            maps(scenario.sourceSub().get("migrationCreatedRecords")).get(0).put("operationKey", hash("forged-operation"));
            assertThrows(IllegalArgumentException.class, scenario::execute);
            assertEquals(0, scenario.api.deleted);
        }
    }

    @Test void blocksGuidChangesBeforePreparation() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.record().put("guid", "replaced-record-guid");
            scenario.prepare();
            assertEquals("BLOCKED", scenario.planItem().get("state"));
            scenario.confirm();
            scenario.execute();
            assertTrue(scenario.recordExists());
        }
    }

    @Test void blocksGuidChangesAfterPreparation() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.prepare();
            scenario.confirm();
            scenario.record().put("guid", "replaced-record-guid");
            scenario.execute();
            assertTrue(scenario.recordExists());
            assertEquals("BLOCKED", scenario.planItem().get("state"));
        }
    }

    @Test void rejectsForeignOwnerEvenIfPublicJournalIsRebased() throws Exception {
        try (Scenario scenario = imported()) {
            put(scenario.record(), "unit.id", "foreign-organization");
            for (Map<String,Object> entry : maps(scenario.sourceSub().get("migrationCreatedRecords"))) {
                if (scenario.recordId.equals(id(entry)) && scenario.collection.equals(entry.get("collection"))) {
                    entry.put("businessHash", ImportOperations.businessHash(scenario.record()));
                    entry.put("unitId", "foreign-organization");
                }
            }
            assertThrows(IllegalArgumentException.class, scenario::prepare);
            assertEquals(0, scenario.api.deleted);
        }
    }

    @Test void preservesChangedBusinessDataAfterPreparation() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.prepare();
            scenario.confirm();
            put(scenario.record(), "infoRK.numberRK", "changed-after-preparation");
            scenario.execute();
            assertTrue(scenario.recordExists());
            assertEquals("BLOCKED", scenario.planItem().get("state"));
        }
    }

    @Test void protectsCaseWithCompleteRootAndAnActiveSubservice() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.api.activeCases.add(activeCase(scenario.recordId, "complete", "process", false));
            scenario.prepare();
            assertEquals("BLOCKED", scenario.planItem().get("state"));
        }
    }

    @Test void protectsProcessRootEvenWhenItsSubservicesAreComplete() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.api.activeCases.add(activeCase(scenario.recordId, "process", "complete", true));
            scenario.prepare();
            assertEquals("BLOCKED", scenario.planItem().get("state"));
        }
    }

    @Test void searchesUntilEmptyPageWhenBackendReturnsShortPages() throws Exception {
        try (Scenario scenario = imported()) {
            scenario.api.referencePageSize = 1;
            scenario.api.activeCases.add(obj("_id", "unrelated-case", "status", obj("code", "process")));
            scenario.api.activeCases.add(activeCase(scenario.recordId, "process", "process", false));
            scenario.prepare();
            assertEquals("BLOCKED", scenario.planItem().get("state"));
        }
    }

    @Test void repeatedImportDoesNotReplaceTrustedHashesWithChangedData() throws Exception {
        try (Scenario scenario = imported()) {
            Object journal = copy(scenario.sourceSub());
            put(scenario.record(), "infoRK.numberRK", "changed-after-import");
            scenario.operation(false, Command.Operation.IMPORT_APPLY);
            assertEquals(hash(at(journal, "migrationCreatedRecords")), hash(scenario.sourceSub().get("migrationCreatedRecords")));
            scenario.prepare();
            assertEquals("BLOCKED", scenario.planItem().get("state"));
        }
    }

    private static Map<String,Object> activeCase(String recordId, String rootStatus, String subStatus, boolean finished) {
        Map<String,Object> sub = obj("status", obj("code", subStatus));
        if (finished) sub.put("dateFinish", "2026-01-01T00:00:00Z");
        return obj("_id", "active-reference", "status", obj("code", rootStatus), "objects", List.of(obj("_id", recordId)),
            "subservices", List.of(sub));
    }

    private Scenario imported() throws Exception {
        Map<String,Object> fixture;
        try (var input = getClass().getResourceAsStream("/acceptance.json")) { fixture = map(Json.read(input)); }
        AdvertisingAcceptanceFaultTest.Fake api = new AdvertisingAcceptanceFaultTest.Fake(fixture);
        Journal journal = new Journal(directory);
        Map<String,Object> draft = map(api.cases.get(api.mainId).get("importDraft"));
        String key = hash("safety-validation");
        draft.put("workerValidationKey", key);
        Map<String,Object> compact = Operations.compactDraft(draft);
        api.cases.get(api.mainId).put("importDraft", compact);
        journal.save(key, obj("processId", "process", "appealId", api.mainId, "inputHash", ImportOperations.inputHash(compact), "draft", draft));
        Scenario scenario = new Scenario(api, journal);
        scenario.operation(false, Command.Operation.IMPORT_APPLY);
        scenario.operation(false, Command.Operation.IMPORT_FINALIZE);
        api.createRollback();
        scenario.recordId = id(api.records.get(scenario.collection).values().iterator().next());
        return scenario;
    }

    private static final class Scenario implements AutoCloseable {
        final AdvertisingAcceptanceFaultTest.Fake api;
        final Journal journal;
        final Operations operations;
        final String collection = "placeReestrRKCollection";
        String recordId;
        Scenario(AdvertisingAcceptanceFaultTest.Fake api, Journal journal) {
            this.api = api; this.journal = journal; operations = new Operations(journal, () -> {}, true, "https://test.example");
        }
        void operation(boolean rollback, Command.Operation operation) throws Exception {
            operations.execute(new Context(command(api, rollback, operation), api));
        }
        Map<String,Object> sourceSub() { return maps(api.cases.get(api.mainId).get("subservices")).get(0); }
        Map<String,Object> rollbackSub() { return maps(api.cases.get("rollback").get("subservices")).get(0); }
        Map<String,Object> record() { return api.records.get(collection).get(recordId); }
        boolean recordExists() { return api.records.get(collection).containsKey(recordId); }
        Map<String,Object> planItem() { return maps(at(rollbackSub(), "rbuRollbackPlan.items")).stream()
            .filter(item -> collection.equals(item.get("collection")) && recordId.equals(id(item))).findFirst().orElseThrow(); }
        void prepare() throws Exception { operation(true, Command.Operation.ROLLBACK_PREPARE); }
        void confirm() { api.cases.get("rollback").put("rbuRollbackDecision", obj("confirmed", true, "targetAppealId", api.mainId,
            "journalFingerprint", at(rollbackSub(), "rbuRollbackPlan.journalFingerprint"))); }
        void execute() throws Exception { operation(true, Command.Operation.ROLLBACK_EXECUTE); }
        public void close() throws Exception { journal.close(); }
    }
}

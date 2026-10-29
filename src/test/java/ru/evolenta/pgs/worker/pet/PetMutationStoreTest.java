package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.pgs.worker.trade.core.Data;

class PetMutationStoreTest {
    @Test void adoptsOnlyItsCommittedCreateAfterLostResponse() throws Exception {
        var api = new Fake(); var journal = new Memory(); var store = store(api, journal);
        api.loseCreate = true;
        assertThrows(IOException.class, () -> store.create("animalsRecordsCollectionTwo", "a".repeat(64), payload()));
        assertEquals("CREATE_PENDING", journal.read("a".repeat(64)).get("status"));
        assertEquals("record", store.create("animalsRecordsCollectionTwo", "a".repeat(64), payload()).get("_id"));
        assertEquals(1, api.creates);
    }
    @Test void neverAdoptsCreateWithoutIntentOrRetriesAnUnresolvedCreate() throws Exception {
        var api = new Fake(); var journal = new Memory(); var store = store(api, journal);
        api.record = copy(payload()); api.record.put("_id", "record");
        assertThrows(IllegalStateException.class, () -> store.create("animalsRecordsCollectionTwo", "a".repeat(64), payload()));
        api.record = obj(); journal.save("a".repeat(64), obj("status", "CREATE_PENDING", "createdHash", PetRecordHash.businessHash(payload())));
        assertThrows(IllegalStateException.class, () -> store.create("animalsRecordsCollectionTwo", "a".repeat(64), payload()));
        assertEquals(0, api.creates);
    }
    @Test void patchPreservesFieldsAndRecoversThenRestoresOnlyUnchangedNewValue() throws Exception {
        var api = new Fake(); var journal = new Memory(); var store = store(api, journal);
        var receipt = store.create("animalsRecordsCollectionTwo", "a".repeat(64), payload());
        var patch = obj("catchInfo", obj("catchRecordLink", "/registry/act"));
        api.loseUpdate = true;
        assertThrows(IOException.class, () -> store.patch("b".repeat(64), receipt, patch));
        assertEquals("keep", at(api.record, "catchInfo.other"));
        var done = store.patch("b".repeat(64), receipt, patch);
        assertEquals("PATCHED", done.get("status"));
        assertEquals(1, api.updates);
        store.undoPatch("c".repeat(64), done);
        assertEquals("old", at(api.record, "catchInfo.catchRecordLink"));
        assertEquals("keep", at(api.record, "catchInfo.other"));
        store.undoPatch("c".repeat(64), done);
        assertEquals(2, api.updates);
    }
    @Test void refusesPatchOrRestoreAfterAnUnrelatedRecordChange() throws Exception {
        var api = new Fake(); var journal = new Memory(); var store = store(api, journal);
        var receipt = store.create("animalsRecordsCollectionTwo", "a".repeat(64), payload());
        var patched = store.patch("b".repeat(64), receipt, obj("catchInfo", obj("catchRecordLink", "/registry/act")));
        api.record.put("externalChange", true);
        assertThrows(IllegalStateException.class, () -> store.undoPatch("c".repeat(64), patched));
        assertEquals(1, api.updates);
    }
    static PetMutationStore store(Fake api, Memory journal) { return new PetMutationStore(api, journal, () -> {}, "appeal", "owner"); }
    static Map<String,Object> payload() { return obj("guid", "stable-guid", "unit", obj("id", "owner"), "migrationSource", obj("appealId", "appeal", "operationKey", "a".repeat(64), "sourceFileSha256", "f".repeat(64), "workerContract", "pgs-pet-external-v1"), "catchInfo", obj("other", "keep", "catchRecordLink", "old")); }
    static final class Memory implements CheckpointStore {
        final Map<String,Map<String,Object>> rows = new HashMap<>();
        public Map<String,Object> read(String key) { return copy(rows.getOrDefault(key, obj())); }
        public void save(String key,Map<String,Object> value) { rows.put(key,copy(value)); }
    }
    static final class Fake implements PgsApi {
        Map<String,Object> record = obj(); int creates, updates; boolean loseCreate, loseUpdate;
        public Map<String,Object> find(String c,String id) { return copy(record); }
        public List<Map<String,Object>> search(String c,List<Object> f,int p,int size) { return p == 0 && !record.isEmpty() ? List.of(copy(record)) : List.of(); }
        public Map<String,Object> create(String c,Map<String,Object> value) throws Exception { creates++; record=copy(value);record.put("_id","record");if(loseCreate){loseCreate=false;throw new IOException("Committed create response lost");}return copy(record); }
        public void update(String c,String id,String guid,Map<String,Object> patch) throws Exception { assertEquals(record.get("guid"), guid); updates++;record.putAll(copy(patch));if(loseUpdate){loseUpdate=false;throw new IOException("Committed patch response lost");} }
        public void updateSub(String c,String id,String guid,String parent,Map<String,Object> p) { throw new UnsupportedOperationException(); }
        public void delete(String c,String id,String guid) { record=obj(); }
        public byte[] download(String id,long max) { throw new UnsupportedOperationException(); }
    }
}

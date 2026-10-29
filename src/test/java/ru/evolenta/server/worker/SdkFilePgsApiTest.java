package ru.evolenta.server.worker;

import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.zip.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.server.service.FileStorageService;
import ru.evolenta.server.storage.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

class SdkFilePgsApiTest {
    private static final String APPEAL = "a".repeat(24), SOURCE = "b".repeat(24), FILE = "c".repeat(24);
    private static final String PATH = "/SIER/rbuModulesAppeals/2026/10/29/" + SOURCE + "/file.xlsx";

    @Test void readsReferencedSameOwnerContainerFileThroughTheSdkEngine() throws Exception {
        Fixture fixture = new Fixture();
        assertArrayEquals(fixture.bytes, fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
        assertEquals(1, fixture.engine.opened);
        assertEquals(1, fixture.engine.closed);
        assertEquals(0, fixture.registry.mutations);
    }

    @Test void rejectsUnscopedDownloads() {
        assertThrows(IllegalStateException.class, () -> new Fixture().api().download(FILE, 100));
    }

    @Test void rejectsAnUnreferencedFileBeforeOpeningStorage() {
        Fixture fixture = new Fixture();
        fixture.registry.current.put("importFile", obj("_id", "d".repeat(24)));
        assertThrows(IllegalArgumentException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
        assertEquals(0, fixture.engine.opened);
    }

    @Test void rejectsAForeignContainerOwnerBeforeOpeningStorage() {
        Fixture fixture = new Fixture();
        put(fixture.registry.source, "unit.id", "foreign");
        assertThrows(IllegalArgumentException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
        assertEquals(0, fixture.engine.opened);
    }

    @Test void rejectsMismatchedMetadataIdentityCollectionAndStoragePath() {
        for (Map<String,Object> patch : List.of(obj("_id", "d".repeat(24)), obj("entryName", "organizations"),
                obj("id", PATH.replace(SOURCE, "d".repeat(24))), obj("id", PATH.replace("2026/10/29", "../..")))) {
            Fixture fixture = new Fixture();
            fixture.registry.metadata.putAll(patch);
            assertThrows(IllegalArgumentException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
            assertEquals(0, fixture.engine.opened);
        }
    }

    @Test void rejectsTheSdkDefaultStorageFallbackForAnUnknownStorage() {
        Fixture fixture = new Fixture();
        fixture.registry.metadata.put("storage", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
        assertEquals(0, fixture.engine.opened);
    }

    @Test void enforcesTheByteLimitAndClosesTheNativeStream() {
        Fixture fixture = new Fixture();
        fixture.registry.metadata.remove("size");
        assertThrows(IOException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 2));
        assertEquals(1, fixture.engine.opened);
        assertEquals(1, fixture.engine.closed);
    }

    @Test void rejectsAStorageSizeMismatch() {
        Fixture fixture = new Fixture();
        fixture.registry.metadata.put("size", fixture.bytes.length + 1);
        assertThrows(IllegalStateException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
        assertEquals(1, fixture.engine.closed);
    }

    @Test void rejectsMetadataDriftDuringARead() {
        Fixture fixture = new Fixture();
        fixture.engine.onOpen = () -> fixture.registry.metadata.put("id", PATH + ".changed");
        assertThrows(IllegalArgumentException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 100));
        assertEquals(1, fixture.engine.closed);
    }

    @Test void decodesTheNativeCompressedFileAndBoundsLogicalBytes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.engine.bytes = zipped(fixture.bytes);
        fixture.registry.metadata.put("compressed", true);
        fixture.registry.metadata.put("size", fixture.engine.bytes.length);
        assertArrayEquals(fixture.bytes, fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 1000));
        fixture.engine.bytes = zipped(new byte[10000]);
        fixture.registry.metadata.put("size", fixture.engine.bytes.length);
        assertThrows(IOException.class, () -> fixture.api().download("rbuModulesAppeals", APPEAL, FILE, 500));
        assertEquals(2, fixture.engine.closed);
    }

    @Test void delegatesRegistryCallsWithoutUsingAnHttpFileRoute() throws Exception {
        Fixture fixture = new Fixture();
        PgsApi api = fixture.api();
        api.update("animalsRecordsCollectionTwo", "record", "guid", obj("value", true));
        assertEquals(1, fixture.registry.mutations);
        assertEquals(0, fixture.engine.opened);
    }

    @Test void contextPassesItsCaseAndChecksTheTaskLease() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.current.put("subservices", List.of(obj("guid", "sub",
            "camundaProcessInfo", obj("id", "process", "camundaBusinessInfoId", "business"))));
        Map<String,Object> variables = obj();
        obj("mainId", APPEAL, "collectionName", "rbuModulesAppeals.subservices", "camundaBusinessInfoId", "business",
            "rbuDomain", "ADVERTISING", "rbuProfile", "ROIV", "rbuOperation", "PARSE_VALIDATE", "rbuContractVersion", "1")
            .forEach((key,value) -> variables.put(key, obj("type", "String", "value", value)));
        Context context = new Context(new Command(obj("id", "task", "processInstanceId", "process", "activityId", "parse",
            "variables", variables)), fixture.api());
        int[] checks = {0}; context.setLease(() -> checks[0]++);
        assertArrayEquals(fixture.bytes, context.download(FILE, 100));
        assertEquals(2, checks[0]);
    }

    private static byte[] zipped(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry = new ZipEntry("file.xlsx"); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static final class Fixture {
        final byte[] bytes = "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Registry registry = new Registry(bytes.length);
        final Engine engine = new Engine(bytes);
        PgsApi api() {
            FileStorageService service = (FileStorageService) Proxy.newProxyInstance(FileStorageService.class.getClassLoader(),
                new Class<?>[]{FileStorageService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getStorage")) return engine;
                    throw new AssertionError("Unexpected SDK call: " + method.getName());
                });
            return new SdkFilePgsApi(registry, service);
        }
    }

    private static final class Registry implements PgsApi {
        final Map<String,Object> current = obj("_id", APPEAL, "unit", obj("id", "owner"), "importFile", obj("_id", FILE));
        final Map<String,Object> source = obj("_id", SOURCE, "unit", obj("id", "owner"));
        final Map<String,Object> metadata;
        int mutations;
        Registry(int size) { metadata = obj("_id", FILE, "entryId", SOURCE, "entryName", "rbuModulesAppeals", "storage", "S3", "id", PATH, "size", size); }
        public Map<String,Object> find(String collection, String id) {
            if (collection.equals("uploadedFiles")) return copy(metadata);
            if (collection.equals("rbuModulesAppeals") && id.equals(APPEAL)) return copy(current);
            if (collection.equals("rbuModulesAppeals") && id.equals(SOURCE)) return copy(source);
            return obj();
        }
        public List<Map<String,Object>> search(String collection, List<Object> filters, int page, int size) { return List.of(); }
        public void update(String collection, String id, String guid, Map<String,Object> patch) { mutations++; }
        public void updateSub(String collection, String id, String guid, String parent, Map<String,Object> patch) { mutations++; }
        public Map<String,Object> create(String collection, Map<String,Object> value) { mutations++; return value; }
        public void delete(String collection, String id, String guid) { mutations++; }
        public byte[] download(String id, long max) { throw new AssertionError("HTTP file route must not be used"); }
    }

    private static final class Engine implements StorageEngine {
        byte[] bytes;
        int opened, closed;
        Runnable onOpen = () -> {};
        Engine(byte[] bytes) { this.bytes = bytes; }
        public String getStorageName() { return "S3"; }
        public String getRootDir() { return "/SIER"; }
        public StorageInputStream getFileStream(String path) {
            assertEquals(PATH, path); opened++; onOpen.run();
            return new StorageInputStream(new ByteArrayInputStream(bytes)) {
                public void close() throws IOException { closed++; inputStream.close(); }
            };
        }
        public String putInRepository(String entry, String id, String name, InputStream in, Document metadata) { throw new AssertionError("Unexpected upload"); }
        public void downloadFileToStream(OutputStream out, String id) { throw new AssertionError("Use the validated metadata snapshot"); }
        public StorageOutputStream getPutFileStream(String entry, String id, String name, Document metadata) { throw new AssertionError("Unexpected upload"); }
        public boolean deleteFileInRepository(String id) { throw new AssertionError("Unexpected delete"); }
        public String getDirectLinkToFile(String id) { throw new AssertionError("Unexpected direct link"); }
    }
}

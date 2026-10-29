package ru.evolenta.server.worker;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import ru.evolenta.server.service.FileStorageService;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.pgs.worker.integration.RepositoryTaskAdapter;
import ru.evolenta.server.dto.ValueObjectDTO;
import ru.evolenta.server.dto.response.ExternalTaskFetchAndLockRespDTO;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

class RepositoryTaskAdapterTest {
    @TempDir Path directory;

    @Test void rejectsUnconfiguredStandardBeforeOperationWrites() throws Exception {
        FakeApi api = new FakeApi("unconfigured-standard");
        RepositoryTaskAdapter adapter = adapter(api);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> adapter.execute(task(), () -> {}));
        assertEquals("Стандарт отсутствует в allowlist", error.getMessage());
        assertEquals(0, api.writes);
    }

    @Test void allowsConfiguredStandardAndReturnsScalarOperationResult() throws Exception {
        FakeApi api = new FakeApi("41059");
        var result = adapter(api).execute(task(), () -> {});
        assertEquals("CANCELLED", result.getVariables().get("rbuApplicationStatus").getValue());
        assertEquals(2, api.writes);
    }

    @Test void checksCurrentSubserviceInsteadOfAnotherAllowedSubservice() throws Exception {
        FakeApi api = new FakeApi("unconfigured-standard");
        Map<String,Object> allowed = obj("guid", "other-sub", "standardCode", "41059",
            "camundaProcessInfo", obj("id", "other-process", "camundaBusinessInfoId", "other-business"));
        api.appeal.put("subservices", List.of(allowed, maps(api.appeal.get("subservices")).get(0)));
        assertThrows(IllegalArgumentException.class, () -> adapter(api).execute(task(), () -> {}));
        assertEquals(0, api.writes);
    }

    @Test void rejectsMissingStandardCodeAndPrefixMatches() throws Exception {
        for (String standard : List.of("", "4105", "410590")) {
            FakeApi api = new FakeApi(standard);
            assertThrows(IllegalArgumentException.class, () -> adapter(api).execute(task(), () -> {}));
            assertEquals(0, api.writes);
        }
    }

    @Test void springConfigurationRequiresANonemptyAllowlist() {
        AdvertisingWorkerConfiguration configuration = new AdvertisingWorkerConfiguration();
        assertThrows(IllegalArgumentException.class, () -> configuration.rbuStandardAllowlist(new Properties()));
        Properties blank = new Properties();
        blank.setProperty("worker.standardCodes", " , , ");
        assertThrows(IllegalArgumentException.class, () -> configuration.rbuStandardAllowlist(blank));
        assertNotNull(configuration.rbuStandardAllowlist(properties()));
    }

    @Test void allowedStandardStillRequiresAnActiveTaskLease() throws Exception {
        FakeApi api = new FakeApi("41059");
        assertThrows(IllegalStateException.class,
            () -> adapter(api).execute(task(), () -> { throw new IllegalStateException("Lease expired"); }));
        assertEquals(0, api.writes);
    }

    @Test void springHttpRegistryAdapterRequiresTheNativeStorageService() {
        var beans = new StaticListableBeanFactory();
        assertThrows(IllegalStateException.class, () -> new AdvertisingWorkerConfiguration().advertisingPgsApi(
            properties(), beans.getBeanProvider(FileStorageService.class)));
    }

    @Test void actualSpringConfigurationWiresTheConfiguredGuard() throws Exception {
        Properties properties = properties();
        properties.setProperty("worker.integrationVerified", "true");
        Path file = directory.resolve("worker.properties");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
        FakeApi api = new FakeApi("41059");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("worker-test",
                Map.of("worker.enabled", "true", "worker.config-file", file.toString())));
            context.registerBean(PgsApi.class, () -> api);
            context.registerBean(CheckpointStore.class, MemoryStore::new);
            context.register(AdvertisingWorkerConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(StandardAllowlist.class));
            assertEquals("CANCELLED", context.getBean(RepositoryTaskAdapter.class)
                .execute(task(), () -> {}).getVariables().get("rbuApplicationStatus").getValue());
            assertEquals(2, api.writes);
        }
    }

    private static RepositoryTaskAdapter adapter(FakeApi api) {
        return new RepositoryTaskAdapter(api, new MemoryStore(), true, "", new StandardAllowlist(properties()));
    }

    private static Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("worker.standardCodes", " 41059,41060,41059 ");
        return properties;
    }

    private static ExternalTaskFetchAndLockRespDTO task() {
        ExternalTaskFetchAndLockRespDTO task = new ExternalTaskFetchAndLockRespDTO();
        task.setId("task");
        task.setProcessInstanceId("process");
        task.setActivityId("cancel");
        Map<String,ValueObjectDTO> variables = new LinkedHashMap<>();
        obj("mainId", "appeal", "collectionName", "rbuModulesAppeals.subservices",
            "camundaBusinessInfoId", "business", "rbuDomain", "ADVERTISING",
            "rbuProfile", "ROIV", "rbuOperation", "IMPORT_CANCEL", "rbuContractVersion", "1")
            .forEach((key,value) -> variables.put(key, new ValueObjectDTO("String", value, null)));
        task.setVariables(variables);
        return task;
    }

    private static final class MemoryStore implements CheckpointStore {
        private final Map<String,Map<String,Object>> states = new HashMap<>();
        public Map<String,Object> read(String key) { return states.getOrDefault(key, obj()); }
        public void save(String key, Map<String,Object> state) { states.put(key, copy(state)); }
    }

    private static final class FakeApi implements PgsApi {
        private final Map<String,Object> appeal;
        private int writes;
        FakeApi(String standard) {
            appeal = obj("_id", "appeal", "guid", "appeal-guid", "subservices", List.of(
                obj("guid", "sub-guid", "standardCode", standard, "xsdData", obj(),
                    "camundaProcessInfo", obj("id", "process", "camundaBusinessInfoId", "business"))));
        }
        public Map<String,Object> find(String collection, String id) { return copy(appeal); }
        public List<Map<String,Object>> search(String collection, List<Object> filters, int page, int size) { return List.of(); }
        public void update(String collection, String id, String guid, Map<String,Object> patch) { writes++; appeal.putAll(patch); }
        public void updateSub(String collection, String id, String guid, String parent, Map<String,Object> patch) { writes++; }
        public Map<String,Object> create(String collection, Map<String,Object> value) { throw new AssertionError("Unexpected create"); }
        public void delete(String collection, String id, String guid) { throw new AssertionError("Unexpected delete"); }
        public byte[] download(String id, long limit) { throw new AssertionError("Unexpected download"); }
    }
}

package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import ru.evolenta.pgs.worker.trade.core.*;

import java.io.InputStream;
import java.util.*;

class PetDomainTest {
    private static final RegistryAccess EMPTY =
            new RegistryAccess() {
                public Optional<Map<String, Object>> findById(String collection, String id) {
                    return Optional.empty();
                }

                public List<Map<String, Object>> search(
                        String collection, Map<String, Object> filter, int limit) {
                    return List.of();
                }
            };

    @Test
    void decodesAll614RealConfiguredFieldsAnd17RuleMethods() {
        var configuration = new PetConfiguration();
        assertEquals(614, configuration.fields().size());
        assertEquals(17, configuration.ruleMethods().size());
        assertEquals(86, configuration.groupCount());
    }

    @Test
    void realIncidentRowsBuildAllSixCollectionsAndPreserveNestedPayloads() throws Exception {
        Map<String, Object> fixture = fixture();
        WorkbookDraft draft = WorkbookDraft.from(PetData.map(fixture.get("draft")));
        var context = OperationContextForFixture(fixture);
        var plan = new PetModule().mapValidated(draft, context, registry(fixture));
        assertEquals(((Number) fixture.get("expectedRecords")).intValue(), plan.size());
        assertEquals(
                Set.of(
                        "animalCatchOrderRegistryCollection",
                        "animalsRecordsCollectionTwo",
                        "animalCatchActRegistryCollection",
                        "myPetAnimalCardReestr",
                        "animalReleaseActRegistryEntry",
                        "animalTransferActRegistryCollection"),
                plan.stream()
                        .map(PlannedEntity::collection)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(plan.size(), plan.stream().map(PlannedEntity::key).distinct().count());
        assertTrue(
                plan.stream().anyMatch(entity -> entity.data().get("animal") instanceof Map<?, ?>));
        assertTrue(
                plan.stream()
                        .allMatch(
                                entity ->
                                        PetData.map(entity.data().get("unit"))
                                                .get("id")
                                                .equals(context.unitId())));
        assertTrue(
                plan.stream()
                        .allMatch(
                                entity ->
                                        PetData.map(entity.data().get("migrationSource"))
                                                .get("appealId")
                                                .equals(context.appealId())));
        var patches = new PetModule().mapPatches(draft, context);
        assertFalse(patches.isEmpty(), "catch-act back links must be explicit dependent patches");
    }

    @Test
    void estimateCountsRecordsNotRowsIncludingBooleanAndZeroValues() {
        var sheets =
                List.of(
                        sheet("sheet2", row(6, Map.of(9, "order"))),
                        sheet("sheet3", row(6, Map.of(9, "animal", 23, 0))),
                        sheet(
                                "sheet4",
                                row(
                                        6,
                                        Map.of(
                                                9, "card", 364, false, 378, "x", 397, "x", 410, "x",
                                                423, "x"))));
        assertEquals(
                9,
                new PetModule()
                        .estimate(
                                new WorkbookDraft("Мой питомец", "a".repeat(64), sheets, Map.of()))
                        .totalRecords());
    }

    @Test
    void completeMappedPayloadsEqualOriginalPublishedGroovyOnTheSameRealFixture() throws Exception {
        Map<String, Object> fixture = fixture();
        var context = OperationContextForFixture(fixture);
        var draft = WorkbookDraft.from(PetData.map(fixture.get("draft")));
        Map<String, Object> golden;
        try (InputStream input =
                PetDomainTest.class.getResourceAsStream("/pet/pet-mapping-golden.json")) {
            assertNotNull(input);
            golden = new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
        var expected = new LinkedHashMap<String, Map<String, Object>>();
        var ids = new HashMap<String, Object>();
        for (var entity : PetData.maps(golden.get("entities"))) {
            String key =
                    context.migrationId()
                            + "/"
                            + entity.get("sheetCode")
                            + ":"
                            + entity.get("rowNumber")
                            + ":"
                            + entity.get("recordPart");
            expected.put(key, entity);
            ids.put(key, entity.get("id"));
        }
        var actual = new LinkedHashMap<String, Map<String, Object>>();
        var domain = new PetModule();
        for (var entity : domain.mapValidated(draft, context, registry(fixture))) {
            var payload = new LinkedHashMap<>(PetData.map(resolve(entity.data(), ids)));
            var source = PetData.map(payload.remove("migrationSource"));
            assertEquals(
                    expected.get(entity.key()).get("rowHash"),
                    source.get("rowHash"),
                    entity.key() + " rowHash");
            actual.put(entity.key(), payload);
        }
        for (var patch : domain.mapPatches(draft, context))
            actual.put(
                    patch.target().key(),
                    Data.merge(
                            actual.get(patch.target().key()),
                            PetData.map(resolve(patch.patch(), ids))));
        assertEquals(expected.keySet(), actual.keySet());
        for (var entry : actual.entrySet())
            assertNull(
                    firstDifference(
                            new ObjectMapper()
                                    .valueToTree(expected.get(entry.getKey()).get("data")),
                            new ObjectMapper().valueToTree(entry.getValue()),
                            entry.getKey()));
    }

    private static Object resolve(Object value, Map<String, Object> ids) {
        if (value instanceof EntityReference reference)
            return Objects.requireNonNull(ids.get(reference.key()));
        if (value instanceof ReferenceTemplate template)
            return template.prefix() + resolve(template.reference(), ids) + template.suffix();
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            PetData.map(map).forEach((name, item) -> result.put(name, resolve(item, ids)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(item -> resolve(item, ids)).toList();
        return value;
    }

    private static String firstDifference(
            com.fasterxml.jackson.databind.JsonNode expected,
            com.fasterxml.jackson.databind.JsonNode actual,
            String path) {
        if (Objects.equals(expected, actual)) return null;
        if (expected == null || actual == null || expected.getNodeType() != actual.getNodeType())
            return path + " (type or missing value)";
        if (expected.isObject()) {
            var names = new TreeSet<String>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String difference =
                        firstDifference(expected.get(name), actual.get(name), path + "/" + name);
                if (difference != null) return difference;
            }
        } else if (expected.isArray()) {
            if (expected.size() != actual.size()) return path + " (array size)";
            for (int index = 0; index < expected.size(); index++) {
                String difference =
                        firstDifference(expected.get(index), actual.get(index), path + "/" + index);
                if (difference != null) return difference;
            }
        }
        return path + " (value)";
    }

    @Test
    void rejectsDuplicateSourceRowsAndOver2500RecordsBeforeMapping() {
        var duplicated =
                new WorkbookDraft(
                        "Мой питомец",
                        "a".repeat(64),
                        List.of(
                                sheet("sheet2", row(6, Map.of(9, "x")), row(6, Map.of(9, "y"))),
                                sheet("sheet3"),
                                sheet("sheet4")),
                        Map.of());
        assertThrows(IllegalArgumentException.class, () -> new PetModule().estimate(duplicated));
        var rows = new ArrayList<WorkbookDraft.Row>();
        for (int i = 6; i < 2507; i++) rows.add(row(i, Map.of(9, "order-" + i)));
        var overflow =
                new WorkbookDraft(
                        "Мой питомец",
                        "a".repeat(64),
                        List.of(
                                new WorkbookDraft.Sheet("sheet2", columns(9), rows),
                                sheet("sheet3"),
                                sheet("sheet4")),
                        Map.of());
        assertTrue(new PetModule().estimate(overflow).limitExceeded());
    }

    @Test
    void fileResolverIgnoresOtherGroupsAndRejectsAmbiguousNames() {
        var index =
                new PetFiles(
                        Map.of(
                                "documents",
                                List.of(
                                        Map.of(
                                                "groupName",
                                                "Файлы для загрузки в реестр",
                                                "files",
                                                List.of(
                                                        Map.of(
                                                                "_id",
                                                                "f1",
                                                                "originalName",
                                                                "123_photo.jpg",
                                                                "size",
                                                                2))),
                                        Map.of(
                                                "groupName",
                                                "Чужая группа",
                                                "files",
                                                List.of(
                                                        Map.of(
                                                                "_id",
                                                                "f2",
                                                                "originalName",
                                                                "photo.jpg",
                                                                "size",
                                                                2))))));
        assertEquals("f1", index.resolve("photo.jpg", "animal.photo[0]").get("_id"));
        assertEquals(
                "animal.photo[0]",
                index.resolve("photo.jpg", "animal.photo[0]").get("entityFieldPath"));
        assertThrows(IllegalArgumentException.class, () -> index.resolve("missing.jpg", "x"));
        var ambiguous =
                new PetFiles(
                        Map.of(
                                "documentFiles",
                                List.of(
                                        Map.of("_id", "f1", "originalName", "photo.jpg", "size", 2),
                                        Map.of(
                                                "_id",
                                                "f2",
                                                "originalName",
                                                "1_photo.jpg",
                                                "size",
                                                2))));
        assertThrows(IllegalArgumentException.class, () -> ambiguous.resolve("photo.jpg", "x"));
    }

    @Test
    void missingTrustedContextAndWrongTemplateCannotValidateSuccessfully() {
        var draft =
                new WorkbookDraft(
                        "Мой питомец",
                        "a".repeat(64),
                        List.of(
                                sheet("sheet2", row(6, Map.of(9, "x"))),
                                sheet("sheet3"),
                                sheet("sheet4")),
                        Map.of("availableSheets", List.of("renamed")));
        var report =
                new PetModule()
                        .validate(
                                draft,
                                new OperationContext(
                                        "migration", "appeal", "owner", "actor", "OMSU", Map.of()),
                                EMPTY);
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream()
                        .anyMatch(issue -> issue.code().equals("INVALID_SHEET_STRUCTURE")));
        assertTrue(
                report.issues().stream()
                        .anyMatch(issue -> issue.code().equals("ORGANIZATION_REQUIRED")));
    }

    private static Map<String, Object> fixture() throws Exception {
        try (InputStream input =
                PetDomainTest.class.getResourceAsStream("/pet/pet-real-incident.json")) {
            assertNotNull(input);
            return new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
    }

    private static OperationContext OperationContextForFixture(Map<String, Object> fixture) {
        return new OperationContext(
                "pet-fixture",
                "fixture-appeal",
                PetData.text(PetData.map(fixture.get("organization")).get("_id")),
                "fixture-actor",
                "ROIV",
                PetData.map(fixture.get("context")));
    }

    private static RegistryAccess registry(Map<String, Object> fixture) {
        Map<String, Object> organization = PetData.map(fixture.get("organization"));
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) {
                return collection.equals("organizations") && id.equals(organization.get("_id"))
                        ? Optional.of(organization)
                        : Optional.empty();
            }

            public List<Map<String, Object>> search(
                    String collection, Map<String, Object> query, int limit) {
                return List.of();
            }
        };
    }

    static WorkbookDraft.Row row(int number, Map<Integer, Object> values) {
        return new WorkbookDraft.Row(
                number,
                values.entrySet().stream()
                        .map(
                                entry ->
                                        new WorkbookDraft.Cell(
                                                "C" + entry.getKey(),
                                                entry.getValue(),
                                                entry.getValue() instanceof String
                                                        ? "string"
                                                        : "number"))
                        .toList());
    }

    static List<WorkbookDraft.Column> columns(int... attributes) {
        return Arrays.stream(attributes)
                .mapToObj(
                        attribute ->
                                new WorkbookDraft.Column(
                                        "C" + attribute,
                                        String.valueOf(attribute),
                                        Map.<String, Object>of("fieldName", "field" + attribute)))
                .toList();
    }

    static WorkbookDraft.Sheet sheet(String code, WorkbookDraft.Row... rows) {
        var attributes = new TreeSet<Integer>();
        for (var row : rows)
            for (var cell : row.cells())
                attributes.add(Integer.parseInt(cell.column().substring(1)));
        return new WorkbookDraft.Sheet(
                code,
                attributes.stream()
                        .map(
                                attribute ->
                                        new WorkbookDraft.Column(
                                                "C" + attribute,
                                                String.valueOf(attribute),
                                                Map.<String, Object>of(
                                                        "fieldName", "field" + attribute)))
                        .toList(),
                List.of(rows));
    }
}

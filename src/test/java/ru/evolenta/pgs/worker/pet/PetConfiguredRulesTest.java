package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import org.junit.jupiter.api.Test;

import ru.evolenta.pgs.worker.trade.core.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class PetConfiguredRulesTest {
    private static final PetConfiguration CONFIGURATION = new PetConfiguration();
    private static final RegistryAccess EMPTY = registry((collection, filter) -> List.of());

    @Test
    void exercisesEveryOneOfThe17ConfiguredMethodsWithRealFieldConfiguration() {
        var covered = new HashSet<String>();
        reject(covered, "check_required", null, Map.of(), "REQUIRED");
        accept(covered, "check_required", "x", Map.of());
        reject(covered, "is_valid_inn_ogrn", "1.23E12", Map.of(), "IS_VALID_INN_OGRN");
        accept(covered, "is_valid_inn_ogrn", "1234567890123", Map.of());
        reject(covered, "is_valid_uuid", "invalid", Map.of(), "IS_VALID_UUID");
        accept(covered, "is_valid_uuid", "8cd09210-4b66-45f6-b422-36ee01df6e4a", Map.of());
        reject(covered, "is_int", "1.5", Map.of(), "IS_INT");
        accept(covered, "is_int", "1234", Map.of());
        reject(covered, "is_valid_date", "31.02.2026", Map.of(), "IS_VALID_DATE");
        accept(covered, "is_valid_date", "29.02.2024", Map.of());
        reject(covered, "is_valid_time", "24:00", Map.of(), "IS_VALID_TIME");
        accept(covered, "is_valid_time", "23:59", Map.of());
        reject(
                covered,
                "is_valid_reference_value",
                "__NOT_IN_REFERENCE__",
                Map.of(),
                "INVALID_REFERENCE");
        var referenceEnv = environment("is_valid_reference_value", null, Map.of(), EMPTY);
        accept(
                covered,
                "is_valid_reference_value",
                referenceEnv.referenceValues().getFirst(),
                Map.of());
        reject(
                covered,
                "check_special_reference",
                "__OTHER_EVENT__",
                Map.of(),
                "INVALID_SPECIAL_REFERENCE");
        accept(
                covered,
                "check_special_reference",
                rule("check_special_reference").params.get("allowed_value"),
                Map.of());
        var required = rule("check_conditional_required");
        String conditionalField = text(required.params.get("condition_field"));
        Object expected = required.params.get("condition_value");
        reject(
                covered,
                "check_conditional_required",
                null,
                Map.of(conditionalField, expected),
                "CONDITIONAL_REQUIRED");
        accept(
                covered,
                "check_conditional_required",
                "description",
                Map.of(conditionalField, expected));
        var empty = rule("check_conditional_empty");
        String emptyField = text(empty.params.get("condition_field"));
        Object emptyValue = empty.params.get("condition_value");
        if (emptyValue instanceof List<?> values) emptyValue = values.getFirst();
        reject(
                covered,
                "check_conditional_empty",
                "unexpected",
                Map.of(emptyField, emptyValue),
                "MUST_BE_EMPTY");
        accept(covered, "check_conditional_empty", null, Map.of(emptyField, emptyValue));
        reject(
                covered,
                "check_conditional_required_in_block",
                null,
                Map.of("__group", "x"),
                "CONDITIONAL_REQUIRED");
        accept(covered, "check_conditional_required_in_block", "x", Map.of("__group", "x"));
        var exclusive = rule("check_mutually_exclusive");
        String other = text(exclusive.params.get("other_field"));
        reject(
                covered,
                "check_mutually_exclusive",
                "shelter",
                Map.of(other, "pvs"),
                "MUTUALLY_EXCLUSIVE");
        accept(covered, "check_mutually_exclusive", "shelter", Map.of());
        reject(covered, "check_file_exists", "missing.jpg", Map.of(), "CHECK_FILE_EXISTS");
        accept(covered, "check_file_exists", "photo.jpg", Map.of());
        reject(covered, "check_file_extension", "photo", Map.of(), "CHECK_FILE_EXTENSION");
        accept(covered, "check_file_extension", "photo.jpg", Map.of());
        var sizeEnv = environment("check_file_size", "zero.jpg", Map.of(), EMPTY);
        covered.add("check_file_size");
        assertEquals(
                "CHECK_FILE_SIZE",
                PetRuleEngine.check(
                                "check_file_size",
                                "zero.jpg",
                                rule("check_file_size").params,
                                sizeEnv)
                        .orElseThrow()
                        .code());
        accept(covered, "check_file_size", "photo.jpg", Map.of());
        var guidEnv =
                environment(
                        "check_organization_guid_exists",
                        "8cd09210-4b66-45f6-b422-36ee01df6e4a",
                        Map.of(),
                        EMPTY);
        covered.add("check_organization_guid_exists");
        assertEquals(
                "ORGANIZATION_GUID_NOT_FOUND",
                PetRuleEngine.check(
                                "check_organization_guid_exists",
                                "8cd09210-4b66-45f6-b422-36ee01df6e4a",
                                Map.of(),
                                guidEnv)
                        .orElseThrow()
                        .code());
        var found =
                environment(
                        "check_organization_guid_exists",
                        "8cd09210-4b66-45f6-b422-36ee01df6e4a",
                        Map.of(),
                        registry(
                                (collection, filter) ->
                                        List.of(Map.of("guid", filter.get("guid")))));
        assertTrue(
                PetRuleEngine.check(
                                "check_organization_guid_exists",
                                "8cd09210-4b66-45f6-b422-36ee01df6e4a",
                                Map.of(),
                                found)
                        .isEmpty());
        accept(covered, "skip", "unvalidated optional string", Map.of());
        assertEquals(CONFIGURATION.ruleMethods(), covered);
    }

    @Test
    void distinguishesGuidLookupFailureFromAbsenceAndCachesTheResult() {
        var calls = new AtomicInteger();
        var env =
                environment(
                        "check_organization_guid_exists",
                        "8cd09210-4b66-45f6-b422-36ee01df6e4a",
                        Map.of(),
                        registry(
                                (collection, filter) -> {
                                    calls.incrementAndGet();
                                    throw new IllegalStateException("unavailable");
                                }));
        for (int count = 0; count < 2; count++)
            assertEquals(
                    "ORGANIZATION_GUID_LOOKUP_ERROR",
                    PetRuleEngine.check(
                                    "check_organization_guid_exists",
                                    "8cd09210-4b66-45f6-b422-36ee01df6e4a",
                                    Map.of(),
                                    env)
                            .orElseThrow()
                            .code());
        assertEquals(1, calls.get());
    }

    @Test
    void conditionalScopeIsNotTriggeredByValuesInAnUnrelatedBlock() {
        var target = rule("check_conditional_required_in_block");
        var env = environment("check_conditional_required_in_block", null, Map.of(), EMPTY);
        var unrelated =
                env.sheet().columns().stream()
                        .filter(
                                column ->
                                        !column.column().equals(env.column().column())
                                                && !text(column.metadata().get("subBlock"))
                                                        .equals(
                                                                text(
                                                                        env.column()
                                                                                .metadata()
                                                                                .get("subBlock"))))
                        .findFirst()
                        .orElseThrow();
        var row =
                new WorkbookDraft.Row(
                        6, List.of(new WorkbookDraft.Cell(unrelated.column(), "x", "string")));
        var scoped =
                new PetRuleEngine.Environment(
                        CONFIGURATION,
                        env.field(),
                        env.column(),
                        env.sheet(),
                        row,
                        env.draft(),
                        env.files(),
                        env.registry(),
                        new HashMap<>());
        assertTrue(
                PetRuleEngine.check(
                                "check_conditional_required_in_block", null, target.params, scoped)
                        .isEmpty());
    }

    private static void accept(
            Set<String> covered, String method, Object value, Map<String, Object> related) {
        covered.add(method);
        assertTrue(
                PetRuleEngine.check(
                                method,
                                value,
                                rule(method).params,
                                environment(method, value, related, EMPTY))
                        .isEmpty(),
                method);
    }

    private static void reject(
            Set<String> covered,
            String method,
            Object value,
            Map<String, Object> related,
            String code) {
        covered.add(method);
        assertEquals(
                code,
                PetRuleEngine.check(
                                method,
                                value,
                                rule(method).params,
                                environment(method, value, related, EMPTY))
                        .orElseThrow(() -> new AssertionError(method + " accepted bad value"))
                        .code());
    }

    private record ConfiguredRule(Map<String, Object> field, Map<String, Object> params) {}

    private static ConfiguredRule rule(String method) {
        for (var field : CONFIGURATION.fields())
            for (var rule : maps(field.get("rules")))
                if (text(rule.get("method")).equals(method))
                    return new ConfiguredRule(field, map(rule.get("params")));
        throw new AssertionError(method);
    }

    private static PetRuleEngine.Environment environment(
            String method, Object value, Map<String, Object> related, RegistryAccess registry) {
        var rule = rule(method);
        String code = text(rule.field.get("sheet"));
        var expected =
                maps(map(map(CONFIGURATION.profile().get("expected")).get(code)).get("columns"));
        var columns =
                expected.stream()
                        .map(
                                column ->
                                        new WorkbookDraft.Column(
                                                text(column.get("column")),
                                                text(column.get("attribute")),
                                                obj(
                                                        "fieldName",
                                                        column.get("name"),
                                                        "block",
                                                        column.get("block"),
                                                        "subBlock",
                                                        column.get("subBlock"),
                                                        "requiredMarker",
                                                        column.get("requiredMarker"))))
                        .toList();
        var current =
                columns.stream()
                        .filter(
                                column ->
                                        column.attribute()
                                                .equals(text(rule.field.get("attribute"))))
                        .findFirst()
                        .orElseThrow();
        var values = new LinkedHashMap<String, Object>();
        if (value != null) values.put(current.column(), value);
        for (var entry : related.entrySet()) {
            if (entry.getKey().equals("__group")) {
                var groups = CONFIGURATION.groups(code);
                Map<String, Object> group = null;
                for (Object id : list(rule.field.get("groupIds"))) {
                    group =
                            groups.stream()
                                    .filter(candidate -> text(candidate.get("id")).equals(text(id)))
                                    .findFirst()
                                    .orElse(null);
                    if (group != null) break;
                }
                assertNotNull(group);
                var attributes = list(group.get("attributes")).stream().map(PetData::text).toList();
                var other =
                        columns.stream()
                                .filter(
                                        column ->
                                                attributes.contains(column.attribute())
                                                        && !column.column()
                                                                .equals(current.column()))
                                .findFirst()
                                .orElseThrow();
                values.put(other.column(), entry.getValue());
            } else
                for (var column : columns)
                    if (PetRuleEngine.baseNameForField(column.metadata().get("fieldName"))
                            .equals(PetRuleEngine.baseNameForField(entry.getKey())))
                        values.put(column.column(), entry.getValue());
        }
        var row =
                new WorkbookDraft.Row(
                        6,
                        values.entrySet().stream()
                                .map(
                                        entry ->
                                                new WorkbookDraft.Cell(
                                                        entry.getKey(), entry.getValue(), "string"))
                                .toList());
        var sheet = new WorkbookDraft.Sheet(code, columns, List.of(row));
        var draft = new WorkbookDraft("Мой питомец", "a".repeat(64), List.of(sheet), Map.of());
        var files =
                new PetFiles(
                        Map.of(
                                "documentFiles",
                                List.of(
                                        Map.of("_id", "f1", "originalName", "photo.jpg", "size", 1),
                                        Map.of(
                                                "_id",
                                                "f2",
                                                "originalName",
                                                "zero.jpg",
                                                "size",
                                                0))));
        return new PetRuleEngine.Environment(
                CONFIGURATION,
                rule.field,
                current,
                sheet,
                row,
                draft,
                files,
                registry,
                new HashMap<>());
    }

    private static RegistryAccess registry(
            java.util.function.BiFunction<String, Map<String, Object>, List<Map<String, Object>>>
                    search) {
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) {
                return Optional.empty();
            }

            public List<Map<String, Object>> search(
                    String collection, Map<String, Object> filter, int limit) {
                return search.apply(collection, filter);
            }
        };
    }
}

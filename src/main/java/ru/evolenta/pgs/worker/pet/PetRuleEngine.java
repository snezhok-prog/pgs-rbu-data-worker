package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import ru.evolenta.pgs.worker.trade.core.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

final class PetRuleEngine {
    record Failure(String code, String message) {}

    record Environment(
            PetConfiguration configuration,
            Map<String, Object> field,
            WorkbookDraft.Column column,
            WorkbookDraft.Sheet sheet,
            WorkbookDraft.Row row,
            WorkbookDraft draft,
            PetFiles files,
            RegistryAccess registry,
            Map<String, String> organizationCache) {
        Object cell(WorkbookDraft.Column target) {
            return row.cells().stream()
                    .filter(cell -> cell.column().equals(target.column()))
                    .findFirst()
                    .map(WorkbookDraft.Cell::value)
                    .orElse(null);
        }

        List<WorkbookDraft.Column> named(String name) {
            String key = validationKey(baseNameForField(name));
            return sheet.columns().stream()
                    .filter(
                            candidate ->
                                    validationKey(
                                                    baseNameForField(
                                                            candidate.metadata().get("fieldName")))
                                            .equals(key))
                    .toList();
        }

        Object related(String name, Map<String, Object> params) {
            var candidates = new ArrayList<>(named(name));
            String block = validationKey(first(params.get("condition_block"), field.get("block")));
            if (!block.isEmpty()) {
                var scoped =
                        candidates.stream()
                                .filter(
                                        candidate ->
                                                validationKey(candidate.metadata().get("block"))
                                                                .equals(block)
                                                        || validationKey(
                                                                        candidate
                                                                                .metadata()
                                                                                .get("subBlock"))
                                                                .equals(block))
                                .toList();
                if (!scoped.isEmpty()) candidates = new ArrayList<>(scoped);
            }
            String ordinal = ordinal(first(field.get("name"), column.metadata().get("fieldName")));
            if (!ordinal.isEmpty()) {
                var numbered =
                        candidates.stream()
                                .filter(
                                        candidate ->
                                                text(candidate.metadata().get("fieldName"))
                                                        .trim()
                                                        .startsWith(ordinal))
                                .toList();
                if (!numbered.isEmpty()) candidates = new ArrayList<>(numbered);
            }
            int current = columnOrder(column.column());
            if (text(params.get("search_direction")).equals("right")) {
                var right =
                        candidates.stream()
                                .filter(candidate -> columnOrder(candidate.column()) > current)
                                .sorted(
                                        Comparator.comparingInt(
                                                candidate -> columnOrder(candidate.column())))
                                .toList();
                if (!right.isEmpty()) candidates = new ArrayList<>(right);
            } else
                candidates.sort(
                        Comparator.comparingInt(
                                candidate -> Math.abs(columnOrder(candidate.column()) - current)));
            return candidates.isEmpty() ? null : cell(candidates.getFirst());
        }

        List<Map<String, Object>> groups() {
            return configuration.groups(sheet.code());
        }

        List<?> referenceValues() {
            String id = validationClean(field.get("referenceId"));
            var workbook = map(draft.metadata().get("referenceLists"));
            String name = id.replaceFirst("_variant\\d+$", "");
            Object entries = workbook.get("entries");
            for (var entry : entries instanceof Map<?, ?> ? List.of(map(entries)) : maps(entries)) {
                if (validationKey(entry.get("name")).equals(validationKey(name))
                        && !list(entry.get("values")).isEmpty()) return list(entry.get("values"));
            }
            for (var entry : workbook.entrySet())
                if (validationKey(entry.getKey()).equals(validationKey(name))
                        && entry.getValue() instanceof List<?> values
                        && !values.isEmpty()) return values;
            Object embedded = map(configuration.profile().get("referenceLists")).get(id);
            if (embedded instanceof List<?> values) return values;
            return list(map(embedded).get("values"));
        }
    }

    static Optional<Failure> check(
            String method, Object value, Map<String, Object> params, Environment env) {
        boolean present = validationPresent(value);
        String code = null;
        switch (method) {
            case "skip" -> {
                return Optional.empty();
            }
            case "check_required" -> {
                if (!present) code = "REQUIRED";
            }
            case "is_valid_reference_value" -> {
                if (present) {
                    var allowed = env.referenceValues();
                    if (allowed.isEmpty()) code = "REFERENCE_LIST_MISSING";
                    else if (!exactReference(value, allowed)) code = "INVALID_REFERENCE";
                }
            }
            case "check_special_reference" -> {
                if (present) {
                    String allowed = validationClean(params.get("allowed_value"));
                    Object embedded =
                            map(env.configuration.profile().get("referenceLists"))
                                    .get(text(env.field.get("referenceId")));
                    if (!allowed.isEmpty()
                            && (!validationKey(value).equals(validationKey(allowed))
                                    || (embedded instanceof List<?> values
                                            && !values.isEmpty()
                                            && values.stream()
                                                    .noneMatch(
                                                            item ->
                                                                    validationKey(item)
                                                                            .equals(
                                                                                    validationKey(
                                                                                            allowed))))))
                        code = "INVALID_SPECIAL_REFERENCE";
                }
            }
            case "check_conditional_required" -> {
                boolean matches =
                        params.containsKey("condition_value")
                                ? conditionMatches(
                                        env.related(text(params.get("condition_field")), params),
                                        params.get("condition_value"))
                                : validationPresent(
                                        env.related(text(params.get("condition_field")), params));
                if (matches && !present) code = "CONDITIONAL_REQUIRED";
                if (!matches && params.containsKey("else_value") && present) code = "MUST_BE_EMPTY";
            }
            case "check_conditional_empty" -> {
                if (present
                        && conditionMatches(
                                env.related(text(params.get("condition_field")), params),
                                params.get("condition_value"))) code = "MUST_BE_EMPTY";
            }
            case "check_conditional_required_in_block" -> {
                var groups = env.groups();
                String scope = validationClean(params.get("scopeGroupId"));
                Map<String, Object> group = null;
                if (!scope.isEmpty()) {
                    group =
                            groups.stream()
                                    .filter(candidate -> text(candidate.get("id")).equals(scope))
                                    .findFirst()
                                    .orElse(null);
                    if (group == null || list(group.get("attributes")).isEmpty())
                        return Optional.of(
                                new Failure(
                                        "VALIDATION_CONFIG_ERROR",
                                        "Не найдена или пуста группа проверки «" + scope + "»."));
                } else
                    for (Object id : list(env.field.get("groupIds"))) {
                        group =
                                groups.stream()
                                        .filter(
                                                candidate ->
                                                        text(candidate.get("id")).equals(text(id)))
                                        .findFirst()
                                        .orElse(null);
                        if (group != null) break;
                    }
                var attributes =
                        new HashSet<>(
                                group == null
                                        ? List.of()
                                        : list(group.get("attributes")).stream()
                                                .map(PetData::text)
                                                .toList());
                if (!present
                        && env.sheet.columns().stream()
                                .anyMatch(
                                        candidate ->
                                                attributes.contains(candidate.attribute())
                                                        && validationPresent(env.cell(candidate))))
                    code = "CONDITIONAL_REQUIRED";
            }
            case "check_mutually_exclusive" -> {
                if (present) {
                    String otherBlock = validationKey(params.get("other_block"));
                    boolean conflict;
                    if (!otherBlock.isEmpty()) {
                        var attributes = new HashSet<String>();
                        for (var group : env.groups())
                            if (validationKey(group.get("name")).equals(otherBlock))
                                list(group.get("attributes"))
                                        .forEach(attribute -> attributes.add(text(attribute)));
                        conflict =
                                env.sheet.columns().stream()
                                        .anyMatch(
                                                candidate ->
                                                        attributes.contains(candidate.attribute())
                                                                && validationPresent(
                                                                        env.cell(candidate)));
                    } else
                        conflict =
                                validationPresent(
                                        env.related(text(params.get("other_field")), params));
                    if (conflict) code = "MUTUALLY_EXCLUSIVE";
                }
            }
            case "check_file_exists" -> {
                if (present && !env.files.exists(value)) code = "CHECK_FILE_EXISTS";
            }
            case "check_file_extension" -> {
                String name = baseName(value);
                if (present && (!name.contains(".") || name.endsWith(".")))
                    code = "CHECK_FILE_EXTENSION";
            }
            case "check_file_size" -> {
                long min =
                        params.get("min_size") == null
                                ? 1
                                : Long.parseLong(text(params.get("min_size")));
                if (present && !env.files.nonzero(value, min)) code = "CHECK_FILE_SIZE";
            }
            case "check_organization_guid_exists" -> {
                if (present && scalarValid("is_valid_uuid", value, Map.of())) {
                    String guid = validationKey(value);
                    String status = env.organizationCache.get(guid);
                    if (status == null) {
                        try {
                            var records =
                                    env.registry.search("organizations", Map.of("guid", guid), 2);
                            if (records.size() > 1
                                    || records.stream()
                                            .anyMatch(
                                                    record ->
                                                            !validationKey(record.get("guid"))
                                                                    .equals(guid)))
                                throw new IllegalArgumentException(
                                        "Неоднозначный или несоответствующий GUID ответ"
                                                + " organizations");
                            status = records.isEmpty() ? "ORGANIZATION_GUID_NOT_FOUND" : "FOUND";
                        } catch (RuntimeException error) {
                            status = "ORGANIZATION_GUID_LOOKUP_ERROR";
                        }
                        env.organizationCache.put(guid, status);
                    }
                    if (!status.equals("FOUND")) code = status;
                }
            }
            default -> {
                if (!scalarValid(method, value, params)) code = method.toUpperCase(Locale.ROOT);
            }
        }
        if (code == null) return Optional.empty();
        String configured =
                validationClean(first(env.field.get("userMessage"), env.field.get("ruleText")));
        if (configured.matches("(?s).*\\n\\s*2\\..*")) configured = "";
        return Optional.of(
                new Failure(
                        code,
                        configured.isEmpty()
                                ? "Поле «"
                                        + validationClean(env.field.get("name"))
                                        + "» не прошло проверку "
                                        + method
                                        + "."
                                : configured));
    }

    static boolean scalarValid(String method, Object value, Map<String, Object> params) {
        if (!Set.of(
                        "is_int",
                        "is_valid_inn_ogrn",
                        "is_valid_date",
                        "is_valid_time",
                        "is_valid_uuid")
                .contains(method)) {
            throw new IllegalArgumentException("Неподдерживаемое правило валидации " + method);
        }
        if (!validationPresent(value)) return true;
        String text = validationClean(value);
        return switch (method) {
            case "is_int" ->
                    text.matches("\\d+")
                            && (params.get("length") == null
                                    || text.length()
                                            == Integer.parseInt(PetData.text(params.get("length"))))
                            && (params.get("min_length") == null
                                    || text.length()
                                            >= Integer.parseInt(
                                                    PetData.text(params.get("min_length"))))
                            && (params.get("max_length") == null
                                    || text.length()
                                            <= Integer.parseInt(
                                                    PetData.text(params.get("max_length"))));
            case "is_valid_inn_ogrn" -> {
                Object lengths = params.get("length");
                var allowed =
                        lengths instanceof List<?> list
                                ? list
                                : lengths == null ? List.of() : List.of(lengths);
                yield text.matches("\\d+")
                        && (allowed.isEmpty()
                                || allowed.stream()
                                        .anyMatch(
                                                length ->
                                                        text.length()
                                                                == Integer.parseInt(
                                                                        PetData.text(length))));
            }
            case "is_valid_date" -> {
                try {
                    LocalDate.parse(text, DATE);
                    yield true;
                } catch (DateTimeParseException error) {
                    yield false;
                }
            }
            case "is_valid_time" -> text.matches("(?:[01]\\d|2[0-3]):[0-5]\\d");
            case "is_valid_uuid" -> {
                try {
                    UUID.fromString(text);
                    yield true;
                } catch (IllegalArgumentException error) {
                    yield false;
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            "Неподдерживаемое правило валидации " + method);
        };
    }

    static boolean conditionMatches(Object actual, Object expected) {
        var candidates =
                expected instanceof List<?> values ? values : Collections.singletonList(expected);
        return candidates.stream()
                .anyMatch(
                        candidate -> {
                            String marker = validationClean(candidate);
                            if (marker.equals("_not_empty")) return validationPresent(actual);
                            if (marker.equals("_empty")) return !validationPresent(actual);
                            if (marker.startsWith("_not_equal:"))
                                return !validationKey(actual)
                                        .equals(validationKey(marker.substring(11)));
                            return validationKey(actual).equals(validationKey(candidate));
                        });
    }

    static boolean exactReference(Object actual, List<?> expected) {
        return expected.stream()
                .anyMatch(candidate -> validationClean(candidate).equals(text(actual)));
    }

    static String baseNameForField(Object value) {
        return validationClean(value).replaceFirst("^\\d+\\.\\s*", "");
    }

    static String ordinal(Object value) {
        var matcher = java.util.regex.Pattern.compile("^\\d+\\.").matcher(validationClean(value));
        return matcher.find() ? matcher.group() : "";
    }

    static int columnOrder(String value) {
        int result = 0;
        for (char letter : value.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT).toCharArray())
            result = result * 26 + letter - 64;
        return result;
    }
}

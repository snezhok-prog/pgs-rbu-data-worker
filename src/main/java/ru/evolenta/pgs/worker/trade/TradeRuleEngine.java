package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static ru.evolenta.pgs.worker.trade.TradeData.*;

/** Executes the declarative methods found in the decoded published contracts. */
public final class TradeRuleEngine {
    public record RuleIssue(String code, String message) {}
    public record Input(Map<String, Object> related, List<String> allowed,
                        List<Map<String, Object>> files, List<Object> groupValues, boolean stripStoragePrefixes) {
        public Input(Map<String, Object> related, List<String> allowed, List<Map<String, Object>> files, List<Object> groupValues) { this(related, allowed, files, groupValues, true); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    public static final Set<String> METHODS = Set.of("has_extra_spaces", "skip", "check_required", "is_int", "is_valid_inn_ogrn", "check_no_exponential",
            "is_valid_decimal", "is_valid_decimal_dot_only", "is_valid_date", "is_not_future_date", "is_valid_time", "is_valid_phone", "is_valid_email",
            "is_valid_coordinates", "is_valid_address", "is_valid_cadastral_number", "is_valid_code", "is_valid_snils", "is_valid_url", "is_valid_format_x_x",
            "check_max_length", "check_file_extension", "check_file_exists", "is_valid_reference_value", "check_conditional_required", "check_conditional_empty",
            "check_conditional_reference", "check_equal_to_field", "check_less_or_equal", "check_greater_or_equal", "check_greater_than", "check_group_max_length",
            "block_need_to_be_validated_else_empty", "check_required_one_of_group", "check_unique_in_row", "check_same_value_in_column", "check_unique_within_group");
    private final Clock clock;
    public TradeRuleEngine() { this(Clock.systemDefaultZone()); }
    public TradeRuleEngine(Clock clock) { this.clock = clock; }

    public List<RuleIssue> characters(Object value) {
        if (value == null) return List.of();
        String raw = value.toString();
        List<RuleIssue> result = new ArrayList<>();
        if (!raw.equals(raw.trim())) result.add(issue("LEADING_TRAILING_SPACES", "Лишние пробелы в начале или конце строки."));
        if (raw.contains("  ")) result.add(issue("DOUBLE_SPACES", "Двойные пробелы между словами."));
        if (raw.contains("\r") || raw.contains("\n")) result.add(issue("LINE_BREAK", "Перенос строки внутри значения."));
        if (Pattern.compile("[\\t\\u00A0\\u200B-\\u200D\\u2060\\uFEFF]").matcher(raw).find()) result.add(issue("INVISIBLE_SYMBOL", "Невидимый служебный символ."));
        return result;
    }

    public List<RuleIssue> check(String method, Object value, JsonNode params, Input input) {
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Неподдерживаемое правило валидации: " + method);
        Object other = input.related().get(params.path("condition_field").asText());
        boolean condition = params.has("condition_value") ? conditionMatches(other, params.get("condition_value")) : present(other);
        String current = text(value);
        RuleIssue failure = switch (method) {
            case "has_extra_spaces" -> null;
            case "skip" -> null;
            case "check_required" -> present(value) ? null : issue("REQUIRED", "Поле обязательно для заполнения.");
            case "check_conditional_required" -> condition && !present(value) ? issue("CONDITIONAL_REQUIRED", "Поле обязательно при выполненном условии.")
                    : !condition && params.has("else_value") && present(value) ? issue("MUST_BE_EMPTY", "Поле должно быть пустым, если условие не выполнено.") : null;
            case "check_conditional_empty" -> condition && present(value) ? issue("MUST_BE_EMPTY", "Поле должно быть пустым при выполненном условии.") : null;
            case "block_need_to_be_validated_else_empty" -> condition && !present(value) ? issue("CONDITIONAL_REQUIRED", "Поле обязательно при выполненном условии.")
                    : !condition && present(value) ? issue("MUST_BE_EMPTY", "Поле должно быть пустым, если условие не выполнено.") : null;
            case "is_valid_reference_value" -> !present(value) ? null : input.allowed().isEmpty() ? issue("REFERENCE_LIST_MISSING", "Не найден допустимый перечень справочника.")
                    : input.allowed().stream().anyMatch(allowed -> text(allowed).equals(value.toString())) ? null : issue("INVALID_REFERENCE", "Значение отсутствует в допустимом перечне.");
            case "check_conditional_reference" -> conditionalReference(value, params, input, other, condition);
            case "check_equal_to_field" -> !present(value) || key(value).equals(key(input.related().get(params.path("other_field").asText()))) ? null : issue("NOT_EQUAL", "Значения связанных полей должны совпадать.");
            case "check_less_or_equal", "check_greater_or_equal" -> compare(method, value, input.related().get(params.path("other_field").asText()));
            case "check_greater_than" -> greaterThan(value, params);
            case "check_group_max_length" -> groupLength(params, input) <= params.path("max_length").asInt() ? null : issue("GROUP_TOO_LONG", "Суммарная длина связанных полей превышает допустимую.");
            case "check_required_one_of_group" -> condition && input.groupValues().stream().noneMatch(TradeData::present) ? issue("CONDITIONAL_REQUIRED", "Заполните хотя бы одно поле из группы.") : null;
            case "check_unique_in_row" -> present(value) && input.groupValues().stream().filter(v -> key(v).equals(key(value))).count() > 1 ? issue("DUPLICATE_IN_ROW", "Значение повторяется в этой строке.") : null;
            case "check_same_value_in_column" -> input.groupValues().stream().filter(TradeData::present).map(TradeData::key).distinct().count() > 1 ? issue("NOT_SAME_IN_COLUMN", "Все значения колонки должны совпадать.") : null;
            case "check_unique_within_group" -> present(value) && input.groupValues().stream().filter(v -> key(v).equals(key(value))).count() > 1 ? issue("DUPLICATE_IN_GROUP", "Значение повторяется внутри группы.") : null;
            default -> !present(value) || format(method, current, params, input) ? null : issue(method.toUpperCase(java.util.Locale.ROOT), "Значение не прошло проверку " + method + ".");
        };
        return failure == null ? List.of() : List.of(failure);
    }

    private RuleIssue conditionalReference(Object value, JsonNode params, Input input, Object other, boolean condition) {
        if (!present(value) || !condition) return null;
        JsonNode allowed = params.path("allowed_values");
        if (allowed.isObject()) {
            var fields = allowed.fields();
            JsonNode match = JSON.createArrayNode();
            while (fields.hasNext()) { var entry = fields.next(); if (key(entry.getKey()).equals(key(other))) { match = entry.getValue(); break; } }
            allowed = match;
        }
        if (!allowed.isArray()) allowed = JSON.createArrayNode().add(allowed);
        for (JsonNode candidate : allowed) if (key(candidate.asText()).equals(key(value))) return null;
        return allowed.isEmpty() ? null : issue("INVALID_CONDITIONAL_REFERENCE", "Значение недопустимо при выбранном условии.");
    }

    public boolean conditionMatches(Object actual, Object expected) {
        if (expected instanceof JsonNode node) {
            if (node.isArray()) { for (JsonNode value : node) if (conditionMatches(actual, value.asText())) return true; return false; }
            return conditionMatches(actual, node.asText());
        }
        if (expected instanceof List<?> values) return values.stream().anyMatch(value -> conditionMatches(actual, value));
        String marker = text(expected);
        if (marker.equals("_not_empty")) return present(actual);
        if (marker.equals("_empty")) return !present(actual);
        if (marker.startsWith("_not_equal:")) return !key(actual).equals(key(marker.substring(11)));
        return key(actual).equals(key(expected));
    }

    private boolean format(String method, String value, JsonNode params, Input input) {
        return switch (method) {
            case "is_int" -> value.matches("\\d+") && (!params.has("length") || value.length() == params.path("length").asInt())
                    && (!params.has("min_length") || value.length() >= params.path("min_length").asInt()) && (!params.has("max_length") || value.length() <= params.path("max_length").asInt());
            case "is_valid_inn_ogrn" -> value.matches("\\d+") && allowedLength(value, params.path("length"));
            case "check_no_exponential" -> !value.toLowerCase(java.util.Locale.ROOT).contains("e");
            case "is_valid_decimal" -> value.matches("(?:0|[1-9]\\d*)(?:[.,]\\d{1,2})?");
            case "is_valid_decimal_dot_only" -> value.matches("(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?");
            case "is_valid_date", "is_not_future_date" -> validDate(value, method.equals("is_not_future_date"));
            case "is_valid_time" -> value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d");
            case "is_valid_phone" -> value.matches("\\+7 \\(\\d{3}\\) \\d{3}(?: \\d{2} \\d{2}|-\\d{2}-\\d{2})");
            case "is_valid_email" -> value.matches("[-\\p{L}0-9.!#$%&'*+/=?^_`{|}~]+@[-\\p{L}0-9]+(?:\\.[-\\p{L}0-9]+)+");
            case "is_valid_coordinates" -> value.matches("-?\\d{2,3}\\.\\d+, -?\\d{2,3}\\.\\d+");
            case "is_valid_address" -> value.matches("\\d{6}, .+");
            case "is_valid_cadastral_number" -> value.matches("\\d+(?::\\d+){3}");
            case "is_valid_code" -> value.matches("\\d{3}-\\d{3}");
            case "is_valid_snils" -> value.matches("\\d{3}-\\d{3}-\\d{3} \\d{2}");
            case "is_valid_url" -> TradeUrl.valid(value);
            case "is_valid_format_x_x" -> value.matches("\\d+-\\d+");
            case "check_max_length" -> value.length() <= params.path("max_length").asInt();
            case "check_file_extension" -> fileBase(value).contains(".") && !fileBase(value).endsWith(".");
            case "check_file_exists" -> input.files().stream().filter(file -> fileKey(file.getOrDefault("originalName", file.get("name")), input.stripStoragePrefixes()).equals(fileKey(value, input.stripStoragePrefixes()))).count() == 1;
            default -> throw new IllegalArgumentException("Неподдерживаемый формат: " + method);
        };
    }

    private static boolean allowedLength(String value, JsonNode lengths) {
        if (lengths.isMissingNode() || lengths.isNull()) return true;
        if (!lengths.isArray()) return value.length() == lengths.asInt();
        for (JsonNode length : lengths) if (value.length() == length.asInt()) return true;
        return lengths.isEmpty();
    }
    private boolean validDate(String value, boolean notFuture) {
        try { LocalDate date = LocalDate.parse(value, DATE); return !notFuture || !date.isAfter(LocalDate.now(clock)); }
        catch (DateTimeException error) { return false; }
    }
    private static int groupLength(JsonNode params, Input input) {
        int length = 0;
        for (JsonNode field : params.path("group_fields")) length += text(input.related().get(field.asText())).length();
        return length;
    }
    private static RuleIssue compare(String method, Object left, Object right) {
        if (!present(left)) return null;
        BigDecimal a = number(left), b = number(right);
        boolean lower = method.equals("check_less_or_equal");
        return a != null && b != null && (lower ? a.compareTo(b) <= 0 : a.compareTo(b) >= 0) ? null
                : issue(lower ? "NOT_LESS_OR_EQUAL" : "NOT_GREATER_OR_EQUAL", "Нарушено соотношение связанных полей.");
    }
    private static RuleIssue greaterThan(Object value, JsonNode params) {
        if (!present(value)) return null;
        BigDecimal numeric = number(value), minimum = number(params.path("minimum").asText("0"));
        return numeric != null && minimum != null && numeric.compareTo(minimum) > 0 ? null : issue("MUST_BE_POSITIVE", "Значение должно быть больше заданного минимума.");
    }
    static BigDecimal number(Object value) {
        try { return present(value) ? new BigDecimal(text(value).replace(',', '.')) : null; }
        catch (NumberFormatException error) { return null; }
    }
    static String fileBase(Object value) { String raw = text(value).replace('\\', '/'); return raw.substring(raw.lastIndexOf('/') + 1); }
    static String fileKey(Object value) { return key(fileBase(value).replaceFirst("^(?:\\d+_)+", "")); }
    private static String fileKey(Object value, boolean strip) { return strip ? fileKey(value) : key(fileBase(value)); }
    private static RuleIssue issue(String code, String message) { return new RuleIssue(code, message); }
}

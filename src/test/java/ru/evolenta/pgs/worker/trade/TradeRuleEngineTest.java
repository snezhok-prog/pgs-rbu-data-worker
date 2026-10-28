package ru.evolenta.pgs.worker.trade;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeRuleEngineTest {
    private final TradeRuleEngine engine = new TradeRuleEngine(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    private final TradeRuleEngine.Input input = new TradeRuleEngine.Input(Map.of(), List.of(), List.of(), List.of());

    @Test void validatesActualTypesAndFormatsWithBoundaryValues() {
        Object[][] cases = {
            {"is_int", "0001", "1e3", Map.of()}, {"is_int", "123", "12", Map.of("length", 3)},
            {"is_valid_inn_ogrn", "1234567890", "123456789", Map.of("length", List.of(10, 12))},
            {"check_no_exponential", "1000", "1E3", Map.of()}, {"is_valid_decimal", "0,01", "1,001", Map.of()},
            {"is_valid_decimal_dot_only", "1.00", "1,00", Map.of()}, {"is_valid_date", "29.02.2024", "29.02.2025", Map.of()},
            {"is_not_future_date", "23.10.2026", "24.10.2026", Map.of()}, {"is_valid_time", "23:59", "24:00", Map.of()},
            {"is_valid_phone", "+7 (999) 123-45-67", "+79991234567", Map.of()},
            {"is_valid_email", "почта@example.ru", "x@localhost", Map.of()},
            {"is_valid_coordinates", "55.75, 037.61", "55,75;37,61", Map.of()},
            {"is_valid_address", "123456, Москва", "Москва", Map.of()},
            {"is_valid_cadastral_number", "77:01:123:4", "77:01:123", Map.of()},
            {"is_valid_code", "123-456", "123456", Map.of()}, {"is_valid_snils", "123-456-789 01", "12345678901", Map.of()},
            {"is_valid_url", "https://example.ru/a?q=1#x", "https://example.ru/%zz", Map.of()},
            {"is_valid_format_x_x", "1-2", "1:2", Map.of()}, {"check_max_length", "abc", "abcd", Map.of("max_length", 3)},
            {"check_file_extension", "test.pdf", "test.", Map.of()}
        };
        for (Object[] sample : cases) {
            String method = sample[0].toString();
            assertTrue(engine.check(method, sample[1], TradeData.JSON.valueToTree(sample[3]), input).isEmpty(), method + " accepts " + sample[1]);
            assertFalse(engine.check(method, sample[2], TradeData.JSON.valueToTree(sample[3]), input).isEmpty(), method + " rejects " + sample[2]);
        }
        assertFalse(engine.check("is_valid_decimal", -1, TradeData.JSON.createObjectNode(), input).isEmpty());
        assertTrue(engine.check("is_valid_decimal", "", TradeData.JSON.createObjectNode(), input).isEmpty());
    }

    @Test void handlesConditionalRequiredEmptyAndReferenceRules() {
        var selected = new TradeRuleEngine.Input(Map.of("Тип", "Да"), List.of("Разрешено"), List.of(), List.of());
        var params = TradeData.JSON.valueToTree(Map.of("condition_field", "Тип", "condition_value", "Да", "else_value", ""));
        assertEquals("CONDITIONAL_REQUIRED", engine.check("check_conditional_required", "", params, selected).getFirst().code());
        assertEquals("MUST_BE_EMPTY", engine.check("check_conditional_empty", "x", params, selected).getFirst().code());
        assertTrue(engine.check("is_valid_reference_value", "Разрешено", params, selected).isEmpty());
        assertEquals("INVALID_REFERENCE", engine.check("is_valid_reference_value", "разрешено", params, selected).getFirst().code());
        assertTrue(engine.conditionMatches("другое", "_not_equal:нет"));
        assertTrue(engine.conditionMatches("", "_empty"));
    }

    @Test void keepsAllWhitespaceErrorsAndRejectsUnknownRules() {
        assertEquals(List.of("LEADING_TRAILING_SPACES", "DOUBLE_SPACES", "LINE_BREAK", "INVISIBLE_SYMBOL"), engine.characters(" x  y\n\t").stream().map(TradeRuleEngine.RuleIssue::code).toList());
        assertThrows(IllegalArgumentException.class, () -> engine.check("missing_rule", "x", TradeData.JSON.createObjectNode(), input));
    }

    @Test void executesNumericAndGroupRelationsAndFailsClosedForMalformedMinimum() {
        var selected = new TradeRuleEngine.Input(Map.of("Тип", "Да", "Связано", "2", "Другое", "abc"), List.of("Да"), List.of(Map.of("originalName", "001_file.pdf")), List.of("x", "X"));
        var params = TradeData.JSON.valueToTree(Map.of("condition_field", "Тип", "condition_value", "Да", "other_field", "Связано", "group_fields", List.of("Связано", "Другое"), "max_length", 3));
        assertTrue(engine.check("check_less_or_equal", "2", params, selected).isEmpty());
        assertEquals("NOT_LESS_OR_EQUAL", engine.check("check_less_or_equal", "3", params, selected).getFirst().code());
        assertTrue(engine.check("check_greater_or_equal", "2", params, selected).isEmpty());
        assertEquals("NOT_GREATER_OR_EQUAL", engine.check("check_greater_or_equal", "1", params, selected).getFirst().code());
        assertEquals("NOT_EQUAL", engine.check("check_equal_to_field", "1", params, selected).getFirst().code());
        assertEquals("GROUP_TOO_LONG", engine.check("check_group_max_length", "", params, selected).getFirst().code());
        assertEquals("DUPLICATE_IN_ROW", engine.check("check_unique_in_row", "x", params, selected).getFirst().code());
        assertEquals("DUPLICATE_IN_GROUP", engine.check("check_unique_within_group", "x", params, selected).getFirst().code());
        assertTrue(engine.check("check_same_value_in_column", "x", params, selected).isEmpty());
        assertEquals("NOT_SAME_IN_COLUMN", engine.check("check_same_value_in_column", "x", params, new TradeRuleEngine.Input(Map.of(), List.of(), List.of(), List.of("x", "y"))).getFirst().code());
        assertTrue(engine.check("check_file_exists", "file.pdf", params, selected).isEmpty());
        assertFalse(engine.check("check_file_exists", "missing.pdf", params, selected).isEmpty());
        assertEquals("MUST_BE_POSITIVE", engine.check("check_greater_than", "1", TradeData.JSON.valueToTree(Map.of("minimum", "not-a-number")), input).getFirst().code());
        assertTrue(engine.check("check_greater_than", "0.01", TradeData.JSON.createObjectNode(), input).isEmpty());
    }
}

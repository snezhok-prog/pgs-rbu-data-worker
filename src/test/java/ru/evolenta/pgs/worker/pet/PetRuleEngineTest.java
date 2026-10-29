package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class PetRuleEngineTest {
    @Test
    void realConfiguredScalarRulesRejectMalformedValues() {
        assertFalse(
                PetRuleEngine.scalarValid(
                        "is_valid_inn_ogrn", "1.23e10", Map.of("length", List.of(13))));
        assertFalse(
                PetRuleEngine.scalarValid(
                        "is_valid_inn_ogrn", "123", Map.of("length", List.of(13))));
        assertTrue(
                PetRuleEngine.scalarValid(
                        "is_valid_inn_ogrn", "1234567890123", Map.of("length", List.of(13))));
        assertFalse(PetRuleEngine.scalarValid("is_int", "123", Map.of("length", 4)));
        assertTrue(PetRuleEngine.scalarValid("is_int", "0001", Map.of("length", 4)));
        assertFalse(PetRuleEngine.scalarValid("is_valid_date", "31.02.2026", Map.of()));
        assertTrue(PetRuleEngine.scalarValid("is_valid_date", "29.02.2024", Map.of()));
        assertFalse(PetRuleEngine.scalarValid("is_valid_time", "24:00", Map.of()));
        assertTrue(PetRuleEngine.scalarValid("is_valid_time", "23:59", Map.of()));
        assertFalse(PetRuleEngine.scalarValid("is_valid_uuid", "bad", Map.of()));
        assertTrue(
                PetRuleEngine.scalarValid(
                        "is_valid_uuid", "8cd09210-4b66-45f6-b422-36ee01df6e4a", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> PetRuleEngine.scalarValid("invented_success", "x", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> PetRuleEngine.scalarValid("invented_success", null, Map.of()));
    }

    @Test
    void ruleConditionsUseNormalizationButReferenceValuesStayExact() {
        assertTrue(PetRuleEngine.conditionMatches(" Ёлка   ", "елка"));
        assertTrue(PetRuleEngine.conditionMatches("anything", "_not_empty"));
        assertTrue(PetRuleEngine.conditionMatches("", "_empty"));
        assertTrue(PetRuleEngine.conditionMatches("нет", List.of("нет", "не знаю")));
        assertFalse(PetRuleEngine.exactReference(" елка", List.of("елка")));
        assertFalse(PetRuleEngine.exactReference("Ёлка", List.of("елка")));
    }
}

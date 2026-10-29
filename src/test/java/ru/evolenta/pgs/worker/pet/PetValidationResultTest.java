package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import ru.evolenta.pgs.worker.trade.core.*;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

class PetValidationResultTest {
    @Test
    void structuralErrorsBlockEveryRowAndProduceAStableFingerprint() throws Exception {
        var context = PetXlsxIntegrationTest.context("ROIV");
        var draft =
                new PetXlsxParser()
                        .read(
                                new ByteArrayInputStream(PetXlsxIntegrationTest.workbook(true)),
                                "test.xlsx",
                                "storage",
                                context);
        var result =
                new PetModule()
                        .validateAndReport(
                                draft, context, registry(), Instant.parse("2026-10-23T12:00:00Z"));
        assertTrue(result.blocked());
        assertFalse(result.validation().valid());
        assertEquals(0, result.form().get("validRows"));
        assertEquals(1, result.form().get("invalidRows"));
        assertEquals(
                draft.fileSha256() + "|2026-10-23T12:00:00Z|rbu-pet-validation-1.0",
                result.draft().metadata().get("validationFingerprint"));
        assertEquals("VALIDATED", result.draft().metadata().get("status"));
        assertFalse(PetData.maps(result.form().get("errors")).isEmpty());
        assertEquals(false, PetData.map(result.confirmation()).get("confirmApply"));
    }

    private static RegistryAccess registry() {
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) {
                return Optional.of(Map.of("_id", id, "name", "Тестовая организация"));
            }

            public List<Map<String, Object>> search(
                    String collection, Map<String, Object> filter, int limit) {
                return collection.equals("organizations")
                        ? List.of(Map.of("_id", "owner", "guid", filter.get("guid")))
                        : List.of();
            }
        };
    }
}

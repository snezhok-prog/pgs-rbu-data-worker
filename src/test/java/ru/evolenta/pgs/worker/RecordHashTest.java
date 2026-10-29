package ru.evolenta.pgs.worker;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

class RecordHashTest {
    @Test void preservesThePreviouslyCompiledTradeFingerprint() {
        String previous = "2a161be49cb47003422eaa4a2f257eefc2f92bbecf27a06cda0449cf1d60947c";
        assertEquals(previous, RecordHash.of(record()));
        assertEquals(previous, TradeImportOperations.businessHash(record()));
        assertEquals(previous, ImportOperations.businessHash(record()));
    }

    @Test void normalizesNumericRepresentationsRecursively() {
        Map<String,Object> equivalent = record();
        equivalent.put("data", obj("area", new BigDecimal("7.8E+3"), "list", List.of(new BigDecimal(".5000"))));
        assertEquals(RecordHash.of(record()), RecordHash.of(equivalent));
        put(equivalent, "data.area", 7801);
        assertNotEquals(RecordHash.of(record()), RecordHash.of(equivalent));
    }

    @Test void retainsOwnerAndSourceProvenanceInTheFingerprint() {
        Map<String,Object> foreign = record();
        put(foreign, "unit.id", "foreign-organization");
        assertNotEquals(RecordHash.of(record()), RecordHash.of(foreign));
        Map<String,Object> changedSource = record();
        put(changedSource, "migrationSource.operationKey", "other-operation");
        assertNotEquals(RecordHash.of(record()), RecordHash.of(changedSource));
    }

    @Test void excludesPlatformMetadataWithoutChangingInput() {
        Map<String,Object> value = record();
        Map<String,Object> before = copy(value);
        String expected = RecordHash.of(value);
        assertEquals(before, value);
        value.put("guid", "replacement-guid");
        value.put("dateLastModification", "later");
        value.put("entityType", "updated-platform-type");
        assertEquals(expected, RecordHash.of(value));
    }

    private static Map<String,Object> record() {
        return obj("_id", "storage", "guid", "guid", "entityType", "platform", "unit", obj("id", "organization"),
            "migrationSource", obj("appealId", "import", "operationKey", "operation"),
            "data", obj("area", new BigDecimal("7800.0"), "list", List.of(new BigDecimal("0.50"))));
    }
}

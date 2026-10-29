package ru.evolenta.pgs.worker;

import java.math.BigDecimal;
import java.util.*;
import static ru.evolenta.pgs.worker.Util.*;

/** Business data fingerprint; identity is checked separately by ID, GUID and owner. */
public final class RecordHash {
    private RecordHash() {}

    public static String of(Map<String,Object> record) {
        Map<String,Object> business = copy(record);
        for (String field : List.of("_id", "auid", "guid", "dateCreation", "dateLastModification", "lastChangedAt",
                "userCreation", "userLastModification", "lastChangedByAppealId", "entityType")) business.remove(field);
        return hash(canonicalNumbers(business));
    }

    // Storage may return 7800.0 for 7.8E+3; their business value is unchanged.
    private static Object canonicalNumbers(Object value) {
        if (value instanceof Number) return new BigDecimal(value.toString()).stripTrailingZeros();
        if (value instanceof Map) {
            Map<String,Object> result = obj();
            map(value).forEach((key,item) -> result.put(key, canonicalNumbers(item)));
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : list(value)) result.add(canonicalNumbers(item));
            return result;
        }
        return value;
    }
}

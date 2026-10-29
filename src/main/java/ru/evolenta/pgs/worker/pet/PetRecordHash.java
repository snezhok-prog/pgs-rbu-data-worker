package ru.evolenta.pgs.worker.pet;

import java.util.Map;
import ru.evolenta.pgs.worker.RecordHash;

/** Hashes every business field, retaining migration provenance and all linked fields. */
public final class PetRecordHash {
    private PetRecordHash() {}
    public static String businessHash(Map<String,Object> record) {
        return RecordHash.of(record);
    }
}

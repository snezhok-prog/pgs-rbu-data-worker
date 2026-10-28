package ru.evolenta.pgs.worker.trade.core;

import java.util.Map;
import java.util.Objects;

public record PlannedPatch(
        String key, String collection, EntityReference target, Map<String, Object> patch) {
    public PlannedPatch {
        if (key == null
                || key.isBlank()
                || collection == null
                || !collection.matches("[A-Za-z][A-Za-z0-9_]{0,99}"))
            throw new IllegalArgumentException("Invalid planned patch identity");
        Objects.requireNonNull(target, "target");
        patch = Data.immutableMap(patch);
    }
}

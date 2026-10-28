package ru.evolenta.pgs.worker.trade.core;

import java.util.Map;

public record PlannedEntity(String key, String collection, Map<String, Object> data) {
    public PlannedEntity {
        if (key == null
                || key.isBlank()
                || collection == null
                || !collection.matches("[A-Za-z][A-Za-z0-9_]{0,99}"))
            throw new IllegalArgumentException("Invalid planned entity identity");
        data = Data.immutableMap(data);
    }
}

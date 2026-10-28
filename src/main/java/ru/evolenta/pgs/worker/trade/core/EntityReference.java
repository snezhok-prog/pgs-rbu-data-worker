package ru.evolenta.pgs.worker.trade.core;

public record EntityReference(String key, String field) {
    public EntityReference {
        if (key == null || key.isBlank() || !("_id".equals(field) || "guid".equals(field)))
            throw new IllegalArgumentException("Invalid entity reference");
    }
}

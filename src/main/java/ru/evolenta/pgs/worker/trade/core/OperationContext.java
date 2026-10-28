package ru.evolenta.pgs.worker.trade.core;

import java.util.Map;
import java.util.Objects;

public record OperationContext(
        String migrationId,
        String appealId,
        String unitId,
        String actorId,
        String scope,
        Map<String, Object> attributes) {
    public OperationContext {
        for (String value : new String[] {migrationId, appealId, unitId, actorId, scope}) {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("Incomplete trusted operation context");
        }
        Objects.requireNonNull(attributes, "attributes");
        attributes = Data.immutableMap(attributes);
    }
}

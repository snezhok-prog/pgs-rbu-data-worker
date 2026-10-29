package ru.evolenta.pgs.worker.pet;
import ru.evolenta.pgs.worker.trade.core.EntityReference;

import java.util.Objects;

public record ReferenceTemplate(String prefix, EntityReference reference, String suffix) {
    public ReferenceTemplate {
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(reference);
        Objects.requireNonNull(suffix);
    }
}

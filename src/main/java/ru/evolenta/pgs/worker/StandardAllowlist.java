package ru.evolenta.pgs.worker;

import java.util.*;
import static ru.evolenta.pgs.worker.Util.clean;

/** Restricts both worker entry points to explicitly configured PGS standards. */
public final class StandardAllowlist {
    private final Set<String> standards;

    public StandardAllowlist(Properties properties) {
        Set<String> configured = new HashSet<>();
        for (String value : properties.getProperty("worker.standardCodes", "").split(",")) {
            if (!value.trim().isEmpty()) configured.add(value.trim());
        }
        if (configured.isEmpty()) throw new IllegalArgumentException("worker.standardCodes must contain at least one allowed standard");
        standards = Set.copyOf(configured);
    }

    public void require(Context context) {
        if (!standards.contains(clean(context.sub.get("standardCode")))) {
            throw new IllegalArgumentException("Стандарт отсутствует в allowlist");
        }
    }
}

package ru.evolenta.pgs.worker.trade.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementations must enforce the trusted organization context and bounded queries. search must
 * collect ALL matching rows up to the requested limit across backend pages, or throw if
 * completeness cannot be established. A short first HTTP page is not proof of an empty tail.
 * Domains treat size == limit as potentially truncated and fail closed where completeness matters.
 * A raw single-page PGS response is not a compliant implementation of this interface.
 */
public interface RegistryAccess {
    Optional<Map<String, Object>> findById(String collection, String id);

    List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit);
}

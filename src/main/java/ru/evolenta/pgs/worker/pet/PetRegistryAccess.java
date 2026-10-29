package ru.evolenta.pgs.worker.pet;

import java.util.*;
import ru.evolenta.pgs.worker.Context;
import ru.evolenta.pgs.worker.Util;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;

/** Adapts PET's exact-field filters to the shared paginated PGS API. */
public final class PetRegistryAccess implements RegistryAccess {
    private final Context worker;
    public PetRegistryAccess(Context worker) { this.worker = worker; }

    public Optional<Map<String, Object>> findById(String collection, String id) {
        try { return Optional.of(worker.api.find(collection, id)).filter(record -> !record.isEmpty()); }
        catch (Exception error) { throw new IllegalStateException("Не удалось прочитать " + collection + "/" + id, error); }
    }

    public List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit) {
        if (limit < 1 || limit > 20001) throw new IllegalArgumentException("Некорректный предел поиска PET");
        List<Object> conditions = new ArrayList<>();
        filter.forEach((field, value) -> conditions.add(Context.filter(field, value)));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (int page = 0; page < 1000; page++) {
                List<Map<String, Object>> rows = worker.api.search(collection, conditions, page, Math.min(100, limit));
                if (rows.isEmpty()) return result;
                for (Map<String, Object> row : rows) {
                    if (Util.id(row).isEmpty() || !seen.add(Util.id(row))) throw new IllegalStateException("Повторная страница или запись без ID: " + collection);
                    result.add(row);
                    if (result.size() == limit) return result;
                }
            }
        } catch (Exception error) { throw new IllegalStateException("Не удалось полностью проверить " + collection, error); }
        throw new IllegalStateException("Превышен предел страниц поиска PET: " + collection);
    }
}

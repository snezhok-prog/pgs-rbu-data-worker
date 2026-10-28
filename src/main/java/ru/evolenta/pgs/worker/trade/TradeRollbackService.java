package ru.evolenta.pgs.worker.trade;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

/** Prepares and rechecks deletion; a backend adapter must execute an atomic conditional delete. */
public final class TradeRollbackService {
    public record JournalEntry(String collection, String id, String guid, String unitId, String expectedFingerprint) {}
    public record Item(JournalEntry identity, String state, String reasonCode, List<TradeReferences.Reference> activeCases) {}
    public record Plan(String sourceAppealId, String rollbackAppealId, String fingerprint, List<Item> items) {
        public Plan { items = List.copyOf(items); }
        public boolean completeReady() { return items.stream().allMatch(item -> item.state().equals("READY")); }
    }
    public Plan prepare(Map<String, Object> sourceAppeal, List<JournalEntry> journal, OperationContext context, RegistryAccess registry) {
        String sourceId = validateSource(sourceAppeal, journal, context);
        List<Item> items = new ArrayList<>();
        for (JournalEntry entry : journal) items.add(check(entry, sourceId, context.appealId(), registry));
        String fingerprint = fingerprint(sourceId, context.appealId(), items);
        return new Plan(sourceId, context.appealId(), fingerprint, items);
    }
    private static String validateSource(Map<String, Object> sourceAppeal, List<JournalEntry> journal, OperationContext context) {
        if (journal.isEmpty() || journal.size() > TradeModule.MAX_REGISTRY_RECORDS) throw new IllegalArgumentException("Предел одного отката — от 1 до 2500 записей.");
        String sourceId = text(sourceAppeal.get("_id"));
        if (sourceId.isEmpty() || sourceId.equals(context.appealId()) || !text(at(sourceAppeal, "status.code")).equals("complete") || present(at(sourceAppeal, "migrationRollback.completedAt"))) throw new IllegalArgumentException("Исходная миграция недоступна для отката.");
        String coordinator = text(context.attributes().getOrDefault("coordinatorUnitId", context.unitId()));
        if (!coordinator.equals(text(at(sourceAppeal, "unit.id")))) throw new IllegalArgumentException("Исходная миграция принадлежит другому кабинету.");
        String expectedStandard = text(context.attributes().getOrDefault("sourceImportStandardCode", context.scope().equals("OMSU") ? "47342" : "46535"));
        var subs = maps(sourceAppeal.get("subservices")).stream().filter(sub -> text(sub.get("standardCode")).equals(expectedStandard) && text(at(sub, "tradeMigrationSummary.status")).equals("APPLIED")).toList();
        if (subs.size() != 1) throw new IllegalArgumentException("Не найден единственный завершённый журнал миграции нужного стандарта.");
        Set<String> savedKeys = new HashSet<>();
        for (var item : maps(subs.getFirst().get("migrationCreatedRecords"))) savedKeys.add(text(item.get("collection")) + '/' + text(item.getOrDefault("_id", item.get("id"))));
        Set<String> requested = new HashSet<>();
        for (JournalEntry entry : journal) {
            if (!TradeModule.COLLECTIONS.contains(entry.collection()) || !present(entry.id()) || !present(entry.guid()) || !present(entry.unitId()) || !present(entry.expectedFingerprint()) || !requested.add(entry.collection() + '/' + entry.id())) throw new IllegalArgumentException("Некорректная или повторяющаяся запись журнала отката.");
        }
        if (!savedKeys.equals(requested)) throw new IllegalArgumentException("Состав журнала отката не совпадает с сохранённой миграцией.");
        return sourceId;
    }
    public List<JournalEntry> recheck(Plan plan, OperationContext context, RegistryAccess registry) {
        if (!plan.rollbackAppealId().equals(context.appealId())) throw new IllegalArgumentException("План отката относится к другому делу.");
        if (!plan.fingerprint().equals(fingerprint(plan.sourceAppealId(), plan.rollbackAppealId(), plan.items()))) throw new IllegalArgumentException("Изменён сохранённый план отката.");
        var source = registry.findById("rbuModulesAppeals", plan.sourceAppealId()).orElseThrow(() -> new IllegalArgumentException("Исходная миграция больше не существует."));
        if (!plan.sourceAppealId().equals(validateSource(TradeContext.requireLive(source, plan.sourceAppealId()), plan.items().stream().map(Item::identity).toList(), context))) throw new IllegalArgumentException("Изменилась идентичность исходной миграции.");
        List<JournalEntry> result = new ArrayList<>();
        for (Item item : plan.items()) if (item.state().equals("READY")) {
            Item current = check(item.identity(), plan.sourceAppealId(), context.appealId(), registry);
            if (!current.state().equals("READY")) throw new IllegalArgumentException("Запись изменилась после подготовки плана: " + current.reasonCode());
            result.add(item.identity());
        }
        return List.copyOf(result);
    }
    public boolean verifyDeleted(JournalEntry item, RegistryAccess registry) { return registry.findById(item.collection(), item.id()).isEmpty(); }
    private static String fingerprint(String sourceId, String rollbackId, List<Item> items) { return TradeRecordFingerprint.of(Map.of("sourceAppealId", sourceId, "rollbackAppealId", rollbackId, "items", items)); }
    private Item check(JournalEntry entry, String sourceId, String ownAppealId, RegistryAccess registry) {
        var found = registry.findById(entry.collection(), entry.id());
        if (found.isEmpty()) return blocked(entry, "MISSING");
        if (Boolean.TRUE.equals(found.get().get("isDeleted"))) return blocked(entry, "ALREADY_EXCLUDED");
        var current = TradeContext.requireLive(found.get(), entry.id());
        if (!entry.unitId().equals(text(at(current, "unit.id") == null ? current.get("dictionaryUnitId") : at(current, "unit.id")))) return blocked(entry, "OWNER");
        if (!sourceId.equals(text(at(current, "migrationSource.appealId")))) return blocked(entry, "ORIGIN");
        if (!entry.guid().equals(text(current.get("guid")))) return blocked(entry, "GUID");
        if (!entry.expectedFingerprint().equals(TradeRecordFingerprint.of(current))) return blocked(entry, "CHANGED");
        var refs = TradeReferences.active(current, ownAppealId, registry);
        return refs.isEmpty() ? new Item(entry, "READY", "", List.of()) : new Item(entry, "BLOCKED", "ACTIVE_REFERENCES", refs);
    }
    private static Item blocked(JournalEntry entry, String reason) { return new Item(entry, "BLOCKED", reason, List.of()); }
}

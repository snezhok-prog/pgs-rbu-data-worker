package ru.evolenta.pgs.worker.trade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

public final class TradeReferences {
    public record Reference(String collection, String appealId, String number, String status) {}
    private static final List<String> COLLECTIONS = List.of("metaReglamentNtoAppeals", "reglamentNtoAppeals", "reglamentMarketAppeals", "rbuModulesAppeals");
    private static final List<String> FIELDS = List.of("searchInfoInCollectionData.result._id", "buffSearch.result._id", "oldSearch.result._id", "parentData.searchInfoInCollectionDataParent.result._id",
            "dataForCreateChildAppeals.appealData.parentData.searchInfoInCollectionDataParent.result._id", "tempData.processingRegistryItemData.data._id", "searchResult.content._id", "searchResult_DEBUG.content._id",
            "subservices.xsdData.searchResult.content._id", "licenseId", "appealEntryId", "currentRegisterId", "recordId", "registryRecordId", "registryEntryId", "singleRecordPlan.recordId", "singleRecordPlan.recordGuid",
            "subservices.rbuSingleRecordPlan.recordId", "subservices.rbuSingleRecordPlan.recordGuid", "subservices.xsdData.selectedRecordId", "searchInfoInCollectionData.result.guid", "currentRegisterGuid", "registryRecordGuid");
    private TradeReferences() {}
    static List<Reference> active(Map<String, Object> record, String ownAppealId, RegistryAccess registry) {
        Set<String> tokens = new LinkedHashSet<>(); for (String name : List.of("_id", "guid")) if (present(record.get(name))) tokens.add(text(record.get(name)));
        if (tokens.isEmpty()) throw new IllegalArgumentException("Нельзя проверить связи записи без идентификатора.");
        List<Map<String, Object>> any = FIELDS.stream().map(field -> Map.<String, Object>of("field", field, "operator", "in", "value", List.copyOf(tokens))).toList();
        Map<String, Object> filter = Map.of("search", Map.of("search", List.of(Map.of("orSubConditions", any))));
        List<Reference> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String collection : COLLECTIONS) {
            var found = registry.search(collection, filter, 100000);
            if (found.size() >= 100000) throw new IllegalArgumentException("Превышен безопасный объём проверки ссылок.");
            for (var appeal : found) {
                String id = text(appeal.get("_id")); if (id.isEmpty()) throw new IllegalArgumentException("Поиск ссылок вернул запись без идентификатора.");
                if (!seen.add(collection + '/' + id)) throw new IllegalArgumentException("Поиск ссылок вернул повторяющуюся запись.");
                if (collection.equals("rbuModulesAppeals") && id.equals(ownAppealId)) continue;
                boolean referenced = FIELDS.stream().anyMatch(field -> valuesAt(appeal, field).stream().anyMatch(value -> tokens.contains(text(value))));
                if (!referenced) continue;
                List<Map<String, Object>> subs = maps(appeal.get("subservices"));
                boolean finished = Boolean.TRUE.equals(at(appeal, "status.isFinish")) || text(at(appeal, "status.code")).equals("complete") && !subs.isEmpty() && subs.stream().allMatch(sub -> present(sub.get("dateFinish")) && text(at(sub, "status.code")).equals("complete"));
                if (!finished) result.add(new Reference(collection, id, text(appeal.get("number")), text(at(appeal, "status.name") == null ? at(appeal, "status.code") : at(appeal, "status.name"))));
            }
        }
        return List.copyOf(result);
    }
}

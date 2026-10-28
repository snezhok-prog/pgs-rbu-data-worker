package ru.evolenta.pgs.worker.trade;

import java.util.List;
import java.util.Map;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

record TradeContext(Map<String, Object> organization, Map<String, Object> organizationCard,
                    Map<String, Object> passport, Map<String, Object> municipality, List<Map<String, Object>> files) {
    static TradeContext load(OperationContext context, RegistryAccess registry) {
        if (!List.of("ROIV", "OMSU").contains(context.scope())) throw new IllegalArgumentException("Неизвестная область полномочий.");
        Map<String, Object> selected = map(context.attributes().get("organization"));
        Map<String, Object> card = Map.of();
        String cardId = text(selected.get("_id"));
        String organizationId = text(selected.getOrDefault("organizationId", at(selected, "organization._id")));
        if (!cardId.isEmpty()) {
            var found = registry.findById("rbuRegionOrganizations", cardId);
            if (found.isPresent()) {
                card = requireLive(found.get(), cardId);
                organizationId = text(card.getOrDefault("organizationId", at(card, "organization._id")));
            } else if (organizationId.isEmpty()) organizationId = cardId;
        }
        if (organizationId.isEmpty()) throw new IllegalArgumentException("Организация не разрешена в карточку organizations.");
        Map<String, Object> organization = requireLive(registry.findById("organizations", organizationId).orElseThrow(() -> new IllegalArgumentException("Организация ПГС не найдена.")), organizationId);
        if (!organizationId.equals(context.unitId())) throw new IllegalArgumentException("Владелец доверенного контекста не совпадает с выбранной организацией.");
        if (context.scope().equals("OMSU")) {
            if (card.isEmpty() || !organizationId.equals(text(card.get("organizationId")))) throw new IllegalArgumentException("Импорт доступен только для своей организации.");
            if (!Boolean.TRUE.equals(context.attributes().get("validationOnly")) && !Boolean.TRUE.equals(card.get("isMigration"))) throw new IllegalArgumentException("У организации отсутствует разрешение на самостоятельный импорт данных.");
            String coordinatorId = text(context.attributes().getOrDefault("coordinatorUnitId", context.unitId()));
            if (!organizationId.equals(coordinatorId)) throw new IllegalArgumentException("Импорт доступен только для своей организации.");
        }
        Map<String, Object> passport = resolve(registry, "rbuRegionPassports", context.attributes().get("region"), "regionName");
        Map<String, Object> municipality = resolve(registry, "rbuRegionMunicipalities", context.attributes().get("municipality"), "municipalityName");
        if (present(municipality.get("passportId")) && !text(municipality.get("passportId")).equals(text(passport.get("_id")))) throw new IllegalArgumentException("Муниципальное образование не относится к выбранному паспорту.");
        if (!card.isEmpty() && present(card.get("municipalityRecordId")) && !text(card.get("municipalityRecordId")).equals(text(municipality.get("_id")))) throw new IllegalArgumentException("Организация не относится к выбранному муниципальному образованию.");
        return new TradeContext(organization, card, passport, municipality, maps(context.attributes().get("documentFiles")));
    }
    static Map<String, Object> requireLive(Map<String, Object> row, String id) {
        if (!id.equals(text(row.get("_id"))) || Boolean.TRUE.equals(row.get("isDeleted")) || row.containsKey("error") || row.containsKey("errors") || Boolean.FALSE.equals(row.get("success"))) throw new IllegalArgumentException("Некорректная или исключённая из использования запись: " + id);
        return row;
    }
    static Map<String, Object> resolve(RegistryAccess registry, String collection, Object selection, String nameField) {
        Map<String, Object> selected = map(selection);
        String id = text(selected.getOrDefault("_id", selected.get("id")));
        if (!id.isEmpty()) return requireLive(registry.findById(collection, id).orElseThrow(() -> new IllegalArgumentException("Не найдена запись " + collection)), id);
        String name = text(selected.getOrDefault(nameField, selected.getOrDefault("name", selection instanceof Map<?, ?> ? null : selection)));
        if (name.isEmpty()) throw new IllegalArgumentException("Не заполнено поле " + nameField);
        List<Map<String, Object>> rows = registry.search(collection, query(nameField, "eq", name), 2);
        if (rows.size() != 1) throw new IllegalArgumentException("Не найдена единственная запись " + collection + "/" + name);
        return requireLive(rows.getFirst(), text(rows.getFirst().get("_id")));
    }
    static Map<String, Object> query(String field, String operator, Object value) { return Map.of("search", Map.of("search", List.of(Map.of("field", field, "operator", operator, "value", value), Map.of("field", "isDeleted", "operator", "neq", "value", true)))); }
    String regionName() { return text(passport.get("regionName")); }
    String municipalityName() { return text(municipality.get("municipalityName")); }
    String organizationId() { return text(organization.get("_id")); }
}

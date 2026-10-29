package ru.evolenta.pgs.worker;

import static ru.evolenta.pgs.worker.Util.*;
import java.util.*;
import ru.evolenta.pgs.worker.pet.PetRegistryAccess;
import ru.evolenta.pgs.worker.trade.core.OperationContext;

/** Resolves the selected PET records and their organization from current authoritative data. */
public final class PetGuard {
    public final Map<String, Object> organization, card, region, municipality;
    public final String cabinetId;
    private final Context worker;

    public PetGuard(Context worker, boolean requireMigration) {
        this.worker = worker;
        cabinetId = owner(worker.appeal);
        Map<String, Object> cabinet = live("organizations", cabinetId);
        card = live("rbuPetRegionOrganizations", id(worker.selection("organization")));
        Map<String, Object> selectedRegion = worker.selection("region");
        if (selectedRegion.isEmpty()) selectedRegion = worker.selection("passport");
        region = live("rbuPetRegionPassports", id(selectedRegion));
        municipality = live("rbuPetRegionMunicipalities", id(worker.selection("municipality")));
        String organizationId = clean(first(card.get("organizationId"), at(card, "organization._id"), at(card, "organization.id")));
        if (organizationId.isEmpty()) throw new IllegalArgumentException("Организация PET не связана с кабинетом ПГС");
        organization = live("organizations", organizationId);
        if (!"active".equals(card.get("accessStatus")) || !"673151".equals(clean(card.get("serviceCode"))) || !"MY_PET".equals(card.get("serviceDomain")))
            throw new IllegalArgumentException("Карточка организации не предоставляет действующий доступ к услуге PET");
        if (clean(card.get("organizationGuid")).isEmpty() || !clean(card.get("organizationGuid")).equals(clean(organization.get("guid"))))
            throw new IllegalArgumentException("GUID карточки PET не совпадает с текущим кабинетом организации");
        String regionCode = clean(region.get("regionCode"));
        if (regionCode.isEmpty() || !regionCode.equals(clean(municipality.get("regionCode")))
                || !regionCode.equals(clean(card.get("regionCode"))) || !id(region).equals(clean(municipality.get("passportId")))
                || !id(region).equals(clean(card.get("passportId"))))
            throw new IllegalArgumentException("Нарушены связи субъекта РФ, МО и организации PET");
        String municipalityId = clean(first(municipality.get("municipalityId"), municipality.get("oktmo")));
        if (municipalityId.isEmpty() || maps(card.get("municipalities")).stream().noneMatch(item -> municipalityId.equals(clean(item.get("municipalityId")))))
            throw new IllegalArgumentException("Выбранное МО не входит в область полномочий организации PET");
        if (present(card.get("municipalityId")) && !municipalityId.equals(clean(card.get("municipalityId"))))
            throw new IllegalArgumentException("Организация PET относится к другому МО");
        if (present(card.get("municipalityRecordId")) && !id(municipality).equals(clean(card.get("municipalityRecordId"))))
            throw new IllegalArgumentException("Карточка PET связана с другим МО");
        Set<String> allowed = new HashSet<>();
        for (Object value : Arrays.asList(cabinet.get("regions"), cabinet.get("region"))) {
            if (value instanceof List) for (Map<String, Object> item : maps(value)) allowed.add(clean(item.get("code")));
            else if (value instanceof Map) allowed.add(clean(map(value).get("code")));
            else if (value != null) throw new IllegalArgumentException("Некорректная область полномочий кабинета");
        }
        allowed.add(clean(cabinet.get("regionCode"))); allowed.remove("");
        if (!allowed.contains(regionCode)) throw new IllegalArgumentException("Субъект РФ не входит в область полномочий владельца дела");
        if (worker.command.profile == Command.Profile.OMSU) {
            if (!cabinetId.equals(organizationId)) throw new IllegalArgumentException("ОМСУ может работать только со своей организацией");
            if (requireMigration && !Boolean.TRUE.equals(card.get("isMigration"))) throw new IllegalArgumentException("Организации PET не предоставлено право самостоятельного импорта");
        }
        if (clean(card.get("organizationName")).isEmpty() || clean(card.get("ogrn")).isEmpty())
            throw new IllegalArgumentException("В карточке организации PET не заполнены наименование и ОГРН");
    }

    public static String owner(Map<String, Object> appeal) {
        String owner = clean(first(appeal.get("unitId"), at(appeal, "unit.id"))), nested = clean(at(appeal, "unit.id"));
        if (owner.isEmpty() || !nested.isEmpty() && !owner.equals(nested)) throw new IllegalArgumentException("Не определён однозначный владелец дела");
        return owner;
    }

    private Map<String, Object> live(String collection, String identifier) {
        if (identifier.isEmpty()) throw new IllegalArgumentException("Не заполнен идентификатор " + collection);
        Map<String, Object> record = new PetRegistryAccess(worker).findById(collection, identifier)
                .orElseThrow(() -> new IllegalArgumentException("Не найдена запись " + collection + "/" + identifier));
        if (!identifier.equals(id(record)) || bool(record.get("isDeleted")) || bool(record.get("deleted"))
                || Boolean.FALSE.equals(record.get("isActive")) || present(record.get("status")) && !(record.get("status") instanceof Map) && !"active".equals(record.get("status")))
            throw new IllegalArgumentException("Выбранная запись " + collection + " не действует");
        return record;
    }

    public Map<String, Object> context() {
        return obj("organization", card, "region", region, "municipality", municipality,
                "organizationId", id(organization), "cabinetId", cabinetId);
    }

    public OperationContext operationContext(String time) {
        Map<String, Object> attrs = obj("organization", card, "region", region, "municipality", municipality,
                "documents", list(worker.appeal.get("documents")), "appealNumber", worker.appeal.get("number"), "operationTime", time);
        return new OperationContext(worker.command.processId, worker.command.mainId, id(organization), "external-worker", worker.command.profile.name(), attrs);
    }
}

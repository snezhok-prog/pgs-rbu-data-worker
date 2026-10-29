package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import ru.evolenta.pgs.worker.trade.core.*;

import java.math.BigDecimal;
import java.util.*;

final class PetPayloadMapper {
    private final PetConfiguration configuration;
    private final PetFiles files;
    private final OperationContext context;
    private final Map<String, Object> unit;
    private final WorkbookDraft draft;
    private final List<PlannedEntity> plan = new ArrayList<>();

    PetPayloadMapper(
            PetConfiguration configuration,
            WorkbookDraft draft,
            OperationContext context,
            RegistryAccess registry) {
        this.configuration = configuration;
        this.draft = draft;
        this.context = context;
        files = new PetFiles(context.attributes());
        var selected = map(context.attributes().get("organization"));
        String organizationId = PetValidator.resolveOrganization(selected, registry);
        if (!organizationId.equals(context.unitId()))
            throw new IllegalArgumentException(
                    "Организация назначения не совпадает с владельцем задания");
        var organization =
                registry.findById("organizations", organizationId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Организация назначения не найдена"));
        if (!clean(organization.get("_id")).equals(organizationId))
            throw new IllegalArgumentException("organizations вернул другую организацию");
        unit =
                obj(
                        "id",
                        organizationId,
                        "name",
                        clean(organization.get("name")),
                        "shortName",
                        clean(first(organization.get("shortName"), organization.get("name"))),
                        "inn",
                        first(organization.get("inn"), path(organization, "additionalData.inn")),
                        "ogrn",
                        first(organization.get("ogrn"), path(organization, "additionalData.ogrn")),
                        "kpp",
                        first(organization.get("kpp"), path(organization, "additionalData.kpp")));
    }

    record Values(WorkbookDraft draft, WorkbookDraft.Sheet sheet, WorkbookDraft.Row row) {
        Object v(int attribute) {
            return draft.value(sheet, row, attribute);
        }

        String s(int attribute) {
            return clean(v(attribute));
        }

        boolean has(int from, int to) {
            for (int attribute = from; attribute <= to; attribute++)
                if (present(v(attribute))) return true;
            return false;
        }
    }

    List<PlannedEntity> buildPlan() {
        for (String code : List.of("sheet2", "sheet3", "sheet4")) {
            var sheet =
                    draft.sheets().stream()
                            .filter(candidate -> candidate.code().equals(code))
                            .findFirst()
                            .orElseThrow();
            for (var row : sheet.rows()) {
                var values = new Values(draft, sheet, row);
                switch (code) {
                    case "sheet2" -> order(values);
                    case "sheet3" -> stray(values);
                    case "sheet4" -> card(values);
                    default -> throw new IllegalArgumentException(code);
                }
            }
        }
        return List.copyOf(plan);
    }

    private Map<String, Object> dictionary(Object value, String name) {
        return dictionary(value, name, "");
    }

    private Map<String, Object> dictionary(Object value, String name, String fallback) {
        String text = clean(value);
        if (text.isEmpty()) return null;
        String code = clean(first(configuration.dictionary(name).get(key(text)), fallback));
        return code.isEmpty() ? obj("name", text) : obj("code", code, "name", text);
    }

    private static Map<String, Object> yesState(Object value) {
        String text = key(value);
        if (text.isEmpty()) return null;
        if (Set.of("да", "есть").contains(text) || text.contains("налич"))
            return obj("code", "presence", "name", "Да");
        if (text.equals("нет") || text.contains("отсут"))
            return obj("code", "absence", "name", "Нет");
        return obj("code", "unknown", "name", "Не знаю");
    }

    private static Map<String, Object> aggression(Object value) {
        String text = key(value);
        if (text.isEmpty()) return null;
        boolean yes = text.equals("была") || text.startsWith("да");
        return obj("code", yes ? "presence" : "absence", "name", yes ? "Была" : "Не было");
    }

    private Map<String, Object> file(Values row, int attribute, String target) {
        return files.resolve(row.v(attribute), target);
    }

    private List<Map<String, Object>> fileList(Values row, int attribute, String target) {
        var value = file(row, attribute, target);
        return value == null ? List.of() : List.of(value);
    }

    private Map<String, Object> base(boolean units) {
        return units ? obj("unit", unit, "units", List.of(unit)) : obj("unit", unit);
    }

    private void add(Values row, String part, String collection, Map<String, Object> payload) {
        String operationKey = entityKey(context, row.sheet.code(), row.row.excelRow(), part);
        payload.put("parentEntries", collection);
        payload.put(
                "migrationSource",
                obj(
                        "appealId",
                        context.appealId(),
                        "appealNumber",
                        context.attributes().get("appealNumber"),
                        "parserVersion",
                        draft.metadata().get("parserVersion"),
                        "validationVersion",
                        draft.metadata().get("validationVersion"),
                        "sourceFile",
                        map(draft.metadata().get("file")).get("originalName"),
                        "sourceFileId",
                        first(
                                map(draft.metadata().get("file")).get("id"),
                                map(draft.metadata().get("file")).get("_id")),
                        "sourceFileSha256",
                        draft.fileSha256(),
                        "rowHash",
                        rowHash(row),
                        "sheetCode",
                        row.sheet.code(),
                        "sheetName",
                        map(draft.metadata().get("sheetNames")).get(row.sheet.code()),
                        "rowNumber",
                        row.row.excelRow(),
                        "recordPart",
                        part,
                        "operationKey",
                        operationKey));
        plan.add(new PlannedEntity(operationKey, collection, payload));
    }

    static String entityKey(OperationContext context, String sheet, int row, String part) {
        return context.migrationId() + "/" + sheet + ":" + row + ":" + part;
    }

    private static String rowHash(Values row) {
        var cells = new ArrayList<Map<String, Object>>();
        for (var cell : row.row.cells()) {
            var column =
                    row.sheet.columns().stream()
                            .filter(candidate -> candidate.column().equals(cell.column()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Ячейка вне структуры листа"));
            cells.add(
                    obj(
                            "column",
                            clean(cell.column()),
                            "attribute",
                            clean(column.attribute()),
                            "block",
                            clean(column.metadata().get("block")),
                            "subBlock",
                            clean(column.metadata().get("subBlock")),
                            "fieldName",
                            clean(column.metadata().get("fieldName")),
                            "value",
                            cell.value() == null ? null : text(cell.value())));
        }
        cells.sort(Comparator.comparing(cell -> text(cell.get("column"))));
        return legacyRowSha(cells);
    }

    private Map<String, Object> orderAnimal(Values row, int start) {
        String clipColor = row.s(start + 7);
        var animal =
                obj(
                        "clip",
                        yesState(row.v(start + 6)),
                        "clipsColor",
                        clipColor,
                        "size",
                        dictionary(row.v(start + 3), "SIZE"),
                        "type",
                        dictionary(row.v(start + 1), "TYPE", "dog"),
                        "address",
                        address(row.v(start + 9)),
                        "benchmark",
                        row.s(start + 10),
                        "coloration",
                        row.s(start + 2),
                        "additionalInfo",
                        row.s(start + 8),
                        "unmotivatedAggression",
                        aggression(row.v(start + 4)),
                        "unmotivatedAggressionDescription",
                        row.s(start + 5),
                        "catchOrderStatus",
                        dictionary(row.v(start + 12), "STATUS_ORDER"),
                        "notificationNegative",
                        row.s(start + 13));
        animal.put(
                "number",
                obj(
                        "animal",
                        obj(
                                "clip",
                                animal.get("clip"),
                                "clipsColor",
                                clipColor,
                                "size",
                                animal.get("size"),
                                "type",
                                animal.get("type"),
                                "number",
                                row.s(start),
                                "address",
                                animal.get("address"),
                                "benchmark",
                                animal.get("benchmark"),
                                "coloration",
                                animal.get("coloration"),
                                "additionalInfo",
                                animal.get("additionalInfo"),
                                "unmotivatedAggression",
                                animal.get("unmotivatedAggression"),
                                "unmotivatedAggressionDescription",
                                animal.get("unmotivatedAggressionDescription"))));
        var photo = file(row, start + 11, "animal.photo[0]");
        if (photo != null) animal.put("photo", List.of(photo));
        return nonempty(animal);
    }

    private void order(Values row) {
        var animals = new ArrayList<Map<String, Object>>();
        for (int start = 12; start <= 138; start += 14)
            if (row.has(start, start + 13)) animals.add(orderAnimal(row, start));
        var payload = base(true);
        payload.putAll(
                obj(
                        "animal",
                        animals,
                        "prioritet",
                        Map.of("высокий", "hight", "средний", "middle", "низкий", "low")
                                .get(key(row.v(10))),
                        "dayToOtlov",
                        row.s(11).isEmpty() ? null : new BigDecimal(row.s(11)).intValue(),
                        "entityType",
                        null,
                        "requestNumber",
                        row.s(9)));
        add(row, "order", "animalCatchOrderRegistryCollection", payload);
    }

    private void stray(Values row) {
        var status = dictionary(row.v(21), "STATUS_STRAY");
        var animal =
                nonempty(
                        obj(
                                "clip",
                                yesState(row.v(15)),
                                "clipsColor",
                                row.s(16),
                                "size",
                                dictionary(row.v(12), "SIZE"),
                                "type",
                                dictionary(row.v(10), "TYPE"),
                                "number",
                                row.s(9),
                                "address",
                                address(row.v(18)),
                                "benchmark",
                                row.s(19),
                                "coloration",
                                row.s(11),
                                "additionalInfo",
                                row.s(17),
                                "unmotivatedAggression",
                                aggression(row.v(13)),
                                "unmotivatedAggressionDescription",
                                row.s(14),
                                "status",
                                status,
                                "caught",
                                status != null && text(status.get("code")).equals("captured")));
        var photo = file(row, 20, "animal.photo[0]");
        if (photo != null) animal.put("photo", List.of(photo));
        var note = file(row, 22, "animal.explanatoryNoteFile[0]");
        if (note != null) animal.put("explanatoryNoteFile", List.of(note));
        var payload = base(true);
        payload.putAll(obj("animal", animal, "entityType", null));
        if (row.has(23, 35))
            payload.put(
                    "catchInfo",
                    nonempty(
                            obj(
                                    "requestNumber",
                                    row.s(23),
                                    "catchDate",
                                    first(
                                            isoDateTime(row.v(28), row.v(29)),
                                            isoDateTime(row.v(26), row.v(27))),
                                    "catchAddress",
                                    address(row.v(30)),
                                    "municipalContractNumber",
                                    row.s(24),
                                    "municipalContractDate",
                                    isoDate(row.v(25)),
                                    "catchActNumber",
                                    row.s(33),
                                    "catchActDate",
                                    isoDate(row.v(34)))));
        add(row, "animal", "animalsRecordsCollectionTwo", payload);
        if (!row.has(23, 35)) return;
        var actAnimal =
                obj(
                        "number",
                        obj("animal", animal),
                        "animalRegistryLink",
                        new ReferenceTemplate(
                                "/myPet/myPetReestrs/animalsRecords/edit/",
                                new EntityReference(
                                        entityKey(context, "sheet3", row.row.excelRow(), "animal"),
                                        "_id"),
                                ""));
        actAnimal.putAll(animal);
        var act = base(false);
        act.putAll(
                obj(
                        "animal",
                        List.of(actAnimal),
                        "catchRequestInfo",
                        obj("requestNumber", row.s(23), "catchRequestRegistryLink", ""),
                        "catchProcessInfo",
                        obj(
                                "catchStartDate",
                                isoDate(row.v(26)),
                                "catchStartTime",
                                row.s(27),
                                "catchEndDate",
                                isoDate(row.v(28)),
                                "catchEndTime",
                                row.s(29),
                                "catchAddress",
                                address(row.v(30)),
                                "hunterFullName",
                                row.s(32),
                                "municipalContractNumber",
                                row.s(24),
                                "municipalContractDate",
                                isoDate(row.v(25)),
                                "videoBlock",
                                fileList(row, 31, "catchProcessInfo.videoBlock[0]"),
                                "explanatoryNoteFile",
                                List.of()),
                        "actData",
                        obj(
                                "actDate",
                                first(isoDate(row.v(34)), isoDate(row.v(28))),
                                "org",
                                first(row.s(2), row.s(5)),
                                "actNumber",
                                row.s(33),
                                "catchActFile",
                                fileList(row, 35, "actData.catchActFile[0]"))));
        add(row, "catch-act", "animalCatchActRegistryCollection", act);
    }

    private Map<String, Object> cardAnimal(Values row) {
        var animal =
                obj(
                        "type",
                        dictionary(row.v(10), "TYPE"),
                        "sex",
                        dictionary(row.v(11), "SEX"),
                        "breed",
                        row.s(12),
                        "coloration",
                        row.s(13),
                        "size",
                        dictionary(row.v(14), "SIZE"),
                        "nameAtTimeOfAct",
                        row.s(15),
                        "fur",
                        dictionary(row.v(16), "FUR"),
                        "ears",
                        dictionary(row.v(17), "EARS"),
                        "tail",
                        dictionary(row.v(18), "TAIL"),
                        "specialMarks",
                        row.s(19),
                        "approximateAge",
                        row.s(20),
                        "weight",
                        row.s(21).replace(',', '.'),
                        "temperature",
                        row.s(22).replace(',', '.'),
                        "injuriesInflictedByAnimal",
                        row.s(23),
                        "crateNumber",
                        row.s(26));
        if (!row.s(28).isEmpty()) animal.put("status", dictionary(row.v(28), "STATUS_CARD"));
        if (!row.s(24).isEmpty() || !row.s(25).isEmpty())
            animal.put("tagBlock", obj("tagNumber", row.s(24), "applicationMethod", row.s(25)));
        var keeping =
                nonempty(
                        obj(
                                "releaseDateFromShelter",
                                isoDate(row.v(29)),
                                "releaseDateFromTemporaryFacility",
                                isoDate(row.v(30)),
                                "quarantineEndDate",
                                isoDate(row.v(31))));
        if (!keeping.isEmpty()) animal.put("animalKeepingPeriod", keeping);
        var photo = file(row, 27, "animal.photo[0]");
        if (photo != null) animal.put("photo", List.of(photo));
        return nonempty(animal);
    }

    private static Map<String, Object> snapshot(Map<String, Object> animal) {
        var result = new LinkedHashMap<String, Object>();
        for (String name :
                List.of(
                        "type",
                        "sex",
                        "breed",
                        "coloration",
                        "size",
                        "nameAtTimeOfAct",
                        "tagBlock"))
            if (animal.containsKey(name)) result.put(name, animal.get(name));
        return nonempty(result);
    }

    private static Map<String, Object> event(
            String code, String name, String property, Object data) {
        return obj("eventType", obj("code", code, "name", name), property, data);
    }

    private static Map<String, Object> overlay(
            Map<String, Object> original, Map<String, Object> patch) {
        var result = new LinkedHashMap<>(original);
        result.putAll(patch);
        return nonempty(result);
    }

    private List<Map<String, Object>> events(Values row, Map<String, Object> animal) {
        var events = new ArrayList<Map<String, Object>>();
        var snapshot = snapshot(animal);
        var deworming = new ArrayList<Map<String, Object>>();
        for (int start : new int[] {32, 40, 47, 54, 61, 68, 75, 82, 89, 96}) {
            int shift = start == 32 ? 1 : 0;
            if (!row.has(start, start + (shift == 1 ? 7 : 6))) continue;
            deworming.add(
                    overlay(
                            snapshot,
                            nonempty(
                                    obj(
                                            "type",
                                            shift == 1 ? row.s(start) : null,
                                            "drugName",
                                            row.s(start + shift),
                                            "dosage",
                                            row.s(start + shift + 1),
                                            "executionDate",
                                            isoDate(row.v(start + shift + 2)),
                                            "employeeFullName",
                                            row.s(start + shift + 3),
                                            "employeePosition",
                                            row.s(start + shift + 4),
                                            "dewormingActNumber",
                                            row.s(start + shift + 5),
                                            "dewormingAct",
                                            file(
                                                    row,
                                                    start + shift + 6,
                                                    "events["
                                                            + events.size()
                                                            + "].deworming["
                                                            + deworming.size()
                                                            + "].dewormingAct")))));
        }
        if (!deworming.isEmpty())
            events.add(event("deworming", "Дегельминтизация", "deworming", deworming));
        var disinsection = new ArrayList<Map<String, Object>>();
        for (int start : new int[] {104, 110, 116, 122, 128, 134, 140, 146, 152, 158})
            if (row.has(start, start + 5))
                disinsection.add(
                        overlay(
                                snapshot,
                                obj(
                                        "drugName",
                                        row.s(start),
                                        "executionDate",
                                        isoDate(row.v(start + 1)),
                                        "employeeFullName",
                                        row.s(start + 2),
                                        "employeePosition",
                                        row.s(start + 3),
                                        "disinsectionActNumber",
                                        row.s(start + 4),
                                        "disinsectionAct",
                                        file(
                                                row,
                                                start + 5,
                                                "events["
                                                        + events.size()
                                                        + "].disinsection["
                                                        + disinsection.size()
                                                        + "].disinsectionAct"))));
        if (!disinsection.isEmpty())
            events.add(event("disinsection", "Дезинсекция", "disinsection", disinsection));
        var vaccination = new ArrayList<Map<String, Object>>();
        for (int start : new int[] {165, 173, 181, 189, 197, 205, 213, 221, 229, 237})
            if (row.has(start, start + 7))
                vaccination.add(
                        overlay(
                                snapshot,
                                obj(
                                        "drugName",
                                        row.s(start),
                                        "executionDate",
                                        isoDate(row.v(start + 1)),
                                        "drugSeriesNumber",
                                        row.s(start + 2),
                                        "dosage",
                                        row.s(start + 3),
                                        "employeeFullName",
                                        row.s(start + 4),
                                        "employeePosition",
                                        row.s(start + 5),
                                        "vaccinationActNumber",
                                        row.s(start + 6),
                                        "vaccinationAct",
                                        file(
                                                row,
                                                start + 7,
                                                "events["
                                                        + events.size()
                                                        + "].vaccination["
                                                        + vaccination.size()
                                                        + "].vaccinationAct"))));
        if (!vaccination.isEmpty())
            events.add(event("vaccination", "Вакцинация", "vaccination", vaccination));
        if (row.has(246, 252))
            events.add(
                    event(
                            "sterilization",
                            "Стерилизация (кастрация)",
                            "sterilization",
                            overlay(
                                    snapshot,
                                    obj(
                                            "sterilizationDrugName",
                                            row.s(246),
                                            "executionDate",
                                            isoDate(row.v(247)),
                                            "dosage",
                                            row.s(248),
                                            "employeeFullName",
                                            row.s(249),
                                            "employeePosition",
                                            row.s(250),
                                            "sterilizationActNumber",
                                            row.s(251),
                                            "sterilizationAct",
                                            file(
                                                    row,
                                                    252,
                                                    "events["
                                                            + events.size()
                                                            + "].sterilization.sterilizationAct")))));
        if (row.has(254, 266)) {
            var members = new ArrayList<String>();
            for (int attr = 258; attr <= 262; attr++)
                if (!row.s(attr).isEmpty()) members.add(row.s(attr));
            events.add(
                    event(
                            "inspection",
                            "Освидетельствование",
                            "inspection",
                            obj(
                                    "reactionToFoodWithStranger",
                                    row.s(254),
                                    "reactionToFoodOfferedByStranger",
                                    row.s(255),
                                    "reactionToLoudSounds",
                                    row.s(256),
                                    "commissionDecision",
                                    row.s(257),
                                    "commissionMemberFullName",
                                    String.join(", ", members),
                                    "commissionMemberFullNameBlock",
                                    members.stream()
                                            .map(member -> obj("commissionMemberFullName", member))
                                            .toList(),
                                    "actAuthor",
                                    row.s(263),
                                    "inspectionDate",
                                    isoDate(row.v(264)),
                                    "inspectionActNumber",
                                    row.s(265),
                                    "inspectionAct",
                                    file(
                                            row,
                                            266,
                                            "events["
                                                    + events.size()
                                                    + "].inspection.inspectionAct"))));
        }
        if (row.has(268, 277))
            events.add(
                    event(
                            "euthanasia",
                            "Эвтаназия",
                            "euthanasia",
                            obj(
                                    "euthanasiaReason",
                                    row.s(268),
                                    "euthanasiaDate",
                                    isoDate(row.v(269)),
                                    "euthanasiaTime",
                                    row.s(270),
                                    "euthanasiaMethod",
                                    row.s(271),
                                    "drugName",
                                    row.s(272),
                                    "dosage",
                                    row.s(273),
                                    "employeeFullName",
                                    row.s(274),
                                    "employeePosition",
                                    row.s(275),
                                    "euthanasiaActNumber",
                                    row.s(276),
                                    "euthanasiaAct",
                                    file(
                                            row,
                                            277,
                                            "events["
                                                    + events.size()
                                                    + "].euthanasia.euthanasiaAct"))));
        if (row.has(279, 285))
            events.add(
                    event(
                            "disposal",
                            "Утилизация",
                            "disposal",
                            obj(
                                    "disposalDate",
                                    isoDate(row.v(279)),
                                    "disposalReason",
                                    row.s(280),
                                    "disposalMethod",
                                    row.s(281),
                                    "employeeFullName",
                                    row.s(282),
                                    "employeePosition",
                                    row.s(283),
                                    "disposalActNumber",
                                    row.s(284),
                                    "disposalAct",
                                    file(
                                            row,
                                            285,
                                            "events["
                                                    + events.size()
                                                    + "].disposal.disposalAct"))));
        if (row.has(287, 292))
            events.add(
                    event(
                            "identificationTagApplying",
                            "Нанесение идентификационной метки",
                            "identificationTagApplying",
                            obj(
                                    "tagNumber",
                                    row.s(287),
                                    "applicationMethod",
                                    row.s(288),
                                    "applicationLocation",
                                    row.s(289),
                                    "executionDate",
                                    isoDate(row.v(290)),
                                    "employeeFullName",
                                    row.s(291),
                                    "employeePosition",
                                    row.s(292))));
        var other = new ArrayList<Map<String, Object>>();
        for (int start : new int[] {294, 301, 308, 315, 322, 329, 336, 343, 350, 357})
            if (row.has(start, start + 6))
                other.add(
                        obj(
                                "eventName",
                                row.s(start),
                                "eventDescription",
                                row.s(start + 1),
                                "executionDate",
                                isoDate(row.v(start + 2)),
                                "employeeFullName",
                                row.s(start + 3),
                                "employeePosition",
                                row.s(start + 4),
                                "otherEventDocumentNumber",
                                row.s(start + 5),
                                "otherEventDocument",
                                file(
                                        row,
                                        start + 6,
                                        "events["
                                                + events.size()
                                                + "].other["
                                                + other.size()
                                                + "].otherEventDocument")));
        if (!other.isEmpty()) events.add(event("other", "Иное мероприятие", "other", other));
        return events;
    }

    private void card(Values row) {
        var animal = cardAnimal(row);
        var events = events(row, animal);
        var card = base(true);
        card.putAll(
                obj(
                        "animal",
                        animal,
                        "events",
                        events,
                        "number",
                        row.s(9),
                        "municipality",
                        clean(
                                first(
                                        map(map(draft.metadata().get("context"))
                                                        .get("municipality"))
                                                .get("name"),
                                        map(draft.metadata().get("title")).get("municipality"))),
                        "inn",
                        digits(row.v(4)),
                        "ogrn",
                        digits(row.v(3))));
        if (row.has(364, 377))
            card.put(
                    "releaseInfo",
                    obj(
                            "actName",
                            row.s(364),
                            "actNumber",
                            row.s(365),
                            "actDate",
                            isoDate(row.v(366)),
                            "shelterName",
                            row.s(367),
                            "shelterAddress",
                            row.s(368),
                            "shelterINN",
                            digits(row.v(369)),
                            "shelterOGRN",
                            digits(row.v(370)),
                            "pvsName",
                            row.s(371),
                            "pvsAddress",
                            row.s(372),
                            "pvsINN",
                            digits(row.v(373)),
                            "pvsOGRN",
                            digits(row.v(374)),
                            "catcherFIO",
                            row.s(375),
                            "releaseAddress",
                            row.s(376),
                            "releaseActNumber",
                            row.s(365),
                            "trunsferActNumber",
                            row.s(379),
                            "deathActNumber",
                            row.s(398),
                            "handoverActNumber",
                            row.s(424)));
        if (row.has(378, 396))
            card.put(
                    "transferToOwner",
                    obj(
                            "actName",
                            row.s(378),
                            "actNumber",
                            row.s(379),
                            "transferDate",
                            isoDate(row.v(380)),
                            "shelterName",
                            row.s(381),
                            "shelterAddress",
                            row.s(382),
                            "pvsName",
                            row.s(385),
                            "pvsAddress",
                            row.s(386),
                            "pvsINN",
                            digits(row.v(387)),
                            "pvsOGRN",
                            digits(row.v(388)),
                            "newOwnerFIO",
                            row.s(389),
                            "newOwnerAddress",
                            row.s(390),
                            "idSeries",
                            row.s(391),
                            "idNumber",
                            row.s(392),
                            "idDeptCode",
                            row.s(393),
                            "idIssueDate",
                            isoDate(row.v(394)),
                            "idIssuedBy",
                            row.s(395)));
        if (row.has(397, 409))
            card.put(
                    "deathInfo",
                    obj(
                            "actName",
                            row.s(397),
                            "actNumber",
                            row.s(398),
                            "actDate",
                            isoDate(row.v(399)),
                            "deathDate",
                            isoDate(row.v(400)),
                            "shelterName",
                            row.s(401),
                            "shelterAddress",
                            row.s(402),
                            "pvsName",
                            row.s(405),
                            "pvsAddress",
                            row.s(406),
                            "pvsINN",
                            digits(row.v(407)),
                            "pvsOGRN",
                            digits(row.v(408))));
        add(row, "card", "myPetAnimalCardReestr", card);
        var actAnimal = new LinkedHashMap<>(animal);
        var copyTypes =
                Set.of(
                        "deworming",
                        "disinsection",
                        "vaccination",
                        "sterilization",
                        "identificationTagApplying",
                        "other");
        actAnimal.put(
                "events",
                events.stream()
                        .filter(event -> copyTypes.contains(text(path(event, "eventType.code"))))
                        .toList());
        if (row.has(364, 377)) release(row, actAnimal);
        if (row.has(378, 396)) transferOwner(row, actAnimal);
        if (row.has(397, 409)) death(row, actAnimal);
        if (row.has(410, 422)) handoverCatcher(row, animal);
        if (row.has(423, 428)) handoverShelter(row, actAnimal);
    }

    private static Map<String, Object> shelter(
            Values row, int name, int address, int inn, int ogrn) {
        return obj(
                "shelterName",
                row.s(name),
                "shelterInn",
                digits(row.v(inn)),
                "shelterOgrn",
                digits(row.v(ogrn)),
                "shelterAddress",
                PetData.address(row.v(address)));
    }

    private void release(Values row, Map<String, Object> animal) {
        boolean shelter = row.has(367, 370), pvs = row.has(371, 374);
        var act = base(true);
        act.putAll(
                obj(
                        "number",
                        obj("number", row.s(9)),
                        "releaseDate",
                        isoDate(row.v(366)),
                        "releaseActNumber",
                        row.s(365),
                        "animalReceiverType",
                        shelter ? "shelter" : pvs ? "temporaryHoldingFacility" : null,
                        "animalReleaseActType",
                        obj(
                                "code",
                                "releaseActTitle",
                                "name",
                                first(
                                        row.s(364),
                                        "Акт выпуска животного без владельца в среду обитания")),
                        "animal",
                        animal));
        if (shelter) {
            var data = shelter(row, 367, 368, 369, 370);
            data.putAll(
                    obj(
                            "releaseExecutorFullName",
                            row.s(375),
                            "releaseActFile",
                            fileList(row, 377, "shelterData.releaseActFile[0]")));
            act.put("shelterData", data);
        }
        if (pvs) {
            var data = shelter(row, 371, 372, 373, 374);
            data.putAll(
                    obj(
                            "releaseExecutorFullName",
                            row.s(375),
                            "releaseActFile",
                            fileList(row, 377, "shelterData.releaseActFile[0]")));
            act.put("temporaryHoldingFacilityData", data);
        }
        add(row, "release-act", "animalReleaseActRegistryEntry", act);
    }

    private void transferOwner(Values row, Map<String, Object> animal) {
        var parts = Arrays.stream(row.s(389).split(" ")).filter(part -> !part.isEmpty()).toList();
        var owner =
                obj(
                        "newOwnerLastName",
                        parts.isEmpty() ? null : parts.getFirst(),
                        "newOwnerFirstName",
                        parts.size() > 1 ? parts.get(1) : null,
                        "newOwnerPatronymic",
                        parts.size() > 2 ? String.join(" ", parts.subList(2, parts.size())) : null,
                        "newOwnerAddress",
                        address(row.v(390)),
                        "passportData",
                        obj(
                                "IdentityDocumentSeries",
                                row.s(391),
                                "IdentityDocumentNumber",
                                row.s(392),
                                "IdentityDocumentSubdivisionCode",
                                row.s(393),
                                "IdentityDocumentIssueDate",
                                isoDate(row.v(394)),
                                "IdentityDocumentIssuingAuthority",
                                row.s(395)));
        boolean shelter = row.has(381, 384), pvs = row.has(385, 388);
        var actData =
                obj(
                        "transferActFile",
                        fileList(row, 396, "actData.transferActFile[0]"),
                        "newOwnerData",
                        owner);
        if (shelter) actData.put("shelterData", shelter(row, 381, 382, 383, 384));
        if (pvs) actData.put("temporaryHoldingFacilityData", shelter(row, 385, 386, 387, 388));
        var act = base(true);
        act.putAll(
                obj(
                        "number",
                        obj("number", row.s(9)),
                        "releaseDate",
                        isoDate(row.v(380)),
                        "releaseActNumber",
                        row.s(379),
                        "animalReceiverType",
                        shelter ? "shelter" : pvs ? "temporaryHoldingFacility" : null,
                        "animalReleaseActType",
                        obj(
                                "code",
                                "transferActTitle",
                                "name",
                                "Акт передачи животного без владельца прежнему или новому"
                                        + " владельцу"),
                        "animal",
                        animal,
                        "actData",
                        actData));
        add(row, "transfer-owner-act", "animalReleaseActRegistryEntry", act);
    }

    private void death(Values row, Map<String, Object> animal) {
        boolean shelter = row.has(401, 404), pvs = row.has(405, 408);
        var actData = obj("deathActFile", fileList(row, 409, "actData.deathActFile[0]"));
        if (shelter) actData.put("shelterData", shelter(row, 401, 402, 403, 404));
        if (pvs) actData.put("temporaryHoldingFacilityData", shelter(row, 405, 406, 407, 408));
        var act = base(true);
        act.putAll(
                obj(
                        "number",
                        obj("number", row.s(9)),
                        "releaseDate",
                        isoDate(row.v(399)),
                        "releaseActNumber",
                        row.s(398),
                        "deathDate",
                        isoDate(row.v(400)),
                        "animalReceiverType",
                        shelter ? "shelter" : pvs ? "temporaryHoldingFacility" : null,
                        "animalReleaseActType",
                        obj(
                                "code",
                                "deathActTitle",
                                "name",
                                first(row.s(397), "Акт падежа животного без владельца")),
                        "animal",
                        animal,
                        "actData",
                        actData));
        add(row, "death-act", "animalReleaseActRegistryEntry", act);
    }

    private void handoverCatcher(Values row, Map<String, Object> animal) {
        boolean shelter = row.has(416, 418), pvs = row.has(419, 421);
        var data =
                obj(
                        "dateActMun",
                        isoDate(row.v(413)),
                        "requestNumber",
                        row.s(412),
                        "requestCreationDate",
                        isoDate(row.v(413)),
                        "hunterFullName",
                        row.s(414),
                        "hunterPhoneNumber",
                        phone(row.v(415)),
                        "transferActNumber",
                        row.s(411));
        if (shelter)
            data.put(
                    "shelterData",
                    obj("shelterName", row.s(416), "shelterAddress", address(row.v(417))));
        if (pvs)
            data.put(
                    "temporaryHoldingFacilityData",
                    obj("shelterName", row.s(419), "shelterAddress", address(row.v(420))));
        var copy = new LinkedHashMap<String, Object>();
        for (String key : List.of("type", "sex", "breed", "coloration", "size", "tagBlock"))
            if (animal.get(key) != null) copy.put(key, animal.get(key));
        var act = base(true);
        act.putAll(
                obj(
                        "animal",
                        copy,
                        "dataAppeal",
                        data,
                        "transferActFile",
                        fileList(row, 422, "transferActFile[0]"),
                        "transferActType",
                        obj("code", "actWithHunter", "name", "Акт приема-передачи с ловцом"),
                        "animalReceiverType",
                        obj("code", pvs ? "temporaryHoldingFacility" : "shelter")));
        add(row, "handover-catcher", "animalTransferActRegistryCollection", act);
    }

    private void handoverShelter(Values row, Map<String, Object> animal) {
        var act = base(true);
        act.putAll(
                obj(
                        "number",
                        obj("number", row.s(9)),
                        "actData",
                        obj(
                                "thfName",
                                first(row.s(426), unit.get("name")),
                                "transferActDate",
                                isoDate(row.v(425)),
                                "transferActNumber",
                                row.s(424),
                                "trappingOrganization",
                                obj("name", row.s(427), "shortName", row.s(427))),
                        "animalShelter",
                        animal,
                        "transferActFile",
                        fileList(row, 428, "transferActFile[0]"),
                        "transferActType",
                        obj("code", "actWithShelter", "name", "Акт приема-передачи с приютом")));
        add(row, "handover-shelter", "animalTransferActRegistryCollection", act);
    }

    private static Object phone(Object value) {
        String raw = clean(value);
        if (raw.isEmpty()) return null;
        String digits = raw.replaceAll("\\D+", "");
        if (digits.length() == 10) digits = "7" + digits;
        if (digits.length() == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1);
        return digits.length() == 11 && digits.startsWith("7")
                ? "+7 ("
                        + digits.substring(1, 4)
                        + ") "
                        + digits.substring(4, 7)
                        + " "
                        + digits.substring(7, 9)
                        + " "
                        + digits.substring(9, 11)
                : raw;
    }
}

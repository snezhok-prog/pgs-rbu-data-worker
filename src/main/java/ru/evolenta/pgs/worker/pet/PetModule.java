package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import ru.evolenta.pgs.worker.trade.core.*;

import java.util.*;

public final class PetModule implements DomainModule {
    public static final List<String> COLLECTIONS =
            List.of(
                    "animalCatchOrderRegistryCollection",
                    "animalsRecordsCollectionTwo",
                    "animalCatchActRegistryCollection",
                    "myPetAnimalCardReestr",
                    "animalReleaseActRegistryEntry",
                    "animalTransferActRegistryCollection");
    private final PetConfiguration configuration = new PetConfiguration();

    @Override
    public String id() {
        return "pet";
    }

    @Override
    public Set<String> writableCollections() {
        return Set.copyOf(COLLECTIONS);
    }

    public PetValidationResult validateAndReport(
            WorkbookDraft draft,
            OperationContext context,
            RegistryAccess registry,
            java.time.Instant completedAt) {
        draft = prepare(draft);
        return PetValidationResult.build(
                draft, validate(draft, context, registry), context, completedAt);
    }

    @Override
    public ValidationReport validate(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        draft = prepare(draft);
        var issues =
                new ArrayList<>(
                        new PetValidator(configuration)
                                .validate(draft, context, registry)
                                .issues());
        try {
            if (estimate(draft).limitExceeded())
                issues.add(
                        new ValidationIssue(
                                "REGISTRY_RECORD_LIMIT",
                                "title",
                                0,
                                "volume",
                                "Рассчитанный объём миграции превышает 2500 реестровых записей"));
        } catch (IllegalArgumentException error) {
            issues.add(
                    new ValidationIssue(
                            "INVALID_IMPORT_PLAN", "title", 0, "volume", error.getMessage()));
        }
        return new ValidationReport(issues);
    }

    @Override
    public List<PlannedEntity> plan(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        draft = prepare(draft);
        var report = validate(draft, context, registry);
        if (!report.valid())
            throw new IllegalArgumentException(
                    "Файл содержит ошибки валидации: " + report.issues());
        return mapValidated(draft, context, registry);
    }

    @Override
    public List<PlannedPatch> patches(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        draft = prepare(draft);
        var report = validate(draft, context, registry);
        if (!report.valid())
            throw new IllegalArgumentException(
                    "Файл содержит ошибки валидации: " + report.issues());
        return mapPatches(draft, context);
    }

    private static WorkbookDraft prepare(WorkbookDraft draft) {
        return draft.metadata().containsKey("readerVersion")
                        && !draft.metadata().containsKey("petNormalizedVersion")
                ? new PetXlsxParser().normalize(draft)
                : draft;
    }

    List<PlannedEntity> mapValidated(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        if (estimate(draft).limitExceeded())
            throw new IllegalArgumentException("Превышен предел 2500 записей");
        return new PetPayloadMapper(configuration, draft, context, registry).buildPlan();
    }

    List<PlannedPatch> mapPatches(WorkbookDraft draft, OperationContext context) {
        var result = new ArrayList<PlannedPatch>();
        for (var sheet : draft.sheets())
            if (sheet.code().equals("sheet3"))
                for (var row : sheet.rows())
                    if (has(draft, sheet, row, 23, 35)) {
                        String key =
                                PetPayloadMapper.entityKey(
                                        context, "sheet3", row.excelRow(), "animal");
                        String act =
                                PetPayloadMapper.entityKey(
                                        context, "sheet3", row.excelRow(), "catch-act");
                        result.add(
                                new PlannedPatch(
                                        key + "/catch-record-link",
                                        "animalsRecordsCollectionTwo",
                                        new EntityReference(key, "_id"),
                                        obj(
                                                "catchInfo",
                                                obj(
                                                        "catchRecordLink",
                                                        new ReferenceTemplate(
                                                                "/myPet/myPetReestrs/animalCatchActRegistry/edit/",
                                                                new EntityReference(act, "_id"),
                                                                "")))));
                    }
        return List.copyOf(result);
    }

    public record Estimate(
            int totalRecords, Map<String, Integer> byCollection, boolean limitExceeded) {
        public Estimate {
            byCollection = Map.copyOf(byCollection);
        }
    }

    public Estimate estimate(WorkbookDraft draft) {
        var counts = new LinkedHashMap<String, Integer>();
        COLLECTIONS.forEach(collection -> counts.put(collection, 0));
        var seenSheets = new HashSet<String>();
        var identities = new HashSet<String>();
        int rows = 0;
        for (var sheet : draft.sheets()) {
            if (!Set.of("sheet2", "sheet3", "sheet4").contains(sheet.code())
                    || !seenSheets.add(sheet.code()))
                throw new IllegalArgumentException("Некорректный состав рабочих листов миграции");
            var rowNumbers = new HashSet<Integer>();
            for (var row : sheet.rows()) {
                if (row.excelRow() < 6 || !rowNumbers.add(row.excelRow()))
                    throw new IllegalArgumentException(
                            "Повторный или некорректный номер строки миграции");
                rows++;
                switch (sheet.code()) {
                    case "sheet2" ->
                            target(counts, identities, sheet, row, COLLECTIONS.get(0), "order");
                    case "sheet3" -> {
                        target(counts, identities, sheet, row, COLLECTIONS.get(1), "animal");
                        if (has(draft, sheet, row, 23, 35))
                            target(counts, identities, sheet, row, COLLECTIONS.get(2), "catch-act");
                    }
                    case "sheet4" -> {
                        target(counts, identities, sheet, row, COLLECTIONS.get(3), "card");
                        int[][] ranges = {
                            {364, 377}, {378, 396}, {397, 409}, {410, 422}, {423, 428}
                        };
                        String[] parts = {
                            "release-act",
                            "transfer-owner-act",
                            "death-act",
                            "handover-catcher",
                            "handover-shelter"
                        };
                        for (int index = 0; index < ranges.length; index++)
                            if (has(draft, sheet, row, ranges[index][0], ranges[index][1]))
                                target(
                                        counts,
                                        identities,
                                        sheet,
                                        row,
                                        COLLECTIONS.get(index < 3 ? 4 : 5),
                                        parts[index]);
                    }
                    default -> throw new IllegalArgumentException(sheet.code());
                }
            }
        }
        if (seenSheets.size() != 3)
            throw new IllegalArgumentException("Не найден полный состав рабочих листов миграции");
        if (rows > 5000) throw new IllegalArgumentException("Превышен предел 5000 строк");
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new Estimate(total, counts, total > 2500);
    }

    private static void target(
            Map<String, Integer> counts,
            Set<String> identities,
            WorkbookDraft.Sheet sheet,
            WorkbookDraft.Row row,
            String collection,
            String part) {
        String identity = collection + "/" + sheet.code() + ":" + row.excelRow() + ":" + part;
        if (!identities.add(identity))
            throw new IllegalArgumentException("Повторная идентичность записи " + identity);
        counts.put(collection, counts.get(collection) + 1);
    }

    private static boolean has(
            WorkbookDraft draft,
            WorkbookDraft.Sheet sheet,
            WorkbookDraft.Row row,
            int from,
            int to) {
        for (int attr = from; attr <= to; attr++)
            if (present(draft.value(sheet, row, attr))) return true;
        return false;
    }
}

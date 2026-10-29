package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import ru.evolenta.pgs.worker.trade.core.*;

import java.util.*;

final class PetValidator {
    private final PetConfiguration configuration;

    PetValidator(PetConfiguration configuration) {
        this.configuration = configuration;
    }

    ValidationReport validate(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        var issues = new LinkedHashMap<String, ValidationIssue>();
        var profile = configuration.profile();
        var sheets = new LinkedHashMap<String, WorkbookDraft.Sheet>();
        draft.sheets().forEach(sheet -> sheets.put(sheet.code(), sheet));
        if (!draft.dataType().equals("Мой питомец"))
            add(
                    issues,
                    "UNSUPPORTED_DATA_TYPE",
                    "title",
                    0,
                    "dataType",
                    "Неподдерживаемый вид данных");
        if (!Set.of("ROIV", "OMSU").contains(context.scope()))
            add(
                    issues,
                    "INVALID_SCOPE",
                    "title",
                    0,
                    "scope",
                    "Не определён доверенный контекст РОИВ/ОМСУ");
        if (!draft.fileSha256().matches("[0-9a-fA-F]{64}"))
            add(
                    issues,
                    "FILE_FINGERPRINT_MISSING",
                    "title",
                    0,
                    "file",
                    "Не рассчитана контрольная сумма исходного XLSX");
        var expectedNames =
                list(profile.get("expectedSheets")).stream().map(PetData::text).toList();
        var actualNames =
                list(draft.metadata().get("availableSheets")).stream().map(PetData::text).toList();
        if (actualNames.size() != expectedNames.size()
                || !new HashSet<>(actualNames).equals(new HashSet<>(expectedNames)))
            add(
                    issues,
                    "INVALID_SHEET_STRUCTURE",
                    "title",
                    0,
                    "sheets",
                    "Нарушена структура файла: названия или состав листов отличаются от шаблона");
        var invalidStructure = new HashSet<String>();
        for (var expected : map(profile.get("expected")).entrySet()) {
            String code = expected.getKey();
            var actual = sheets.get(code);
            var columns = maps(map(expected.getValue()).get("columns"));
            boolean mismatch = actual == null || actual.columns().size() != columns.size();
            if (!mismatch)
                for (int index = 0; index < columns.size(); index++) {
                    var want = columns.get(index);
                    var got = actual.columns().get(index);
                    if (!validationClean(got.attribute())
                                    .equals(validationClean(want.get("attribute")))
                            || !headerName(got.metadata().get("fieldName")).equals(headerName(want.get("name")))
                            || !validationClean(got.metadata().get("requiredMarker"))
                                    .equals(validationClean(want.get("requiredMarker")))) {
                        mismatch = true;
                        break;
                    }
                }
            if (mismatch) {
                invalidStructure.add(code);
                add(
                        issues,
                        "INVALID_COLUMN_STRUCTURE",
                        code,
                        4,
                        "structure",
                        "Структура файла изменена. Нельзя менять наименование столбцов и"
                                + " обязательность полей.");
            }
        }
        if (draft.sheets().stream().allMatch(sheet -> sheet.rows().isEmpty()))
            add(
                    issues,
                    "EMPTY_DATA",
                    "title",
                    0,
                    "data",
                    "На рабочих листах не найдено ни одной строки данных");
        var files = new PetFiles(context.attributes());
        var organizationCache = new HashMap<String, String>();
        for (var field : configuration.fields()) {
            var sheet = sheets.get(text(field.get("sheet")));
            if (sheet == null || invalidStructure.contains(sheet.code())) continue;
            var column = findColumn(sheet, field);
            if (column == null) {
                add(
                        issues,
                        "MISSING_COLUMN",
                        sheet.code(),
                        4,
                        text(field.get("column")),
                        "Отсутствует колонка «" + text(field.get("name")) + "»");
                continue;
            }
            for (var row : sheet.rows()) {
                var rawCell =
                        row.cells().stream()
                                .filter(cell -> cell.column().equals(column.column()))
                                .findFirst()
                                .orElse(null);
                Object value = rawCell == null ? null : rawCell.value();
                if (value instanceof Map<?, ?>
                        || value instanceof List<?>
                        || rawCell != null && rawCell.kind().equals("embeddedObject")) {
                    add(
                            issues,
                            "INVALID_CELL_TYPE",
                            sheet.code(),
                            row.excelRow(),
                            column.column(),
                            "Ячейка должна содержать исходное значение, а не встроенный объект");
                    continue;
                }
                String raw = text(value);
                if (value != null && !raw.isEmpty()) {
                    if (!raw.equals(raw.trim()))
                        add(
                                issues,
                                "LEADING_TRAILING_SPACES",
                                sheet.code(),
                                row.excelRow(),
                                column.column(),
                                "Добавлен лишний пробел в значении");
                    if (raw.contains("  "))
                        add(
                                issues,
                                "DOUBLE_SPACES",
                                sheet.code(),
                                row.excelRow(),
                                column.column(),
                                "Добавлен двойной пробел в значении");
                    if (raw.contains("\n") || raw.contains("\r"))
                        add(
                                issues,
                                "LINE_BREAK",
                                sheet.code(),
                                row.excelRow(),
                                column.column(),
                                "Разделение (табуляция) недопустима для указанного значения –"
                                        + " удалите отступ.");
                    if (raw.matches("(?s).*[\\t\\u00a0\\u200B-\\u200D\\u2060\\uFEFF].*"))
                        add(
                                issues,
                                "INVISIBLE_SYMBOL",
                                sheet.code(),
                                row.excelRow(),
                                column.column(),
                                "Разделение (табуляция) недопустима для указанного значения –"
                                        + " удалите отступ.");
                }
                for (var rule : maps(field.get("rules"))) {
                    var fieldWithMessage = new LinkedHashMap<>(field);
                    fieldWithMessage.put(
                            "userMessage",
                            first(
                                    rule.get("userMessage"),
                                    field.get("userMessage"),
                                    rule.get("ruleText")));
                    var environment =
                            new PetRuleEngine.Environment(
                                    configuration,
                                    fieldWithMessage,
                                    column,
                                    sheet,
                                    row,
                                    draft,
                                    files,
                                    registry,
                                    organizationCache);
                    var failure =
                            PetRuleEngine.check(
                                    text(rule.get("method")),
                                    value,
                                    map(rule.get("params")),
                                    environment);
                    failure.ifPresent(
                            problem ->
                                    add(
                                            issues,
                                            problem.code(),
                                            sheet.code(),
                                            problem.code().equals("VALIDATION_CONFIG_ERROR")
                                                    ? 4
                                                    : row.excelRow(),
                                            column.column(),
                                            problem.message()));
                }
            }
        }
        validateContext(draft, context, registry, issues);
        return new ValidationReport(List.copyOf(issues.values()));
    }

    private void validateContext(
            WorkbookDraft draft,
            OperationContext context,
            RegistryAccess registry,
            Map<String, ValidationIssue> issues) {
        var title = map(draft.metadata().get("title"));
        String titleRegion = validationClean(title.get("region")),
                titleMunicipality = validationClean(title.get("municipality"));
        if (titleRegion.isEmpty())
            add(
                    issues,
                    "TITLE_REQUIRED",
                    "title",
                    6,
                    "E",
                    "На титульном листе не заполнен субъект РФ");
        if (titleMunicipality.isEmpty())
            add(
                    issues,
                    "TITLE_REQUIRED",
                    "title",
                    17,
                    "E",
                    "На титульном листе не заполнено муниципальное образование");
        String region = display(context.attributes().get("region")),
                municipality = display(context.attributes().get("municipality"));
        if (region.isEmpty())
            add(
                    issues,
                    "REGION_REQUIRED",
                    "title",
                    0,
                    "region",
                    "Отсутствует доверенный выбранный регион");
        if (municipality.isEmpty())
            add(
                    issues,
                    "MUNICIPALITY_REQUIRED",
                    "title",
                    0,
                    "municipality",
                    "Отсутствует доверенное выбранное муниципальное образование");
        if (!region.isEmpty()
                && !titleRegion.isEmpty()
                && !validationKey(region).equals(validationKey(titleRegion)))
            add(
                    issues,
                    "CONTEXT_MISMATCH",
                    "title",
                    6,
                    "E",
                    "Субъект файла не совпадает с выбранным в деле");
        if (!municipality.isEmpty()
                && !titleMunicipality.isEmpty()
                && !validationKey(municipality).equals(validationKey(titleMunicipality)))
            add(
                    issues,
                    "CONTEXT_MISMATCH",
                    "title",
                    17,
                    "E",
                    "Муниципальное образование файла не совпадает с выбранным в деле");
        var workbookReferences = map(draft.metadata().get("referenceLists"));
        var regions = new ArrayList<Object>();
        Object rawEntries = workbookReferences.get("entries");
        for (var entry :
                rawEntries instanceof Map<?, ?> ? List.of(map(rawEntries)) : maps(rawEntries))
            if (Set.of(validationKey("Субъект РФ"), validationKey("Субъекты РФ"))
                    .contains(validationKey(entry.get("name"))))
                regions.addAll(list(entry.get("values")));
        if (regions.isEmpty())
            for (String name : List.of("Субъект РФ", "Субъекты РФ"))
                if (workbookReferences.get(name) instanceof List<?> values) regions.addAll(values);
        if (regions.isEmpty())
            add(
                    issues,
                    "REFERENCE_LIST_MISSING",
                    "title",
                    6,
                    "E",
                    "На листе 5 отсутствует перечень субъектов РФ");
        else if (!titleRegion.isEmpty() && !PetRuleEngine.exactReference(titleRegion, regions))
            add(issues, "INVALID_REFERENCE", "title", 6, "E", "Субъект отсутствует на листе 5");
        var selected = map(context.attributes().get("organization"));
        String organizationId = "";
        if (selected.isEmpty())
            add(
                    issues,
                    "ORGANIZATION_REQUIRED",
                    "title",
                    0,
                    "organization",
                    "В стартовой форме не выбрана организация");
        else {
            try {
                organizationId = resolveOrganization(selected, registry);
                if (organizationId.isEmpty())
                    add(
                            issues,
                            "ORGANIZATION_NOT_FOUND",
                            "title",
                            0,
                            "organization",
                            "Выбранная организация не разрешена в organizations");
                else if (!organizationId.equals(context.unitId()))
                    add(
                            issues,
                            "OWNER_MISMATCH",
                            "title",
                            0,
                            "organization",
                            "Владелец задания не совпадает с выбранной организацией");
                Object snapshot = map(draft.metadata().get("context")).get("organization");
                if (snapshot != null
                        && !resolveOrganization(map(snapshot), registry).equals(organizationId))
                    add(
                            issues,
                            "CONTEXT_MISMATCH",
                            "title",
                            0,
                            "organization",
                            "Организация изменилась после чтения файла");
            } catch (RuntimeException error) {
                add(
                        issues,
                        "REGISTRY_SEARCH_ERROR",
                        "title",
                        0,
                        "organization",
                        "Ошибка проверки организации: " + error.getMessage());
            }
        }
        if (validationClean(selected.get("organizationName")).isEmpty())
            add(
                    issues,
                    "ORGANIZATION_NAME_REQUIRED",
                    "title",
                    0,
                    "organizationName",
                    "У выбранной организации отсутствует organizationName");
        if (validationClean(selected.get("ogrn")).isEmpty())
            add(
                    issues,
                    "ORGANIZATION_OGRN_REQUIRED",
                    "title",
                    0,
                    "organizationOgrn",
                    "У выбранной организации отсутствует ogrn");
        try {
            verifySelection(
                    "rbuPetRegionPassports",
                    context.attributes().get("region"),
                    "regionName",
                    registry);
            verifySelection(
                    "rbuPetRegionMunicipalities",
                    context.attributes().get("municipality"),
                    "municipalityName",
                    registry);
        } catch (RuntimeException error) {
            add(issues, "INVALID_REFERENCE", "title", 0, "regionMunicipality", error.getMessage());
        }
        var duplicateSpecs = map(configuration.profile().get("duplicateSpecs"));
        var existingKeys = new HashMap<String, Map<String, Map<String, Object>>>();
        if (!organizationId.isEmpty())
            for (var item : duplicateSpecs.entrySet()) {
                var spec = map(item.getValue());
                var uploadedKeys = new HashSet<String>();
                for (var sheet : draft.sheets()) {
                    if (!sheet.code().equals(item.getKey())) continue;
                    for (var row : sheet.rows()) {
                        String businessKey =
                                validationKey(
                                        valueByName(draft, sheet, row, text(spec.get("field"))));
                        if (!businessKey.isEmpty()) uploadedKeys.add(businessKey);
                    }
                }
                if (uploadedKeys.isEmpty()) continue;
                var keys = new LinkedHashMap<String, Map<String, Object>>();
                try {
                    var matches =
                            registry.search(
                                    text(spec.get("collection")),
                                    Map.of("unit.id", organizationId),
                                    2501);
                    if (matches.size() >= 2501)
                        throw new IllegalArgumentException(
                                "Выдача реестра не завершена в заданном лимите");
                    for (var record : matches) {
                        if (!clean(path(record, "unit.id")).equals(organizationId))
                            throw new IllegalArgumentException(
                                    "Ответ реестра содержит запись другого владельца");
                        String businessKey =
                                validationKey(path(record, text(spec.get("recordPath"))));
                        if (uploadedKeys.contains(businessKey)
                                && keys.putIfAbsent(businessKey, record) != null)
                            throw new IllegalArgumentException(
                                    "Реестр содержит неоднозначный бизнес-ключ; выбор первой записи"
                                            + " запрещён");
                    }
                    existingKeys.put(item.getKey(), keys);
                } catch (RuntimeException error) {
                    add(
                            issues,
                            "REGISTRY_SEARCH_ERROR",
                            "title",
                            0,
                            "registry:" + item.getKey(),
                            "Не удалось проверить дубли в реестре: " + error.getMessage());
                }
            }
        for (var sheet : draft.sheets())
            for (var row : sheet.rows()) {
                compareAttribute(
                        issues,
                        draft,
                        sheet,
                        row,
                        2,
                        validationClean(selected.get("organizationName")),
                        "ORGANIZATION_NAME_MISMATCH");
                compareAttribute(
                        issues,
                        draft,
                        sheet,
                        row,
                        3,
                        validationClean(selected.get("ogrn")),
                        "ORGANIZATION_OGRN_MISMATCH");
                Object rowRegion = valueByName(draft, sheet, row, "Субъект РФ");
                if (validationPresent(rowRegion)
                        && !region.isEmpty()
                        && !validationKey(rowRegion).equals(validationKey(region)))
                    add(
                            issues,
                            "CONTEXT_MISMATCH",
                            sheet.code(),
                            row.excelRow(),
                            columnByName(sheet, "Субъект РФ"),
                            "Субъект строки не совпадает с выбранным в деле");
                Object rowMunicipality =
                        valueByName(
                                draft,
                                sheet,
                                row,
                                "Муниципальный район/округ, городской округ или внутригородская"
                                        + " территория");
                if (validationPresent(rowMunicipality)
                        && !municipality.isEmpty()
                        && !validationKey(rowMunicipality).equals(validationKey(municipality)))
                    add(
                            issues,
                            "CONTEXT_MISMATCH",
                            sheet.code(),
                            row.excelRow(),
                            columnByName(
                                    sheet,
                                    "Муниципальный район/округ, городской округ или внутригородская"
                                            + " территория"),
                            "МО строки не совпадает с выбранным в деле");
                var spec = map(duplicateSpecs.get(sheet.code()));
                if (spec.isEmpty()) continue;
                String value =
                        validationKey(valueByName(draft, sheet, row, text(spec.get("field"))));
                var record = existingKeys.getOrDefault(sheet.code(), Map.of()).get(value);
                if (record != null
                        && !clean(path(record, "migrationSource.appealId"))
                                .equals(context.appealId()))
                    add(
                            issues,
                            "EXISTING_RECORD",
                            sheet.code(),
                            row.excelRow(),
                            columnByName(sheet, text(spec.get("field"))),
                            "Запись уже есть в реестре, куда вы пытаетесь её загрузить. Если вы всё"
                                    + " равно хотите её добавить, сделайте её уникальной.");
            }
    }

    static String resolveOrganization(Map<String, Object> selected, RegistryAccess registry) {
        String direct =
                clean(
                        first(
                                selected.get("organizationId"),
                                path(selected, "organization._id"),
                                path(selected, "organization.id")));
        if (!direct.isEmpty()) return direct;
        String id = clean(first(selected.get("_id"), selected.get("id")));
        if (id.isEmpty()) return "";
        var module = registry.findById("rbuPetRegionOrganizations", id);
        if (module.isPresent()) {
            String resolved =
                    clean(
                            first(
                                    module.get().get("organizationId"),
                                    path(module.get(), "organization._id"),
                                    path(module.get(), "organization.id")));
            if (!resolved.isEmpty()) return resolved;
        }
        return registry.findById("organizations", id)
                .map(record -> clean(record.get("_id")))
                .orElse("");
    }

    private static void verifySelection(
            String collection, Object selected, String field, RegistryAccess registry) {
        if (selected == null) return;
        var selectedMap = selected instanceof Map<?, ?> ? map(selected) : Map.<String, Object>of();
        String id = clean(first(selectedMap.get("_id"), selectedMap.get("id")));
        if (!id.isEmpty() && registry.findById(collection, id).isPresent()) return;
        String name = display(selected);
        if (name.isEmpty()) return;
        var matches = registry.search(collection, Map.of(field, name), 2);
        if (matches.size() != 1
                || !validationKey(matches.getFirst().get(field)).equals(validationKey(name)))
            throw new IllegalArgumentException(
                    "Выбранное значение не найдено однозначно в " + collection);
    }

    static WorkbookDraft.Column findColumn(WorkbookDraft.Sheet sheet, Map<String, Object> field) {
        String name = validationKey(field.get("name"));
        var columns =
                sheet.columns().stream()
                        .filter(
                                column ->
                                        validationKey(column.metadata().get("fieldName"))
                                                .equals(name))
                        .toList();
        if (columns.isEmpty())
            columns =
                    sheet.columns().stream()
                            .filter(
                                    column ->
                                            validationKey(
                                                            PetRuleEngine.baseNameForField(
                                                                    column.metadata()
                                                                            .get("fieldName")))
                                                    .equals(
                                                            validationKey(
                                                                    PetRuleEngine.baseNameForField(
                                                                            field.get("name")))))
                            .toList();
        String block = validationKey(field.get("block"));
        if (!block.isEmpty()) {
            var scoped =
                    columns.stream()
                            .filter(
                                    column ->
                                            validationKey(column.metadata().get("block"))
                                                            .equals(block)
                                                    || validationKey(
                                                                    column.metadata()
                                                                            .get("subBlock"))
                                                            .equals(block))
                            .toList();
            if (!scoped.isEmpty()) columns = scoped;
        }
        for (var column : columns)
            if (column.attribute().equals(text(field.get("attribute")))) return column;
        String ordinal = PetRuleEngine.ordinal(field.get("name"));
        if (!ordinal.isEmpty())
            for (var column : columns)
                if (text(column.metadata().get("fieldName")).startsWith(ordinal)) return column;
        return columns.isEmpty() ? null : columns.getFirst();
    }

    private static Object valueByName(
            WorkbookDraft draft, WorkbookDraft.Sheet sheet, WorkbookDraft.Row row, String name) {
        String column = columnByName(sheet, name);
        return row.cells().stream()
                .filter(cell -> cell.column().equals(column))
                .findFirst()
                .map(WorkbookDraft.Cell::value)
                .orElse(null);
    }

    private static String columnByName(WorkbookDraft.Sheet sheet, String name) {
        return sheet.columns().stream()
                .filter(
                        column ->
                                validationKey(column.metadata().get("fieldName"))
                                        .equals(validationKey(name)))
                .map(WorkbookDraft.Column::column)
                .findFirst()
                .orElse("field:" + name);
    }

    private static void compareAttribute(
            Map<String, ValidationIssue> issues,
            WorkbookDraft draft,
            WorkbookDraft.Sheet sheet,
            WorkbookDraft.Row row,
            int attribute,
            String expected,
            String code) {
        if (expected.isEmpty()) return;
        if (!validationClean(draft.value(sheet, row, attribute)).equals(expected)) {
            String column =
                    sheet.columns().stream()
                            .filter(item -> item.attribute().equals(String.valueOf(attribute)))
                            .map(WorkbookDraft.Column::column)
                            .findFirst()
                            .orElse("attribute:" + attribute);
            add(
                    issues,
                    code,
                    sheet.code(),
                    row.excelRow(),
                    column,
                    "Значение файла не совпадает с выбранной организацией");
        }
    }

    private static String headerName(Object value) {
        String name = text(value);
        return clean(name).equals("Наименование приюта") ? name.stripTrailing() : name;
    }

    private static void add(
            Map<String, ValidationIssue> issues,
            String code,
            String sheet,
            int row,
            String column,
            String message) {
        issues.putIfAbsent(
                sheet + ":" + row + ":" + column,
                new ValidationIssue(code, sheet, row, column, message));
    }
}

package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.evolenta.pgs.worker.trade.core.*;

import java.io.ByteArrayInputStream;
import java.util.*;

class PetDuplicateValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"ROIV", "OMSU"})
    void unrelatedExistingBusinessKeyDuplicatesDoNotBlockUniqueUploadedOrder(String scope)
            throws Exception {
        var context = PetXlsxIntegrationTest.context(scope);
        var report =
                new PetModule()
                        .validate(
                                draft(context),
                                context,
                                registry(
                                        List.of(
                                                existing("old-one", "legacy-appeal", " old-order "),
                                                existing("old-two", "other-appeal", "OLD-ORDER"))));
        assertTrue(report.valid(), report.issues().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROIV", "OMSU"})
    void unusedSheetRegistriesAreNotSearched(String scope) throws Exception {
        var context = PetXlsxIntegrationTest.context(scope);
        var searchedCollections = new ArrayList<String>();
        var report =
                new PetModule()
                        .validate(
                                draft(context), context, registry(List.of(), searchedCollections));
        assertTrue(report.valid(), report.issues().toString());
        assertEquals(List.of("animalCatchOrderRegistryCollection"), searchedCollections);
    }

    @Test
    void sheetWithoutBusinessKeysDoesNotSearchItsRegistryButStillFailsRequiredValidation()
            throws Exception {
        var context = PetXlsxIntegrationTest.context("ROIV");
        var draft = draft(context);
        var sheets = new ArrayList<WorkbookDraft.Sheet>();
        for (var sheet : draft.sheets()) {
            if (!sheet.code().equals("sheet2")) {
                sheets.add(sheet);
                continue;
            }
            var rows = new ArrayList<WorkbookDraft.Row>();
            for (var row : sheet.rows())
                rows.add(
                        new WorkbookDraft.Row(
                                row.excelRow(),
                                row.cells().stream()
                                        .filter(cell -> !cell.column().equals("J"))
                                        .toList()));
            sheets.add(new WorkbookDraft.Sheet(sheet.code(), sheet.columns(), rows));
        }
        var missingNumber =
                new WorkbookDraft(draft.dataType(), draft.fileSha256(), sheets, draft.metadata());
        var searchedCollections = new ArrayList<String>();
        var report =
                new PetModule()
                        .validate(missingNumber, context, registry(List.of(), searchedCollections));
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream()
                        .anyMatch(
                                issue ->
                                        issue.sheet().equals("sheet2")
                                                && issue.row() == 6
                                                && issue.column().equals("J")),
                report.issues().toString());
        assertEquals(List.of(), searchedCollections);
    }

    @Test
    void normalizedDuplicatesOfTheUploadedKeyStillFailClosed() throws Exception {
        var context = PetXlsxIntegrationTest.context("ROIV");
        var report =
                new PetModule()
                        .validate(
                                draft(context),
                                context,
                                registry(
                                        List.of(
                                                existing("one", "appeal", " order-1 "),
                                                existing("two", "foreign-appeal", "ORDER-1"))));
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream()
                        .anyMatch(
                                issue ->
                                        issue.code().equals("REGISTRY_SEARCH_ERROR")
                                                && issue.message().contains("неоднозначный")),
                report.issues().toString());
    }

    @Test
    void wrongOwnerInAnUnrelatedRecordStillBlocksValidation() throws Exception {
        var context = PetXlsxIntegrationTest.context("ROIV");
        var record = new LinkedHashMap<>(existing("old-one", "legacy-appeal", "OLD-ORDER"));
        record.put("unit", Map.of("id", "foreign-owner"));
        var report = new PetModule().validate(draft(context), context, registry(List.of(record)));
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream()
                        .anyMatch(
                                issue ->
                                        issue.code().equals("REGISTRY_SEARCH_ERROR")
                                                && issue.message().contains("другого владельца")),
                report.issues().toString());
    }

    @Test
    void registryAtTheReadLimitStillBlocksEvenWhenKeysAreUnrelated() throws Exception {
        var context = PetXlsxIntegrationTest.context("ROIV");
        var report =
                new PetModule()
                        .validate(
                                draft(context),
                                context,
                                registry(
                                        Collections.nCopies(
                                                2501,
                                                existing(
                                                        "old-one", "legacy-appeal", "OLD-ORDER"))));
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream()
                        .anyMatch(
                                issue ->
                                        issue.code().equals("REGISTRY_SEARCH_ERROR")
                                                && issue.message().contains("не завершена")),
                report.issues().toString());
    }

    @Test
    void ambiguousExistingBusinessKeyCannotBeAcceptedByChoosingTheFirstRecord() throws Exception {
        var context = PetXlsxIntegrationTest.context("ROIV");
        var draft =
                new PetXlsxParser()
                        .read(
                                new ByteArrayInputStream(PetXlsxIntegrationTest.workbook(false)),
                                "template.xlsx",
                                "storage-id",
                                context);
        var report =
                new PetModule()
                        .validate(
                                draft,
                                context,
                                registry(
                                        List.of(
                                                existing("one", "appeal"),
                                                existing("two", "foreign-appeal"))));
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream()
                        .anyMatch(
                                issue ->
                                        Set.of("EXISTING_RECORD", "REGISTRY_SEARCH_ERROR")
                                                .contains(issue.code())));
    }

    @Test
    void ownExistingRecordAllowsReplayButForeignExistingRecordBlocks() throws Exception {
        var context = PetXlsxIntegrationTest.context("OMSU");
        var draft =
                new PetXlsxParser()
                        .read(
                                new ByteArrayInputStream(PetXlsxIntegrationTest.workbook(false)),
                                "template.xlsx",
                                "storage-id",
                                context);
        assertTrue(
                new PetModule()
                        .validate(draft, context, registry(List.of(existing("one", "appeal"))))
                        .valid());
        var report =
                new PetModule()
                        .validate(
                                draft,
                                context,
                                registry(List.of(existing("one", "foreign-appeal"))));
        assertFalse(report.valid());
        assertTrue(
                report.issues().stream().anyMatch(issue -> issue.code().equals("EXISTING_RECORD")));
    }

    private static Map<String, Object> existing(String id, String appealId) {
        return existing(id, appealId, "ORDER-1");
    }

    private static Map<String, Object> existing(String id, String appealId, String requestNumber) {
        return Map.of(
                "_id",
                id,
                "unit",
                Map.of("id", "owner"),
                "requestNumber",
                requestNumber,
                "migrationSource",
                Map.of("appealId", appealId));
    }

    private static RegistryAccess registry(List<Map<String, Object>> records) {
        return registry(records, new ArrayList<>());
    }

    private static WorkbookDraft draft(OperationContext context) throws Exception {
        return new PetXlsxParser()
                .read(
                        new ByteArrayInputStream(PetXlsxIntegrationTest.workbook(false)),
                        "template.xlsx",
                        "storage-id",
                        context);
    }

    private static RegistryAccess registry(
            List<Map<String, Object>> records, List<String> searchedCollections) {
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) {
                return switch (collection) {
                    case "organizations" ->
                            Optional.of(Map.of("_id", "owner", "name", "Тестовая организация"));
                    case "rbuPetRegionPassports" ->
                            Optional.of(Map.of("_id", "region", "regionName", "Тестовый регион"));
                    case "rbuPetRegionMunicipalities" ->
                            Optional.of(
                                    Map.of(
                                            "_id",
                                            "municipality",
                                            "municipalityName",
                                            "Тестовое МО"));
                    default -> Optional.empty();
                };
            }

            public List<Map<String, Object>> search(
                    String collection, Map<String, Object> filter, int limit) {
                if (Set.of(
                                "animalCatchOrderRegistryCollection",
                                "animalsRecordsCollectionTwo",
                                "myPetAnimalCardReestr")
                        .contains(collection)) {
                    searchedCollections.add(collection);
                    assertEquals(Map.of("unit.id", "owner"), filter);
                    assertEquals(2501, limit);
                }
                return switch (collection) {
                    case "organizations" ->
                            List.of(Map.of("_id", "owner", "guid", filter.get("guid")));
                    case "animalCatchOrderRegistryCollection" -> records;
                    default -> List.of();
                };
            }
        };
    }
}

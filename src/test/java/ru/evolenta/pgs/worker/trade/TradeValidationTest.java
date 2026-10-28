package ru.evolenta.pgs.worker.trade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft;
import static org.junit.jupiter.api.Assertions.*;

class TradeValidationTest {
    static WorkbookDraft fixture() {
        return WorkbookDraft.from(TradeData.JSON.convertValue(TradeData.resource("nto-draft.json"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }
    static OperationContext context(String scope) {
        var draft = fixture();
        var title = TradeData.map(draft.metadata().get("title"));
        return new OperationContext("migration", "appeal", "organization", "actor", scope, Map.of(
                "organization", Map.of("_id", "card", "organizationId", "organization"),
                "region", Map.of("_id", "passport", "name", title.get("region")),
                "municipality", Map.of("_id", "municipality", "name", title.get("municipality")),
                "operationTime", "2026-10-23T16:00:00Z",
                "documentFiles", TradeData.JSON.convertValue(TradeData.resource("nto-files.json"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {})));
    }
    static RegistryAccess registry(boolean permission) {
        var title = TradeData.map(fixture().metadata().get("title"));
        return new RegistryAccess() {
            public Optional<Map<String, Object>> findById(String collection, String id) {
                return Optional.ofNullable(switch (collection) {
                    case "organizations" -> Map.of("_id", "organization", "name", "Тестовая организация", "inn", "1111111111", "ogrn", "1111111111111", "kpp", "111111111");
                    case "rbuRegionOrganizations" -> Map.of("_id", "card", "organizationId", "organization", "municipalityRecordId", "municipality", "isMigration", permission, "guid", "card-guid");
                    case "rbuRegionPassports" -> Map.of("_id", "passport", "regionName", title.get("region"), "regionCode", "61");
                    case "rbuRegionMunicipalities" -> Map.of("_id", "municipality", "municipalityName", title.get("municipality"), "passportId", "passport");
                    default -> null;
                });
            }
            public List<Map<String, Object>> search(String collection, Map<String, Object> filter, int limit) { return List.of(); }
        };
    }
    @Test void executesPublishedRulesAgainstAnonymizedSourceFixture() {
        var report = new TradeValidation().validate(fixture(), context("ROIV"), registry(true));
        assertTrue(report.valid(), report.issues().toString());
    }
    @Test void rejectsChangedTemplateMetadataAndRevokedOmsuPermission() {
        var draft = fixture();
        var sheet = draft.sheets().getFirst();
        var columns = new java.util.ArrayList<>(sheet.columns());
        var first = columns.getFirst();
        var metadata = new LinkedHashMap<>(first.metadata()); metadata.put("requiredMarker", "Изменено");
        columns.set(0, new WorkbookDraft.Column(first.column(), first.attribute(), metadata));
        var sheets = new java.util.ArrayList<>(draft.sheets()); sheets.set(0, new WorkbookDraft.Sheet(sheet.code(), columns, sheet.rows()));
        var changed = new WorkbookDraft(draft.dataType(), draft.fileSha256(), sheets, draft.metadata());
        assertTrue(new TradeValidation().validate(changed, context("ROIV"), registry(true)).issues().stream().anyMatch(issue -> issue.code().equals("STRUCTURE_CHANGED")));
        assertTrue(new TradeValidation().validate(draft, context("OMSU"), registry(false)).issues().stream().anyMatch(issue -> issue.code().equals("CONTEXT_INVALID")));
    }
    @Test void propagatesEmbeddedPhotoMarkersAndUsesTrimmedTitleReferenceValues() {
        var draft = fixture(); var metadata = new LinkedHashMap<>(draft.metadata());
        metadata.put("referenceLists", Map.of("entries", List.of(Map.of("name", "Субъекты РФ", "values", List.of(" " + TradeData.at(metadata, "title.region") + " ")))));
        assertTrue(new TradeValidation().validate(new WorkbookDraft(draft.dataType(), draft.fileSha256(), draft.sheets(), metadata), context("ROIV"), registry(true)).valid());
        var sheet = draft.sheets().getFirst(); var row = sheet.rows().getFirst();
        var photo = sheet.columns().stream().filter(column -> TradeWorkbook.name(column).startsWith("Фотография объекта")).findFirst().orElseThrow();
        var cells = new java.util.ArrayList<>(row.cells().stream().filter(cell -> !cell.column().equals(photo.column())).toList());
        cells.add(new WorkbookDraft.Cell(photo.column(), null, "embeddedObject"));
        var sheets = new java.util.ArrayList<>(draft.sheets()); sheets.set(0, new WorkbookDraft.Sheet(sheet.code(), sheet.columns(), List.of(new WorkbookDraft.Row(row.excelRow(), cells))));
        assertTrue(new TradeValidation().validate(new WorkbookDraft(draft.dataType(), draft.fileSha256(), sheets, draft.metadata()), context("ROIV"), registry(true)).issues().stream().anyMatch(issue -> issue.code().equals("PHOTO_OBJECT") && issue.column().equals(photo.column())));
    }
}

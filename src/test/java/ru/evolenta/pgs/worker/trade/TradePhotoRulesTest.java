package ru.evolenta.pgs.worker.trade;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradePhotoRulesTest {
    private final Map<String, Object> photo = Map.of("originalName", "test.jpg", "_id", "current-photo", "size", 100);
    private final TradePhotoRules.PhotoCell cell = new TradePhotoRules.PhotoCell("A", "test.jpg", false, Map.of());

    @Test void acceptsCurrentPhotoAndDoesNotConsultHistoricalAppeals() {
        assertTrue(TradePhotoRules.check(List.of(cell), List.of(photo), Map.of("test.jpg", 1), "НТО").isEmpty());
    }

    @Test void rejectsDuplicateWithinCurrentWorkbook() {
        assertEquals("PHOTO_DUPLICATE", code(List.of(cell), List.of(photo), Map.of("test.jpg", 2)));
    }

    @Test void rejectsMissingAndAmbiguousCurrentAttachments() {
        assertEquals("PHOTO_FILE_NOT_FOUND", code(List.of(cell), List.of(), Map.of()));
        assertEquals("PHOTO_FILE_NOT_FOUND", code(List.of(cell), List.of(photo, photo), Map.of()));
    }

    @Test void acceptsExactlyOneMebibyteAndRejectsOneByteMore() {
        assertTrue(TradePhotoRules.check(List.of(cell), List.of(Map.of("originalName", "test.jpg", "_id", "p", "size", 1048576)), Map.of(), "НТО").isEmpty());
        assertEquals("PHOTO_SIZE", code(List.of(cell), List.of(Map.of("originalName", "test.jpg", "_id", "p", "size", 1048577)), Map.of()));
    }

    @Test void validatesSizeFormatAndJpegExtension() {
        assertEquals("PHOTO_SIZE_UNKNOWN", code(List.of(cell), List.of(Map.of("originalName", "test.jpg", "_id", "p")), Map.of()));
        assertEquals("PHOTO_SIZE_UNKNOWN", code(List.of(cell), List.of(Map.of("originalName", "test.jpg", "_id", "p", "size", -1)), Map.of()));
        var png = new TradePhotoRules.PhotoCell("A", "test.png", false, Map.of());
        assertEquals("PHOTO_FORMAT", code(List.of(png), List.of(Map.of("originalName", "test.png", "_id", "p", "size", 100)), Map.of()));
    }

    @Test void requiresMainPhotoWhenAdditionalPhotoStartsBlock() {
        var blank = new TradePhotoRules.PhotoCell("A", "", false, Map.of());
        assertEquals("PHOTO_MAIN_REQUIRED", code(List.of(blank, cell), List.of(photo), Map.of()));
    }

    @Test void rejectsEmbeddedObjectsAndNonTextCellsBeforeFileLookup() {
        assertEquals("PHOTO_OBJECT", code(List.of(new TradePhotoRules.PhotoCell("A", "test.jpg", true, Map.of())), List.of(photo), Map.of()));
        assertEquals("PHOTO_OBJECT", code(List.of(new TradePhotoRules.PhotoCell("A", 42, false, Map.of())), List.of(photo), Map.of()));
    }

    @Test void removesOneVerifiedStoragePrefixAndPreservesUserNumericPrefix() {
        var fallback = new java.util.LinkedHashMap<String, Object>(); fallback.put("originalName", null); fallback.put("name", "fallback.jpg");
        assertEquals("fallback.jpg", TradePhotoRules.fileName(fallback));
        fallback.put("originalName", ""); assertEquals("fallback.jpg", TradePhotoRules.fileName(fallback));
        assertEquals("2026_test.jpg", TradePhotoRules.fileName(Map.of("originalName", "2026_test.jpg")));
        assertEquals("2026_test.jpg", TradePhotoRules.fileName(Map.of("originalName", "123_2026_test.jpg", "storage", "S3", "entryName", "appeals", "entryId", "case", "id", "path/123_2026_test.jpg")));
        assertEquals("123_test.jpg", TradePhotoRules.fileName(Map.of("originalName", "123_test.jpg", "storage", "S3", "entryName", "appeals", "entryId", "case", "id", "unrelated")));
    }

    private String code(List<TradePhotoRules.PhotoCell> cells, List<Map<String, Object>> files, Map<String, Integer> counts) {
        return TradePhotoRules.check(cells, files, counts, "НТО").getFirst().code();
    }
}

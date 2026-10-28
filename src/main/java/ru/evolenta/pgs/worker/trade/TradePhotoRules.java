package ru.evolenta.pgs.worker.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared contract from the published trade import and single-record processes. */
public final class TradePhotoRules {
    public record PhotoCell(String column, Object value, boolean embeddedObject, Map<String, Object> file, boolean fileObject) {
        public PhotoCell(String column, Object value, boolean embeddedObject, Map<String, Object> file) { this(column, value, embeddedObject, file == null ? Map.of() : file, file != null && !file.isEmpty()); }
    }
    public record PhotoIssue(String code, String column, String message) {}
    private TradePhotoRules() {}

    public static String name(Object value) { return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT); }

    public static String fileName(Map<String, Object> file) {
        String value = text(present(file.get("originalName")) ? file.get("originalName") : file.get("name"));
        if (present(file.get("storage")) && present(file.get("entryName")) && present(file.get("entryId"))
                && text(file.get("id")).endsWith("/" + value)) value = value.replaceFirst("^\\d+_", "");
        return value;
    }

    public static List<PhotoIssue> check(List<PhotoCell> cells, List<Map<String, Object>> files,
                                         Map<String, Integer> counts, String block) {
        List<PhotoIssue> errors = new ArrayList<>();
        boolean started = cells.stream().anyMatch(c -> c.embeddedObject() || c.fileObject() || present(c.value()));
        for (int index = 0; index < cells.size(); index++) {
            PhotoCell cell = cells.get(index);
            String filename = cell.fileObject() ? fileName(cell.file()) : text(cell.value());
            String code = "", message = "";
            if (cell.embeddedObject() || (cell.value() != null && !(cell.value() instanceof CharSequence))) {
                code = "PHOTO_OBJECT"; message = "В ячейке содержится объект, а не название файла. Укажите имя файла.";
            } else if (name(filename).isEmpty()) {
                if (index == 0 && started) { code = "PHOTO_MAIN_REQUIRED"; message = "Блок «" + block + "»: заполните основную фотографию объекта."; }
            } else {
                List<Map<String, Object>> matches = cell.fileObject() ? List.of(cell.file())
                        : files.stream().filter(file -> name(fileName(file)).equals(name(filename))).toList();
                if (matches.size() != 1 || !hasId(matches.getFirst())) {
                    code = "PHOTO_FILE_NOT_FOUND"; message = "Указанный файл не найден однозначно. Проверьте имя с расширением и загрузите файл в группу документов.";
                } else if (counts.getOrDefault(name(filename), 0) > 1) {
                    code = "PHOTO_DUPLICATE"; message = "Наименование файла должно быть уникальным в блоке «" + block + "».";
                } else if (!filename.matches("(?i).+\\.jpe?g")) {
                    code = "PHOTO_FORMAT"; message = "Допускаются только файлы формата JPEG или JPG.";
                } else {
                    String size = text(matches.getFirst().get("size"));
                    if (!size.matches("\\d+")) { code = "PHOTO_SIZE_UNKNOWN"; message = "Не удалось определить размер файла. Загрузите файл повторно."; }
                    else if (new BigDecimal(size).compareTo(BigDecimal.valueOf(1048576)) > 0) {
                        code = "PHOTO_SIZE"; message = "Размер файла превышает допустимый лимит — 1 МБ.";
                    }
                }
            }
            if (!code.isEmpty()) errors.add(new PhotoIssue(code, cell.column(), message));
        }
        return List.copyOf(errors);
    }

    private static boolean hasId(Map<String, Object> file) { return present(file.get("_id")) || present(file.get("id")) || present(file.get("fileId")); }
    private static boolean present(Object value) { return !text(value).isEmpty(); }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
}

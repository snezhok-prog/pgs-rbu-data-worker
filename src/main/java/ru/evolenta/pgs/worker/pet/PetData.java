package ru.evolenta.pgs.worker.pet;

import com.fasterxml.jackson.databind.ObjectMapper;

import ru.evolenta.pgs.worker.trade.core.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.*;
import java.util.*;

final class PetData {
    private PetData() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

    static Map<String, Object> map(Object value) {
        return value == null ? new LinkedHashMap<>() : Data.object(value);
    }

    static List<?> list(Object value) {
        return value == null ? List.of() : Data.list(value);
    }

    static List<Map<String, Object>> maps(Object value) {
        return list(value).stream().map(PetData::map).toList();
    }

    static String text(Object value) {
        return Data.text(value);
    }

    static String clean(Object value) {
        return text(value).replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    static String key(Object value) {
        return clean(value).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    static String validationClean(Object value) {
        return text(value).trim();
    }

    static String validationKey(Object value) {
        return validationClean(value)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ");
    }

    static boolean present(Object value) {
        return value != null && (!(value instanceof CharSequence sequence) || !sequence.isEmpty());
    }

    static boolean validationPresent(Object value) {
        return value != null && !text(value).trim().isEmpty();
    }

    static Object first(Object... values) {
        for (Object value : values)
            if (value != null && (!(value instanceof CharSequence sequence) || !sequence.isEmpty()))
                return value;
        return null;
    }

    static Map<String, Object> obj(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Odd object entries");
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    static Map<String, Object> nonempty(Map<String, Object> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach(
                (name, value) -> {
                    if (value != null && (!(value instanceof CharSequence text) || !text.isEmpty()))
                        result.put(name, value);
                });
        return result;
    }

    static String digits(Object value) {
        return clean(value).replaceAll("\\D+", "");
    }

    static String baseName(Object value) {
        String text = clean(value).replace('\\', '/');
        return text.substring(text.lastIndexOf('/') + 1);
    }

    static LocalDate date(Object value) {
        String text = clean(value);
        if (text.isEmpty()) return null;
        try {
            return LocalDate.parse(text, DATE);
        } catch (DateTimeParseException ignored) {
            return LocalDate.parse(text);
        }
    }

    static String isoDate(Object value) {
        LocalDate date = date(value);
        return date == null ? null : date.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
    }

    static String isoDateTime(Object value, Object timeValue) {
        LocalDate date = date(value);
        if (date == null) return null;
        String time = clean(timeValue);
        if (time.isEmpty()) time = "00:00";
        if (time.length() < 5)
            throw new IllegalArgumentException("Некорректное время «" + time + "».");
        return LocalDateTime.of(
                        date,
                        LocalTime.parse(time.substring(0, 5), DateTimeFormatter.ofPattern("HH:mm")))
                .toInstant(ZoneOffset.UTC)
                .toString();
    }

    static Map<String, Object> address(Object value) {
        String text = clean(value);
        if (text.isEmpty()) return null;
        var matcher =
                java.util.regex.Pattern.compile("^\\s*(\\d{6})(?:\\s*,\\s*|\\s+)(.*)$")
                        .matcher(text);
        return matcher.matches()
                ? obj("postalCode", matcher.group(1), "fullAddress", clean(matcher.group(2)))
                : obj("fullAddress", text);
    }

    static Object path(Object value, String path) {
        Object current = value;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    static String display(Object value) {
        if (value instanceof List<?> values)
            return values.isEmpty() ? "" : display(values.getFirst());
        if (value instanceof Map<?, ?>) {
            var map = map(value);
            return clean(
                    first(
                            map.get("organizationName"),
                            map.get("municipalityName"),
                            map.get("regionName"),
                            map.get("name"),
                            map.get("fullName"),
                            map.get("title"),
                            map.get("shortName"),
                            map.get("code"),
                            map.get("id"),
                            map.get("_id")));
        }
        return clean(value);
    }

    static String sha(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(JSON.writeValueAsBytes(value)));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Cannot fingerprint JSON", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String legacyRowSha(Object value) {
        try {
            String json = JSON.writeValueAsString(value);
            var ascii = new StringBuilder(json.length());
            String hex = "0123456789abcdef";
            // JsonOutput.toJson escapes every non-ASCII UTF-16 unit with lower-case hex.
            // Keep the published rowHash stable without executing Groovy in Java.
            for (char character : json.toCharArray())
                if (character >= 127) {
                    ascii.append("\\u");
                    for (int shift = 12; shift >= 0; shift -= 4)
                        ascii.append(hex.charAt((character >>> shift) & 15));
                } else ascii.append(character);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(ascii.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Cannot fingerprint source row", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;

import java.util.*;

final class PetFiles {
    private static final Set<String> GROUPS =
            Set.of("296c71e2-580e-4ead-a7e9-b789f3aa4aeb", "c84c2b64-45af-413d-afbd-5206fd95d35a");
    private final Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();

    PetFiles(Map<String, Object> attributes) {
        var files = new ArrayList<Map<String, Object>>(maps(attributes.get("documentFiles")));
        for (var group : maps(attributes.get("documents"))) {
            String name = key(group.get("groupName"));
            if (!(name.startsWith(key("Файлы для загрузки в реестр"))
                    || name.equals(key("Файлы миграции"))
                    || text(group.get("mnemonic")).equals("petMigrationFiles")
                    || GROUPS.contains(text(group.get("groupGuid"))))) continue;
            files.addAll(maps(group.get("files")));
            for (var document : maps(group.get("documents")))
                files.addAll(maps(document.get("files")));
        }
        var seen = new HashSet<String>();
        for (var file : files) {
            String id = clean(first(file.get("_id"), file.get("id"), file.get("fileId")));
            String normalized =
                    key(
                            baseName(first(file.get("originalName"), file.get("name")))
                                    .replaceFirst("^\\d+_", ""));
            if (!id.isEmpty() && !seen.add(id)) continue;
            byName.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(file);
        }
    }

    List<Map<String, Object>> matching(Object name) {
        return byName.getOrDefault(key(baseName(name)), List.of());
    }

    boolean exists(Object name) {
        return matching(name).size() == 1;
    }

    boolean nonzero(Object name, long minimum) {
        var matches = matching(name);
        if (matches.isEmpty()) return true;
        if (matches.size() != 1) return false;
        Object size = first(matches.getFirst().get("size"), matches.getFirst().get("fileSize"));
        try {
            return size != null && Long.parseLong(text(size)) >= minimum;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    Map<String, Object> resolve(Object name, String fieldPath) {
        if (clean(name).isEmpty()) return null;
        var matches = matching(name);
        if (matches.size() != 1)
            throw new IllegalArgumentException(
                    "Для файла «" + baseName(name) + "» найдено совпадений: " + matches.size());
        var file = new LinkedHashMap<>(matches.getFirst());
        String id = clean(first(file.get("_id"), file.get("id"), file.get("fileId")));
        if (id.isEmpty())
            throw new IllegalArgumentException("У файла отсутствует идентификатор хранилища");
        file.put("_id", id);
        file.put("originalName", first(file.get("originalName"), file.get("name"), baseName(name)));
        file.put("entityFieldPath", fieldPath);
        file.putIfAbsent("parentEntries", "rbuModulesAppeals");
        return file;
    }
}

package ru.evolenta.server.worker;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipInputStream;
import ru.evolenta.pgs.worker.PgsApi;
import ru.evolenta.server.service.FileStorageService;
import ru.evolenta.server.storage.StorageEngine;
import ru.evolenta.server.storage.StorageInputStream;
import static ru.evolenta.pgs.worker.Util.*;

/** Uses native registered storage engines and authorizes each file against its requesting case. */
public final class SdkFilePgsApi implements PgsApi {
    private final PgsApi registry;
    private final FileStorageService storage;

    public SdkFilePgsApi(PgsApi registry, FileStorageService storage) {
        this.registry = Objects.requireNonNull(registry);
        this.storage = Objects.requireNonNull(storage);
    }

    @Override public byte[] download(String fileId, long maxBytes) {
        throw new IllegalStateException("Native file download requires an explicit appeal context");
    }

    @Override public byte[] download(String collection, String appealId, String fileId, long maxBytes) throws Exception {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid file byte limit");
        Snapshot before = authorize(collection, appealId, fileId, maxBytes);
        byte[] bytes;
        try (StorageInputStream nativeStream = before.engine().getFileStream(before.storageId())) {
            if (nativeStream == null || nativeStream.getInputStream() == null) throw new IOException("Storage returned no input stream");
            if (before.compressed()) {
                try (ZipInputStream zip = new ZipInputStream(nativeStream.getInputStream())) {
                    var entry = zip.getNextEntry();
                    if (entry == null || entry.isDirectory()) throw new IOException("Invalid native compressed file");
                    bytes = readBounded(zip, maxBytes);
                    if (zip.getNextEntry() != null) throw new IOException("Native compressed file contains multiple entries");
                }
            } else {
                bytes = readBounded(nativeStream.getInputStream(), maxBytes);
            }
        }
        if (!before.compressed() && before.size() != null && before.size() != bytes.length) {
            throw new IllegalStateException("Stored file size differs from uploadedFiles metadata");
        }
        Snapshot after = authorize(collection, appealId, fileId, maxBytes);
        if (!before.fingerprint().equals(after.fingerprint())) throw new IllegalArgumentException("File metadata or ownership changed during download");
        return bytes;
    }

    private Snapshot authorize(String collection, String appealId, String fileId, long maxBytes) throws Exception {
        if (!"rbuModulesAppeals".equals(collection) || appealId == null || fileId == null
                || !appealId.matches("[a-fA-F0-9]{24}") || !fileId.matches("[a-fA-F0-9]{24}")) {
            throw new IllegalArgumentException("Invalid case or uploaded file identity");
        }
        Map<String,Object> current = caseById(collection, appealId);
        if (!mentioned(current, fileId)) throw new IllegalArgumentException("File is not referenced by the current case");
        Map<String,Object> metadata = registry.find("uploadedFiles", fileId);
        String sourceId = clean(metadata.get("entryId")), storageName = clean(metadata.get("storage"));
        if (!fileId.equals(id(metadata)) || !collection.equals(metadata.get("entryName")) || !sourceId.matches("[a-fA-F0-9]{24}")
                || storageName.isEmpty() || bool(metadata.get("isDeleted"))) throw new IllegalArgumentException("Invalid uploadedFiles metadata");
        Map<String,Object> source = sourceId.equals(appealId) ? current : caseById(collection, sourceId);
        String owner = owner(current);
        if (!owner.equals(owner(source))) throw new IllegalArgumentException("File container belongs to another organization");
        StorageEngine engine = storage.getStorage(storageName);
        // The native registry returns its default for an unknown name; that fallback is not authorization.
        if (engine == null || !storageName.equals(engine.getStorageName())) throw new IllegalArgumentException("Configured storage does not match file metadata");
        String storageId = clean(metadata.get("id"));
        requireStoragePath(storageId, engine.getRootDir(), collection, sourceId);
        boolean compressed = bool(metadata.get("compressed"));
        Long size = null;
        if (metadata.get("size") != null) {
            try { size = Objects.requireNonNull(number(metadata.get("size"))).longValueExact(); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid uploaded file size", error); }
            if (size < 0 || !compressed && size > maxBytes) throw new IllegalArgumentException("Uploaded file exceeds the byte limit");
        }
        return new Snapshot(engine, storageId, compressed, size, hash(obj("metadata", metadata, "owner", owner)));
    }

    private Map<String,Object> caseById(String collection, String appealId) throws Exception {
        Map<String,Object> appeal = registry.find(collection, appealId);
        if (!appealId.equals(id(appeal))) throw new IllegalArgumentException("File case was not found");
        return appeal;
    }

    private static String owner(Map<String,Object> appeal) {
        String flat = clean(appeal.get("unitId")), nested = clean(at(appeal, "unit.id"));
        if (!flat.isEmpty() && !nested.isEmpty() && !flat.equals(nested)) throw new IllegalArgumentException("Case ownership is inconsistent");
        String owner = flat.isEmpty() ? nested : flat;
        if (owner.isEmpty()) throw new IllegalArgumentException("Case owner is absent");
        return owner;
    }

    private static void requireStoragePath(String path, String rootDirectory, String collection, String sourceId) {
        String root = clean(rootDirectory).replaceAll("/+$", ""), prefix = root + "/" + collection + "/";
        if (root.isEmpty() || path.contains("\\") || path.contains("://") || path.contains("?")
                || path.contains("#") || path.indexOf('\0') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 || !path.startsWith(prefix)) {
            throw new IllegalArgumentException("File storage path does not match its container");
        }
        String[] parts = path.substring(prefix.length()).split("/", -1);
        if (parts.length != 5 || !parts[0].matches("[0-9]{4}") || !parts[1].matches("[0-9]{2}") || !parts[2].matches("[0-9]{2}")
                || !sourceId.equals(parts[3]) || parts[4].isEmpty() || parts[4].equals(".") || parts[4].equals("..")) throw new IllegalArgumentException("File storage path does not match its container");
        try { LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid file storage date path", error); }
    }

    private static boolean mentioned(Map<String,Object> appeal, String fileId) {
        int[] remaining = {100000};
        for (String field : List.of("importFile", "documents", "subservices")) {
            if (mentioned(appeal.get(field), fileId, 0, remaining)) return true;
        }
        return false;
    }

    private static boolean mentioned(Object value, String fileId, int depth, int[] remaining) {
        if (--remaining[0] < 0 || depth > 32) throw new IllegalArgumentException("Case file references exceed the inspection limit");
        if (value instanceof Map) {
            Map<String,Object> object = map(value);
            for (String field : List.of("_id", "id", "fileId")) if (fileId.equals(clean(object.get(field)))) return true;
            for (Object child : object.values()) if (mentioned(child, fileId, depth + 1, remaining)) return true;
        } else if (value instanceof List) {
            for (Object child : list(value)) if (mentioned(child, fileId, depth + 1, remaining)) return true;
        }
        return false;
    }

    private record Snapshot(StorageEngine engine, String storageId, boolean compressed, Long size, String fingerprint) {}

    @Override public Map<String,Object> find(String collection, String id) throws Exception { return registry.find(collection, id); }
    @Override public List<Map<String,Object>> search(String collection, List<Object> filters, int page, int size) throws Exception { return registry.search(collection, filters, page, size); }
    @Override public void update(String collection, String id, String guid, Map<String,Object> patch) throws Exception { registry.update(collection, id, guid, patch); }
    @Override public void updateSub(String collection, String id, String guid, String parent, Map<String,Object> patch) throws Exception { registry.updateSub(collection, id, guid, parent, patch); }
    @Override public Map<String,Object> create(String collection, Map<String,Object> value) throws Exception { return registry.create(collection, value); }
    @Override public void delete(String collection, String id, String guid) throws Exception { registry.delete(collection, id, guid); }
}

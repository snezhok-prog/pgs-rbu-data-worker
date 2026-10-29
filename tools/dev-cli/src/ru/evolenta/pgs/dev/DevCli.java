package ru.evolenta.pgs.dev;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import ru.evolenta.pgs.worker.Json;
import static ru.evolenta.pgs.worker.Util.*;

/** Explicit CMD commands for one owned external-task acceptance run. */
public final class DevCli {
    static final long MAX_FILE = 20L * 1024 * 1024;
    static final String TOPIC = "pgs-data-processing-v1";
    interface Transport { Object send(String method, String url, byte[] body, String contentType, Credentials credentials) throws Exception; }
    static final class Credentials {
        private final String kind, value;
        private Credentials(String kind, String value) { this.kind = kind; this.value = value; }
        static Credentials token(String input) {
            String value = input == null ? "" : input.trim().replaceFirst("(?i)^Bearer\\s+", "");
            if (value.isBlank() || Set.of("null", "undefined").contains(value.toLowerCase(Locale.ROOT)) || value.contains("\r") || value.contains("\n")) throw new IllegalArgumentException("Enter an actual user token; literal null/undefined is invalid");
            return new Credentials("token", value);
        }
        static Credentials session(String input) {
            String value = input == null ? "" : input.trim().replaceFirst("(?i)^Cookie:\\s*", "");
            if (value.isBlank() || value.contains("\r") || value.contains("\n") || !value.contains("=")) throw new IllegalArgumentException("Enter one Cookie header value without line breaks");
            for (String part : value.split(";", -1)) if (!part.trim().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+=[^;\\r\\n]*")) throw new IllegalArgumentException("Invalid Cookie header");
            return new Credentials("session", value);
        }
        Map<String,String> headers() {
            if (kind.equals("token")) return Map.of("Authorization", "Bearer " + value);
            Map<String,String> headers = new LinkedHashMap<>(); headers.put("Cookie", value);
            for (String pair : value.split(";")) { int at = pair.indexOf('='); if (pair.substring(0, at).trim().equals("XSRF-TOKEN")) headers.put("X-XSRF-TOKEN", java.net.URLDecoder.decode(pair.substring(at + 1).trim().replace("+", "%2B"), StandardCharsets.UTF_8)); }
            return headers;
        }
        @Override public String toString() { return "Credentials(" + kind + ", redacted)"; }
    }
    static final class HttpFailure extends IOException {
        final int status; final String method;
        HttpFailure(int status, String method, String url) { super("HTTP " + status + " " + method + " " + URI.create(url).getPath() + "; no automatic retry"); this.status = status; this.method = method; }
    }
    static final class HttpTransport implements Transport {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        public Object send(String method, String url, byte[] body, String type, Credentials credentials) throws Exception {
            URI uri = URI.create(url);
            if (credentials != null && !"https".equals(uri.getScheme()) && !Set.of("127.0.0.1", "localhost", "::1").contains(uri.getHost())) throw new IllegalArgumentException("Upload credentials require HTTPS");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
            if (type != null) request.header("Content-Type", type);
            if (credentials != null) credentials.headers().forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new HttpFailure(response.statusCode(), method, url);
            return response.body().isBlank() ? obj() : Json.parse(response.body());
        }
    }
    final Path repo, run;
    final String name;
    final Transport transport;
    Map<String,Object> settings, state;
    String pendingThisCommand;
    DevCli(Path repo, String name, Transport transport) throws Exception {
        if (!name.matches("[a-z][a-z0-9]{1,20}")) throw new IllegalArgumentException("Run name: 2-21 lowercase letters/digits, starts with a letter");
        this.repo = repo.toAbsolutePath().normalize(); this.name = name; this.transport = transport;
        run = this.repo.resolve("runtime/dev-cli").resolve(name);
        settings = read(run.resolve("settings.json")); state = read(run.resolve("state.json"));
    }
    static Map<String,Object> read(Path file) throws IOException { return Files.exists(file) ? map(Json.parse(Files.readString(file, StandardCharsets.UTF_8))) : obj(); }
    static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent()); Path temp = file.resolveSibling(file.getFileName() + ".tmp"); Files.writeString(temp, Json.write(value), StandardCharsets.UTF_8);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    void save() throws IOException { write(run.resolve("state.json"), state); }
    String value(String key) { String v = clean(state.get(key)); if (v.isEmpty()) throw new IllegalStateException("Missing state: " + key); return v; }
    String domain() { return clean(settings.get("domain")); }
    String core() { return clean(at(settings, "context.coreUrl")); }
    String engine() { return clean(at(settings, "context.engineUrl")); }
    Object call(String method, String url, Object body) throws Exception { return transport.send(method, url, body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8), body == null ? null : "application/json", null); }
    void pending(String operation) throws Exception {
        if (present(state.get("pending"))) throw new IllegalStateException("Previous operation is unconfirmed: " + state.get("pending") + ". Inspect state/server; do not delete the journal or retry blindly.");
        state.put("pending", operation); pendingThisCommand = operation; save();
    }
    void finished() throws IOException { state.remove("pending"); save(); pendingThisCommand = null; }
    void recordFailure(Exception e) throws Exception {
        if (e instanceof HttpFailure f && (f.status == 401 || f.status == 403) && !f.method.equals("GET") && pendingThisCommand != null && pendingThisCommand.equals(state.get("pending"))) finished();
    }
    static void noSecrets(Object value) {
        if (value instanceof Map<?,?> m) for (var e : m.entrySet()) {
            if (Pattern.compile("(?i)(?:token|password|secret|cookie|authorization|credential)").matcher(String.valueOf(e.getKey())).find()) throw new IllegalArgumentException("Context/settings must not contain credentials");
            noSecrets(e.getValue());
        } else if (value instanceof List<?> list) list.forEach(DevCli::noSecrets);
    }
    static URI endpoint(Object value, boolean http, boolean trailingSlash) {
        URI uri = URI.create(clean(value));
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !(uri.getScheme().equals("https") || http && uri.getScheme().equals("http"))) throw new IllegalArgumentException("Invalid endpoint; use an explicit HTTP(S) URL without credentials/query");
        if (trailingSlash && !uri.toString().endsWith("/")) throw new IllegalArgumentException("API base URL must end with /"); return uri;
    }
    static boolean objectId(Object x) { return clean(x).matches("[a-f0-9]{24}"); }
    static final class RunLock implements AutoCloseable {
        final FileChannel channel; final FileLock lock;
        RunLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        public void close() throws IOException { lock.release(); channel.close(); }
    }
    RunLock lock() throws IOException {
        Path locks = repo.resolve("runtime/dev-cli/.locks"); Files.createDirectories(locks);
        FileChannel channel = FileChannel.open(locks.resolve(name + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { FileLock lock = channel.tryLock(); if (lock == null) throw new IllegalStateException("Another command is changing this run"); return new RunLock(channel, lock); }
        catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }
    static String canonicalType(String value) {
        if (Set.of("НТО", "Рынки", "Ярмарки").contains(value)) return value;
        throw new IllegalArgumentException("Trade data type: НТО, Рынки or Ярмарки");
    }
    void init(String domain, Path contextFile, String dataType) throws Exception {
        if (!Set.of("ADVERTISING", "TRADE", "PET").contains(domain)) throw new IllegalArgumentException("Domain: ADVERTISING, TRADE or PET");
        if (Files.exists(run)) throw new IllegalStateException("Run already exists. Reuse its state and journal.");
        Map<String,Object> context = read(contextFile); noSecrets(context);
        boolean http = bool(context.get("allowHttp"));
        endpoint(context.get("coreUrl"), http, true); endpoint(context.get("engineUrl"), http, true);
        URI upload = endpoint(context.get("uploadUrl"), http, false), ui = endpoint(context.get("uiBaseUrl"), http, false);
        endpoint(context.get("cloudConfigUrl"), http, false); endpoint(context.get("storageEndpoint"), http, false);
        if (!upload.getScheme().equals(ui.getScheme()) || !upload.getAuthority().equals(ui.getAuthority())) throw new IllegalArgumentException("Upload origin must match uiBaseUrl");
        for (String field : List.of("organization", "region", "municipality", "card")) if (!objectId(at(context, field + "._id"))) throw new IllegalArgumentException("Context requires " + field + "._id from DEV");
        if (!clean(context.get("standardCode")).matches("[1-9][0-9]{3,9}")) throw new IllegalArgumentException("Context requires DEV import standardCode");
        int port = integer(context.getOrDefault("fileAdapterPort", 18085)); if (port < 1024 || port > 65535) throw new IllegalArgumentException("fileAdapterPort: 1024-65535");
        if (domain.equals("TRADE")) dataType = canonicalType(dataType); else dataType = domain.equals("PET") ? "Мой питомец" : "Реклама";
        String runId = UUID.randomUUID().toString(), suffix = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + runId.substring(0, 8);
        String prefix = domain.equals("TRADE") ? "PgsTrade" : domain.equals("PET") ? "PgsPet" : "PgsAdvertising", topic = TOPIC + "-" + domain.toLowerCase(Locale.ROOT) + "-" + name + "-" + runId.substring(0, 8);
        Path template = repo.resolve("tools/dev-cli/templates/" + domain.toLowerCase(Locale.ROOT) + "-acceptance.bpmn");
        String xml = Files.readString(template, StandardCharsets.UTF_8);
        if (!xml.contains(TOPIC) || !xml.contains(prefix + "ImportV1")) throw new IllegalArgumentException("Unsupported acceptance BPMN template");
        xml = xml.replace(TOPIC, topic);
        for (String kind : List.of("Import", "Validation", "Rollback")) xml = xml.replace(prefix + kind + "V1", prefix + kind + "V1_" + suffix);
        settings = obj("domain", domain, "dataType", dataType, "topic", topic, "processKey", prefix + "ImportV1_" + suffix, "context", context);
        state = obj("runId", runId, "name", name); Files.createDirectories(run); Files.writeString(run.resolve("acceptance.bpmn"), xml, StandardCharsets.UTF_8);
        write(run.resolve("settings.json"), settings); write(run.resolve("files.json"), obj("runId", runId, "files", obj())); save();
        Properties p = new Properties(); try (Reader in = Files.newBufferedReader(repo.resolve("config/worker.dev.properties"), StandardCharsets.UTF_8)) { p.load(in); }
        p.setProperty("camunda.baseUrl", clean(context.get("engineUrl"))); p.setProperty("pgs.baseUrl", clean(context.get("coreUrl")));
        p.setProperty("file.baseUrl", "http://127.0.0.1:" + port + "/"); p.setProperty("worker.topic", topic); p.setProperty("worker.instanceId", "rbu-" + name);
        p.setProperty("worker.standardCodes", clean(context.get("standardCode"))); p.setProperty("worker.uiBaseUrl", ui.toString());
        p.setProperty("worker.journalDirectory", run.resolve("journal").toString()); p.setProperty("worker.allowHttp", String.valueOf(http));
        p.setProperty("worker.integrationVerified", String.valueOf(bool(context.get("apiIntegrationVerified")))); p.setProperty("worker.writesEnabled", "false");
        try (Writer out = Files.newBufferedWriter(run.resolve("worker.properties"), StandardCharsets.UTF_8)) { p.store(out, "Local acceptance worker; no credentials"); }
        System.out.println("LOCAL INIT: domain=" + domain + "; type=" + dataType + "; topic=" + topic); System.out.println("Review BPMN before deploy: " + run.resolve("acceptance.bpmn"));
    }
    static byte[] multipart(String boundary, Map<String,String> fields, String part, String filename, String mime, byte[] data) throws IOException {
        if (filename.contains("\r") || filename.contains("\n") || filename.contains("\"")) throw new IllegalArgumentException("Unsupported filename");
        var out = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)); return out.toByteArray();
    }
    void deploy() throws Exception {
        if (present(state.get("deploymentId"))) throw new IllegalStateException("Already deployed");
        String boundary = "rbu-" + UUID.randomUUID(); byte[] body = multipart(boundary, Map.of("deployment-name", "RBU-" + name + "-test", "enable-duplicate-filtering", "true"), "process", "acceptance.bpmn", "text/xml", Files.readAllBytes(run.resolve("acceptance.bpmn")));
        pending("DEPLOY"); Map<String,Object> result = map(transport.send("POST", engine() + "deployment/create", body, "multipart/form-data; boundary=" + boundary, null));
        if (!present(result.get("id"))) throw new IOException("No deployment id"); state.put("deploymentId", result.get("id")); finished(); System.out.println("DEPLOYED " + state.get("deploymentId"));
    }
    void create(String profile) throws Exception {
        if (!Set.of("ROIV", "OMSU").contains(profile)) throw new IllegalArgumentException("Profile: ROIV or OMSU");
        if (present(state.get("appealId"))) throw new IllegalStateException("Test record already exists");
        Map<String,Object> ctx = map(settings.get("context")), org = map(ctx.get("organization"));
        String marker = "RBU-CMD-" + value("runId"); state.put("marker", marker); state.put("ownerId", org.get("_id")); state.put("profile", profile);
        Map<String,Object> body = obj("name", "РБУ — " + name, "number", marker, "unitId", org.get("_id"), "unit", obj("id", org.get("_id"), "name", org.get("name")), "status", obj("code", "process"), "testPurpose", marker, "region", ctx.get("region"), "municipality", ctx.get("municipality"), "organization", ctx.get("card"), "dataType", settings.get("dataType"));
        pending("CREATE_APPEAL"); Map<String,Object> a = map(call("POST", core() + "create/rbuModulesAppeals", body));
        if (!objectId(a.get("_id")) || !present(a.get("guid"))) throw new IOException("No test record identity"); state.put("appealId", a.get("_id")); state.put("appealGuid", a.get("guid")); finished(); System.out.println("TEST RECORD " + state.get("appealId") + "; not a registered PGS case");
    }
    Map<String,Object> appeal() throws Exception {
        Map<String,Object> a = map(call("GET", core() + "find/rbuModulesAppeals?mainId=" + value("appealId"), null));
        if (!value("appealId").equals(clean(a.get("_id"))) || !value("appealGuid").equals(clean(a.get("guid"))) || !value("ownerId").equals(clean(a.get("unitId"))) || !value("ownerId").equals(clean(at(a, "unit.id"))) || !value("marker").equals(clean(a.get("testPurpose")))) throw new IllegalStateException("Test record identity/owner/marker mismatch");
        return a;
    }
    static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        throw new IllegalArgumentException("Supported upload types: XLSX, PDF, JPG, PNG, DOCX");
    }
    void upload(Path path, Credentials credentials, boolean extra) throws Exception {
        appeal(); if (present(state.get("processId"))) throw new IllegalStateException("Upload before starting validation; new inputs require a new run");
        if (!extra && present(state.get("file"))) throw new IllegalStateException("XLSX already uploaded; use attach, not a second upload");
        String filename = path.getFileName().toString(), type = mime(filename);
        if (!extra && !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("Import file must be XLSX");
        if (credentials == null) throw new IllegalArgumentException("Explicit DEV session or token is required");
        byte[] data; try (InputStream in = Files.newInputStream(path)) { data = readBounded(in, MAX_FILE); }
        String hash = sha(data); for (Map<String,Object> f : maps(state.get("extraFiles"))) if (hash.equals(f.get("sha256")) && (filename.equals(f.get("name")) || filename.equals(f.get("originalName")))) throw new IllegalStateException("This attachment is already uploaded");
        String boundary = "rbu-" + UUID.randomUUID(); byte[] body = multipart(boundary, Map.of("entryName", "rbuModulesAppeals", "entryId", value("appealId")), "file", filename, type, data);
        pending(extra ? "UPLOAD_EXTRA" : "UPLOAD_FILE");
        Map<String,Object> file = map(transport.send("POST", clean(at(settings, "context.uploadUrl")), body, "multipart/form-data; boundary=" + boundary, credentials));
        noSecrets(file);
        if (!objectId(file.get("_id")) || !present(file.get("id")) || !value("appealId").equals(clean(file.get("entryId"))) || !"rbuModulesAppeals".equals(file.get("entryName"))) throw new IOException("Upload metadata is incomplete or belongs to another record; reconcile pending result before retry");
        file.put("sha256", hash); file.putIfAbsent("originalName", filename); file.putIfAbsent("contentType", type);
        if (extra) { var all = new ArrayList<Object>(list(state.get("extraFiles"))); all.add(file); state.put("extraFiles", all); } else state.put("file", file);
        finished(); addFiles(); attach(); System.out.println((extra ? "UPLOADED; DOCUMENT GROUP WILL BE LINKED AT SETUP " : "UPLOADED AND ATTACHED ") + file.get("_id"));
    }
    void uploadDocuments(Path directory, Credentials credentials) throws Exception {
        if (present(state.get("pending"))) throw new IllegalStateException("Resolve the pending operation before batch upload");
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("An attachment directory is required");
        List<Path> files; try (var paths = Files.list(directory)) { files = paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".+\\.(pdf|jpg|jpeg|png|docx)")).sorted().toList(); }
        if (files.isEmpty() || files.size() > 64) throw new IllegalArgumentException("Choose a directory with 1-64 PDF/JPG/PNG/DOCX attachments");
        System.out.println("Attachment files selected: " + files.size());
        for (Path file : files) {
            byte[] bytes; try (InputStream in = Files.newInputStream(file)) { bytes = readBounded(in, MAX_FILE); }
            String fingerprint = sha(bytes), filename = file.getFileName().toString();
            if (maps(state.get("extraFiles")).stream().anyMatch(f -> fingerprint.equals(f.get("sha256")) && (filename.equals(f.get("name")) || filename.equals(f.get("originalName"))))) { System.out.println("SKIP already uploaded: " + filename); continue; }
            upload(file, credentials, true);
        }
    }
    void addFiles() throws Exception {
        Map<String,Object> manifest = read(run.resolve("files.json")), files = obj();
        if (present(state.get("file"))) { Map<String,Object> f = map(state.get("file")); files.put(clean(f.get("_id")), f); }
        for (Map<String,Object> f : maps(state.get("extraFiles"))) files.put(clean(f.get("_id")), f);
        manifest.putAll(obj("runId", value("runId"), "appealId", value("appealId"), "ownerId", value("ownerId"), "files", files)); write(run.resolve("files.json"), manifest);
    }
    void attach() throws Exception { attach(false); }
    void attach(boolean duringSetup) throws Exception {
        Map<String,Object> a = appeal();
        if (present(state.get("processId"))) { if (!duringSetup) throw new IllegalStateException("Inputs are frozen after process start"); task("Setup"); value("subGuid"); }
        if (!present(state.get("file")) && list(state.get("extraFiles")).isEmpty()) throw new IllegalStateException("No uploaded files to attach"); addFiles();
        if (present(state.get("file")) && !at(state, "file._id").equals(at(a, "importFile._id"))) {
            pending("ATTACH_WORKBOOK"); call("PUT", core() + "update/rbuModulesAppeals?mainId=" + value("appealId") + "&guid=" + value("appealGuid"), obj("importFile", state.get("file")));
            a = appeal(); if (!at(state, "file._id").equals(at(a, "importFile._id"))) throw new IOException("Uploaded workbook metadata was not attached"); finished();
        }
        if (list(state.get("extraFiles")).isEmpty() || !present(state.get("subGuid"))) return;
        List<Map<String,Object>> groups = maps(a.get("documents")).stream().filter(g -> value("subGuid").equals(g.get("subserviceGuid")) && "296c71e2-580e-4ead-a7e9-b789f3aa4aeb".equals(g.get("groupGuid"))).toList();
        if (groups.size() > 1) throw new IllegalStateException("Ambiguous migration document group");
        Map<String,Object> group;
        if (groups.isEmpty()) {
            pending("CREATE_DOCUMENT_GROUP"); group = map(call("POST", core() + "create/rbuModulesAppeals/documents?mainId=" + value("appealId") + "&parentGuid=" + value("appealGuid") + "&parentEntries=rbuModulesAppeals.documents", obj("groupName", "Файлы миграции", "groupGuid", "296c71e2-580e-4ead-a7e9-b789f3aa4aeb", "subserviceGuid", value("subGuid"), "files", state.get("extraFiles"))));
            if (!present(group.get("guid"))) throw new IOException("No document group identity"); state.put("documentGroupGuid", group.get("guid")); save();
        } else group = groups.getFirst();
        Map<String,Object> saved = appeal(); String groupGuid = clean(group.get("guid"));
        List<Map<String,Object>> savedGroups = maps(saved.get("documents")).stream().filter(g -> groupGuid.equals(g.get("guid")) && value("subGuid").equals(g.get("subserviceGuid"))).toList();
        if (savedGroups.size() != 1) throw new IOException("Migration document group was not saved");
        Set<String> savedFiles = new HashSet<>(); for (Map<String,Object> file : maps(savedGroups.getFirst().get("files"))) savedFiles.add(clean(file.get("_id")));
        for (Map<String,Object> file : maps(state.get("extraFiles"))) if (!savedFiles.contains(clean(file.get("_id")))) throw new IOException("Uploaded attachment metadata was not attached to this subservice");
        if ("CREATE_DOCUMENT_GROUP".equals(pendingThisCommand)) finished();
    }
    static Map<String,Object> variables(Map<String,Object> values) { Map<String,Object> typed = obj(); values.forEach((k,v) -> typed.put(k, obj("value", v, "type", v instanceof Boolean ? "Boolean" : "String"))); return typed; }
    void start() throws Exception {
        if (!present(state.get("deploymentId"))) throw new IllegalStateException("Deploy this run's acceptance BPMN first");
        Map<String,Object> a = appeal(); if (!present(at(a, "importFile._id"))) throw new IllegalStateException("Upload/attach XLSX first");
        if (present(state.get("processId"))) throw new IllegalStateException("Process already exists; use status");
        Map<String,Object> vars = variables(obj("mainId", value("appealId"), "collectionName", "rbuModulesAppeals.subservices", "camundaBusinessInfoId", "rbu-acceptance-" + value("runId"), "rbuDomain", domain(), "rbuProfile", value("profile"), "rbuContractVersion", "1", "rbuRepeat", false));
        pending("START_PROCESS"); Map<String,Object> process = map(call("POST", engine() + "process-definition/key/" + settings.get("processKey") + "/start", obj("businessKey", value("marker"), "variables", vars)));
        if (!present(process.get("id"))) throw new IOException("No process id"); state.put("processId", process.get("id")); finished(); link(); attach(true); complete("Setup", obj()); System.out.println("STARTED " + state.get("processId"));
    }
    void link() throws Exception {
        Map<String,Object> a = appeal(); List<Map<String,Object>> matches = maps(a.get("subservices")).stream().filter(s -> value("processId").equals(clean(at(s, "camundaProcessInfo.id")))).toList();
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous process subservice");
        if (matches.size() == 1) { state.put("subGuid", matches.getFirst().get("guid")); save(); return; }
        pending("CREATE_SUBSERVICE"); Map<String,Object> s = map(call("POST", core() + "create/rbuModulesAppeals/subservices?mainId=" + value("appealId") + "&parentEntries=rbuModulesAppeals.subservices", obj("guid", UUID.randomUUID().toString(), "standardCode", at(settings, "context.standardCode"), "camundaProcessInfo", obj("id", value("processId"), "camundaBusinessInfoId", "rbu-acceptance-" + value("runId")), "xsdData", obj("dataType", settings.get("dataType")))));
        if (!present(s.get("guid"))) throw new IOException("No subservice guid"); state.put("subGuid", s.get("guid")); finished();
    }
    Map<String,Object> task(String stage) throws Exception {
        List<Map<String,Object>> tasks = maps(call("GET", engine() + "task?processInstanceId=" + value("processId"), null));
        List<Map<String,Object>> matches = tasks.stream().filter(t -> (settings.get("processKey") + "_" + stage).equals(t.get("taskDefinitionKey")) && value("processId").equals(t.get("processInstanceId"))).toList();
        if (matches.size() != 1) throw new IllegalStateException("Not at " + stage + "; use status and wait. No task completed."); return matches.getFirst();
    }
    void complete(String stage, Map<String,Object> vars) throws Exception { appeal(); Map<String,Object> t = task(stage); pending("COMPLETE_" + stage); call("POST", engine() + "task/" + t.get("id") + "/complete", obj("variables", variables(vars))); finished(); }
    void confirm() throws Exception {
        task("Confirm"); Map<String,Object> a = appeal(), draft = map(a.get("importDraft"));
        if (!Boolean.TRUE.equals(a.get("isValid")) || bool(draft.get("validationBlocked"))) throw new IllegalStateException("Validation did not pass");
        List<Map<String,Object>> matches = maps(a.get("subservices")).stream().filter(s -> value("processId").equals(clean(at(s, "camundaProcessInfo.id"))) && value("subGuid").equals(clean(s.get("guid")))).toList();
        if (matches.size() != 1) throw new IllegalStateException("Owned process subservice is not unique");
        Map<String,Object> sub = matches.getFirst(), xsd = copy(map(sub.get("xsdData"))), confirmation = obj("confirmApply", true);
        if (Set.of("TRADE", "PET").contains(domain())) {
            String fingerprint = clean(draft.get("confirmationFingerprint"));
            if (!"VALIDATED".equals(draft.get("status")) || !fingerprint.matches("[0-9a-f]{64}") || !fingerprint.equals(clean(at(xsd, "rbuImportConfirmation.confirmationFingerprint"))) || !clean(at(a, "importFile._id")).equals(clean(at(draft, "file.id"))) || !settings.get("dataType").equals(draft.get("dataType"))) throw new IllegalStateException("Validation fingerprint/file/type is stale; revalidate before confirming");
            confirmation.put("confirmationFingerprint", fingerprint);
        }
        xsd.put("rbuImportConfirmation", confirmation); pending("CONFIRM_DATA"); call("PUT", core() + "update/rbuModulesAppeals/subservices?mainId=" + value("appealId") + "&guid=" + sub.get("guid") + "&parentEntries=rbuModulesAppeals.subservices", obj("xsdData", xsd)); finished(); complete("Confirm", obj()); System.out.println("IMPORT CONFIRMED");
    }
    void status() throws Exception {
        if (!present(state.get("appealId"))) { System.out.println(Json.write(obj("pending", state.get("pending"), "marker", state.get("marker"), "message", "No confirmed test record ID; inspect server outcome before retry"))); return; }
        Map<String,Object> a = appeal(); Object tasks = List.of(), history = obj(), incidents = List.of();
        if (present(state.get("processId"))) { tasks = call("GET", engine() + "task?processInstanceId=" + value("processId"), null); history = call("GET", engine() + "history/process-instance/" + value("processId"), null); incidents = call("GET", engine() + "incident?processInstanceId=" + value("processId"), null); }
        write(run.resolve("status.json"), obj("appeal", a, "tasks", tasks, "process", history, "incidents", incidents));
        int count = 0; for (Map<String,Object> s : maps(a.get("subservices"))) if (present(state.get("processId")) && value("processId").equals(clean(at(s, "camundaProcessInfo.id")))) count += list(s.get("migrationCreatedRecords")).size();
        System.out.println(Json.write(obj("domain", domain(), "dataType", settings.get("dataType"), "appealId", state.get("appealId"), "pgsStatus", at(a, "status.code"), "applicationStatus", a.get("applicationStatus"), "isValid", a.get("isValid"), "recordCount", count, "processState", at(history, "state"), "tasks", tasks, "incidents", incidents, "pending", state.get("pending")))); System.out.println("Details: " + run.resolve("status.json"));
    }
    int launch(String command, String extra) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String runtime = repo.resolve("local-runner/target/classes") + File.pathSeparator + repo.resolve("local-runner/target/lib/*");
        if (command.equals("worker")) {
            if (!Set.of("", "--write").contains(extra)) throw new IllegalArgumentException("worker accepts only --write");
            Properties p = new Properties(); try (Reader in = Files.newBufferedReader(run.resolve("worker.properties"))) { p.load(in); }
            p.setProperty("worker.writesEnabled", String.valueOf(extra.equals("--write"))); Path active = run.resolve("worker-active.properties"); try (Writer out = Files.newBufferedWriter(active)) { p.store(out, "Local acceptance worker"); }
            return new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", runtime, "ru.evolenta.pgs.worker.Main", active.toString()).directory(repo.toFile()).inheritIO().start().waitFor();
        }
        Path lib = Path.of(extra).toAbsolutePath().normalize(); if (!Files.isDirectory(lib)) throw new IllegalArgumentException("SDK lib directory required");
        if (new ProcessBuilder(java, repo.resolve("scripts/VerifySdk.java").toString(), repo.resolve("docs/sdk-library-lock.json").toString(), lib.toString()).inheritIO().start().waitFor() != 0) throw new IllegalStateException("SDK verification failed");
        String sdk; try (var files = Files.list(lib)) { sdk = String.join(File.pathSeparator, files.filter(f -> f.toString().endsWith(".jar")).sorted().map(Path::toString).toList()); }
        Path classes = run.resolve("file-classes"); Files.createDirectories(classes); String cp = repo.resolve("local-runner/target/classes") + File.pathSeparator + sdk;
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err, "--release", "25", "-encoding", "UTF-8", "-cp", cp, "-d", classes.toString(), repo.resolve("tools/dev-cli/support/DevFileReader.java").toString());
        if (result != 0) return result;
        Path args = run.resolve("files-java.args"); List<String> launch = List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", classes + File.pathSeparator + cp, "ru.evolenta.pgs.dev.DevFileReader", run.resolve("files.json").toString(), run.resolve("settings.json").toString());
        Files.writeString(args, String.join("\n", launch.stream().map(x -> "\"" + x.replace('\\', '/') + "\"").toList()));
        return new ProcessBuilder(java, "@" + args).directory(repo.toFile()).inheritIO().start().waitFor();
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("dev.cmd build | init NAME ADVERTISING|TRADE|PET CONTEXT_JSON [НТО|Рынки|Ярмарки] | deploy NAME | create NAME ROIV|OMSU | upload NAME XLSX [--session|--token] | upload-extra NAME FILE [--session|--token] | upload-documents NAME DIRECTORY [--session|--token] | attach NAME | start NAME | status NAME | confirm NAME | next NAME [--repeat] | worker NAME [--write] | files NAME SDK_LIB"); return; }
        DevCli cli = new DevCli(Path.of(""), args[1], new HttpTransport());
        RunLock lock = null;
        try {
            if (Set.of("init", "deploy", "create", "upload", "upload-extra", "upload-documents", "attach", "start", "confirm", "next").contains(args[0])) { lock = cli.lock(); cli.settings = read(cli.run.resolve("settings.json")); cli.state = read(cli.run.resolve("state.json")); }
            if (!args[0].equals("init") && cli.settings.isEmpty()) throw new IllegalStateException("Run init first");
            switch (args[0]) {
                case "init" -> { if (args.length < 4 || args.length > 5) throw new IllegalArgumentException("init NAME DOMAIN CONTEXT_JSON [TYPE]"); cli.init(args[2], Path.of(args[3]), args.length == 5 ? args[4] : ""); }
                case "deploy" -> cli.deploy();
                case "create" -> cli.create(args.length > 2 ? args[2] : "ROIV");
                case "upload", "upload-extra", "upload-documents" -> {
                    if (args.length < 3 || args.length > 4 || System.console() == null) throw new IllegalStateException("Run upload interactively in CMD; session/token is entered hidden");
                    String mode = args.length == 4 ? args[3] : "--session";
                    if (!Set.of("--session", "--token").contains(mode)) throw new IllegalArgumentException("Choose --session or --token");
                    char[] secret = System.console().readPassword(mode.equals("--session") ? "DEV Cookie header (hidden): " : "DEV user access token (hidden): "); if (secret == null) throw new IllegalArgumentException("No credentials entered");
                    try { Credentials credentials = mode.equals("--session") ? Credentials.session(new String(secret)) : Credentials.token(new String(secret)); if (args[0].equals("upload-documents")) cli.uploadDocuments(Path.of(args[2]), credentials); else cli.upload(Path.of(args[2]), credentials, args[0].equals("upload-extra")); } finally { Arrays.fill(secret, '\0'); }
                }
                case "attach" -> cli.attach(); case "start" -> cli.start(); case "status" -> cli.status(); case "confirm" -> cli.confirm();
                case "next" -> { if (args.length > 3 || args.length == 3 && !args[2].equals("--repeat")) throw new IllegalArgumentException("next accepts only --repeat"); cli.complete("Inspect", obj("rbuRepeat", args.length == 3)); }
                case "worker", "files" -> System.exit(cli.launch(args[0], args.length > 2 ? args[2] : ""));
                default -> throw new IllegalArgumentException("Unknown command");
            }
        } catch (Exception e) { cli.recordFailure(e); System.err.println("FAILED: " + e.getMessage()); if (present(cli.state.get("pending"))) System.err.println("Unconfirmed mutation remains in state.json; do not clear/retry blindly."); System.exit(1); }
        finally { if (lock != null) lock.close(); }
    }
}

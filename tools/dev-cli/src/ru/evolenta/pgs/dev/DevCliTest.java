package ru.evolenta.pgs.dev;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import ru.evolenta.pgs.worker.Json;
import static ru.evolenta.pgs.worker.Util.*;

/** CLI contracts use synthetic data and a fake transport. They never contact DEV. */
public final class DevCliTest {
    static int passed;
    interface Action { void run() throws Exception; }
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); passed++; }
    static void rejects(Action action, String label) throws Exception { try { action.run(); } catch (Exception expected) { passed++; return; } throw new AssertionError(label); }
    static final String OWNER = "111111111111111111111111", APPEAL = "222222222222222222222222", PROCESS = "33333333-3333-4333-8333-333333333333";
    static final class Fake implements DevCli.Transport {
        Map<String,Object> appeal = obj(), started = obj(); String processKey, stage = "Setup";
        int mutations, uploads, documentGroupsCreated, rootDocumentWrites; boolean loseCreateResponse, wrongFileOwner, ignoreDocuments, wrongTaskOwner, rejectUpload;
        byte[] multipart; DevCli.Credentials credentials;
        public Object send(String method, String url, byte[] bytes, String type, DevCli.Credentials auth) throws Exception {
            if (!method.equals("GET")) mutations++;
            if (url.endsWith("/deployment/create")) return obj("id", "deployment-test");
            if (url.equals("https://localhost/api/v1/storage/upload")) {
                if (rejectUpload) throw new DevCli.HttpFailure(401, method, url);
                uploads++; multipart = bytes; credentials = auth;
                var matcher = Pattern.compile("filename=\"([^\"]+)\"").matcher(new String(bytes, StandardCharsets.UTF_8));
                if (!matcher.find()) throw new AssertionError("Filename missing from multipart"); String filename = matcher.group(1);
                return obj("_id", String.format("%024x", uploads + 20), "guid", UUID.randomUUID().toString(), "id", "SIER/rbuModulesAppeals/" + APPEAL + "/123_" + filename,
                    "entryId", wrongFileOwner ? OWNER : APPEAL, "entryName", "rbuModulesAppeals", "name", filename, "originalName", "123_" + filename, "storage", "S3");
            }
            Map<String,Object> body = bytes == null ? obj() : map(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            if (url.endsWith("create/rbuModulesAppeals")) {
                if (loseCreateResponse) throw new IOException("Simulated response lost after create");
                appeal = copy(body); appeal.putAll(obj("_id", APPEAL, "guid", "44444444-4444-4444-8444-444444444444", "subservices", new ArrayList<>())); return copy(appeal);
            }
            if (url.contains("find/rbuModulesAppeals")) return copy(appeal);
            if (url.contains("update/rbuModulesAppeals?")) { if (body.containsKey("documents")) rootDocumentWrites++; body.remove("documents"); appeal.putAll(copy(body)); return copy(appeal); }
            if (url.contains("create/rbuModulesAppeals/documents?")) {
                documentGroupsCreated++; Map<String,Object> group = copy(body); group.put("guid", UUID.randomUUID().toString());
                if (!ignoreDocuments) { var groups = new ArrayList<Object>(list(appeal.get("documents"))); groups.add(group); appeal.put("documents", groups); } return copy(group);
            }
            if (url.contains("process-definition/key/")) { started = copy(body); return obj("id", PROCESS); }
            if (url.contains("create/rbuModulesAppeals/subservices")) { list(appeal.get("subservices")).add(copy(body)); return copy(body); }
            if (url.contains("update/rbuModulesAppeals/subservices")) { map(list(appeal.get("subservices")).getFirst()).putAll(copy(body)); return obj(); }
            if (url.contains("history/process-instance")) return obj("state", stage.equals("End") ? "COMPLETED" : "ACTIVE");
            if (url.contains("incident?")) return List.of();
            if (url.contains("/task?")) return stage.equals("End") ? List.of() : List.of(obj("id", "owned-task", "taskDefinitionKey", processKey + "_" + stage, "processInstanceId", wrongTaskOwner ? "another-process" : PROCESS));
            if (url.endsWith("/task/owned-task/complete")) { stage = stage.equals("Setup") ? "Confirm" : stage.equals("Confirm") ? "Inspect" : bool(at(body, "variables.rbuRepeat.value")) ? "Inspect" : "End"; return obj(); }
            throw new AssertionError("Unexpected synthetic request: " + method + " " + url);
        }
    }
    record Fixture(Path home, DevCli cli, Fake fake, Path input) { }
    static Fixture fixture(Path source, String domain, String type, String name) throws Exception {
        Path home = Files.createTempDirectory("rbu-dev-cli-test-");
        for (String file : List.of("config/worker.dev.properties", "tools/dev-cli/templates/advertising-acceptance.bpmn", "tools/dev-cli/templates/trade-acceptance.bpmn", "tools/dev-cli/templates/pet-acceptance.bpmn")) {
            Path to = home.resolve(file); Files.createDirectories(to.getParent()); Files.copy(source.resolve(file), to);
        }
        Path context = home.resolve("context.local.json"); DevCli.write(context, context()); Fake fake = new Fake(); DevCli cli = new DevCli(home, name, fake); cli.init(domain, context, type); fake.processKey = clean(cli.settings.get("processKey"));
        String prefix = "Pgs" + (domain.equals("TRADE") ? "Trade" : domain.equals("PET") ? "Pet" : "Advertising");
        String topic = clean(cli.settings.get("topic")), suffix = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + cli.value("runId").substring(0, 8);
        check(topic.equals("pgs-data-processing-v1-" + domain.toLowerCase(Locale.ROOT) + "-" + name + "-" + cli.value("runId").substring(0, 8)), "Generated topic preserves canonical base and run isolation");
        check(fake.processKey.equals(prefix + "ImportV1_" + suffix), "Generated process key preserves canonical base and run isolation");
        Properties worker = new Properties(); try (var reader = Files.newBufferedReader(cli.run.resolve("worker.properties"))) { worker.load(reader); }
        check(topic.equals(worker.getProperty("worker.topic")), "Worker subscribes only to its generated topic");
        bpmn(cli.run.resolve("acceptance.bpmn"), topic, prefix, "_" + suffix);
        Path input = home.resolve("input.xlsx"); Files.writeString(input, "synthetic client transport bytes; parser not invoked"); return new Fixture(home, cli, fake, input);
    }
    static void bpmn(Path path, String topic, String prefix, String suffix) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(path.toFile());
        var nodes = document.getElementsByTagName("*"); Set<String> ids = new HashSet<>(); boolean unique = true;
        for (int index = 0; index < nodes.getLength(); index++) { var element = (Element) nodes.item(index); if (element.hasAttribute("id")) unique &= ids.add(element.getAttribute("id")); }
        check(unique, "BPMN IDs remain unique: " + path.getFileName());
        List<String> missing = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) { var element = (Element) nodes.item(index); for (String field : List.of("sourceRef", "targetRef", "bpmnElement", "default")) if (element.hasAttribute(field) && !ids.contains(element.getAttribute(field))) missing.add(field + "=" + element.getAttribute(field)); }
        check(missing.isEmpty(), "All sequence/diagram/default references resolve: " + missing);
        String bpmn = "http://www.omg.org/spec/BPMN/20100524/MODEL", camunda = "http://camunda.org/schema/1.0/bpmn";
        var processes = document.getElementsByTagNameNS(bpmn, "process"); Set<String> keys = new HashSet<>();
        Map<String,List<String>> operations = Map.of("Import", List.of("PARSE_VALIDATE", "IMPORT_APPLY", "IMPORT_FINALIZE"), "Validation", List.of("PARSE_VALIDATE"), "Rollback", List.of("ROLLBACK_PREPARE", "ROLLBACK_EXECUTE", "ROLLBACK_FINALIZE"));
        for (int index = 0; index < processes.getLength(); index++) {
            var process = (Element) processes.item(index); String processKey = process.getAttribute("id"); keys.add(processKey); var tasks = process.getElementsByTagNameNS(bpmn, "serviceTask"); List<String> actual = new ArrayList<>();
            for (int taskIndex = 0; taskIndex < tasks.getLength(); taskIndex++) { var task = (Element) tasks.item(taskIndex); check(topic.equals(task.getAttributeNS(camunda, "topic")), "Every external task uses the expected topic"); var inputs = task.getElementsByTagNameNS(camunda, "inputParameter"); for (int inputIndex = 0; inputIndex < inputs.getLength(); inputIndex++) { var input = (Element) inputs.item(inputIndex); if (input.getAttribute("name").equals("rbuOperation")) actual.add(input.getTextContent().trim()); } }
            String kind = operations.keySet().stream().filter(name -> processKey.equals(prefix + name + "V1" + suffix)).findFirst().orElse("");
            check(!kind.isEmpty() && actual.equals(operations.get(kind)), "Canonical process key retains its operation sequence: " + processKey);
        }
        check(keys.equals(Set.of(prefix + "ImportV1" + suffix, prefix + "ValidationV1" + suffix, prefix + "RollbackV1" + suffix)), "All three canonical process definitions are present");
    }
    static Map<String,Object> context() {
        return obj("coreUrl", "https://localhost/core/", "engineUrl", "https://localhost/engine-rest/", "uploadUrl", "https://localhost/api/v1/storage/upload", "uiBaseUrl", "https://localhost", "cloudConfigUrl", "https://localhost/config/service/default", "storageEndpoint", "https://localhost/storage", "standardCode", "41059", "apiIntegrationVerified", true,
            "organization", obj("_id", OWNER, "name", "Синтетическая организация"), "region", obj("_id", "555555555555555555555555"), "municipality", obj("_id", "666666666666666666666666"), "card", obj("_id", "777777777777777777777777"));
    }
    static void ready(Fixture f, String profile) throws Exception { f.cli.deploy(); f.cli.create(profile); f.cli.upload(f.input, DevCli.Credentials.session("SESSION=synthetic-session"), false); f.cli.start(); }
    static void validated(Fixture f) {
        f.fake.appeal.put("isValid", true); Map<String,Object> draft = obj("status", "VALIDATED", "validationBlocked", false, "dataType", f.cli.settings.get("dataType"), "file", obj("id", at(f.cli.state, "file._id")), "confirmationFingerprint", "a".repeat(64)); f.fake.appeal.put("importDraft", draft);
        map(list(f.fake.appeal.get("subservices")).getFirst()).put("xsdData", obj("dataType", f.cli.settings.get("dataType"), "rbuImportConfirmation", obj("confirmApply", false, "confirmationFingerprint", "a".repeat(64))));
    }
    static void noSavedSecret(Fixture f, String secret) throws Exception {
        try (var files = Files.walk(f.cli.run)) { for (Path file : files.filter(Files::isRegularFile).toList()) check(!Files.readString(file).contains(secret), "Credentials never persisted: " + file.getFileName()); }
    }
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        for (String domain : List.of("Advertising", "Trade", "Pet")) bpmn(source.resolve("tools/dev-cli/templates/" + domain.toLowerCase(Locale.ROOT) + "-acceptance.bpmn"), "pgs-data-processing-v1", "Pgs" + domain, "");
        bpmn(source.resolve("bpmn/trade-acceptance.bpmn"), "pgs-data-processing-v1", "PgsTrade", "");
        Fixture isolatedOne = fixture(source, "TRADE", "НТО", "isolated01"), isolatedTwo = fixture(source, "TRADE", "НТО", "isolated01");
        check(!isolatedOne.cli.settings.get("topic").equals(isolatedTwo.cli.settings.get("topic")), "Separate runs never share an external-task topic");
        check(!isolatedOne.cli.settings.get("processKey").equals(isolatedTwo.cli.settings.get("processKey")), "Separate runs never share a process definition key");
        for (String profile : List.of("ROIV", "OMSU")) {
            Fixture pet = fixture(source, "PET", "", "pet01");
            ready(pet, profile);
            check("Мой питомец".equals(pet.fake.appeal.get("dataType")), "PET data type retained");
            check("PET".equals(at(pet.fake.started, "variables.rbuDomain.value")), "PET domain retained");
            validated(pet); pet.cli.confirm();
            check("a".repeat(64).equals(at(list(pet.fake.appeal.get("subservices")).getFirst(), "xsdData.rbuImportConfirmation.confirmationFingerprint")), "PET confirmation has current fingerprint");
        }
        for (String type : List.of("НТО", "Рынки", "Ярмарки")) for (String profile : List.of("ROIV", "OMSU")) {
            Fixture f = fixture(source, "TRADE", type, "trade01");
            check(f.fake.mutations == 0, "init is local"); check(Files.readString(f.cli.run.resolve("acceptance.bpmn")).contains(clean(f.cli.settings.get("topic"))), "BPMN topic isolated");
            ready(f, profile); check(type.equals(f.fake.appeal.get("dataType")), "Trade type in test record"); check("process".equals(at(f.fake.appeal, "status.code")), "No artificial complete status");
            check("TRADE".equals(at(f.fake.started, "variables.rbuDomain.value")), "Trade process domain"); check(profile.equals(at(f.fake.started, "variables.rbuProfile.value")), "Profile in process variables");
            check(type.equals(at(list(f.fake.appeal.get("subservices")).getFirst(), "xsdData.dataType")), "Trade type in linked subservice");
            int before = f.fake.mutations; rejects(f.cli::confirm, "Invalid validation rejected"); check(before == f.fake.mutations, "Invalid validation made no mutation");
            validated(f); f.cli.confirm(); check(f.fake.stage.equals("Inspect"), "Current trade confirmation advances"); check("a".repeat(64).equals(at(list(f.fake.appeal.get("subservices")).getFirst(), "xsdData.rbuImportConfirmation.confirmationFingerprint")), "Fresh fingerprint persisted");
            f.cli.complete("Inspect", obj("rbuRepeat", true)); check(f.fake.stage.equals("Inspect"), "Explicit repeat stays at checkpoint"); f.cli.complete("Inspect", obj("rbuRepeat", false)); check(f.fake.stage.equals("End"), "Continue reaches end"); f.cli.status(); check(Files.exists(f.cli.run.resolve("status.json")), "Status snapshot created");
        }
        Fixture advertising = fixture(source, "ADVERTISING", "", "ads01"); ready(advertising, "ROIV"); validated(advertising); map(advertising.fake.appeal.get("importDraft")).remove("confirmationFingerprint"); advertising.cli.confirm(); check("ADVERTISING".equals(at(advertising.fake.started, "variables.rbuDomain.value")), "Advertising remains supported");
        Fixture stale = fixture(source, "TRADE", "НТО", "stale01"); ready(stale, "ROIV"); validated(stale); Map<String,Object> sub = map(list(stale.fake.appeal.get("subservices")).getFirst());
        map(at(sub, "xsdData.rbuImportConfirmation")).put("confirmationFingerprint", "b".repeat(64)); int before = stale.fake.mutations; rejects(stale.cli::confirm, "Stale fingerprint rejected"); check(before == stale.fake.mutations, "Stale fingerprint made no mutation");
        validated(stale); map(stale.fake.appeal.get("importFile")).put("_id", "888888888888888888888888"); before = stale.fake.mutations; rejects(stale.cli::confirm, "Changed workbook rejected"); check(before == stale.fake.mutations, "Changed workbook made no mutation");
        Fixture wrongType = fixture(source, "TRADE", "НТО", "type01"); ready(wrongType, "ROIV"); validated(wrongType); map(wrongType.fake.appeal.get("importDraft")).put("dataType", "Рынки"); rejects(wrongType.cli::confirm, "Changed type rejected");
        Fixture identity = fixture(source, "TRADE", "НТО", "owner01"); identity.cli.create("ROIV"); String guid = clean(identity.fake.appeal.get("guid"));
        for (String field : List.of("unitId", "guid", "testPurpose")) { Object old = identity.fake.appeal.put(field, "foreign"); before = identity.fake.mutations; rejects(identity.cli::attach, "Foreign identity rejected: " + field); check(before == identity.fake.mutations, "Foreign identity made no mutation"); identity.fake.appeal.put(field, old); }
        map(identity.fake.appeal.get("unit")).put("id", "foreign"); rejects(identity.cli::attach, "Nested owner mismatch rejected"); map(identity.fake.appeal.get("unit")).put("id", OWNER); check(guid.equals(identity.cli.value("appealGuid")), "Stored guid not overwritten");
        Fixture taskOwner = fixture(source, "TRADE", "НТО", "task01"); ready(taskOwner, "ROIV"); taskOwner.fake.wrongTaskOwner = true; before = taskOwner.fake.mutations; rejects(() -> taskOwner.cli.complete("Confirm", obj()), "Another process task rejected"); check(before == taskOwner.fake.mutations, "Another task made no mutation");
        Fixture lost = fixture(source, "TRADE", "НТО", "lost01"); lost.fake.loseCreateResponse = true; rejects(() -> lost.cli.create("ROIV"), "Lost create response surfaced"); check("CREATE_APPEAL".equals(lost.cli.state.get("pending")), "Uncertain create retained"); before = lost.fake.mutations; rejects(() -> lost.cli.create("ROIV"), "Blind retry blocked"); check(before == lost.fake.mutations, "No duplicate create request");
        DevCli restarted = new DevCli(lost.home, "lost01", lost.fake); restarted.recordFailure(new DevCli.HttpFailure(401, "POST", "https://localhost/api/upload")); check(present(restarted.state.get("pending")), "Unrelated auth rejection cannot clear old pending"); lost.cli.recordFailure(new DevCli.HttpFailure(401, "GET", "https://localhost/core/find")); check(present(lost.cli.state.get("pending")), "Read auth rejection cannot clear mutation pending"); lost.cli.status();
        Fixture auth = fixture(source, "TRADE", "НТО", "auth01"); auth.cli.create("ROIV"); auth.fake.rejectUpload = true; try { auth.cli.upload(auth.input, DevCli.Credentials.session("SESSION=synthetic-session"), false); throw new AssertionError("401 ignored"); } catch (DevCli.HttpFailure e) { auth.cli.recordFailure(e); } check(!present(auth.cli.state.get("pending")), "Current explicit denied upload may be retried after login");
        auth.fake.rejectUpload = false; auth.cli.upload(auth.input, DevCli.Credentials.session("SESSION=never-save-session; XSRF-TOKEN=a%2Bb%3D"), false); check(auth.fake.credentials.headers().get("Cookie").contains("never-save-session"), "Session uses Cookie"); check(!auth.fake.credentials.headers().containsKey("Authorization"), "Session never adds Bearer null"); check("a+b=".equals(auth.fake.credentials.headers().get("X-XSRF-TOKEN")), "XSRF decoded without losing plus");
        check(new String(auth.fake.multipart, StandardCharsets.UTF_8).contains("name=\"entryId\""), "Multipart owns entryId"); noSavedSecret(auth, "never-save-session");
        check("Bearer synthetic-token".equals(DevCli.Credentials.token("Bearer synthetic-token").headers().get("Authorization")), "Explicit token mode preserved"); check(!DevCli.Credentials.token("synthetic-token").headers().containsKey("Cookie"), "Token does not imply session");
        rejects(() -> DevCli.Credentials.token("null"), "Literal null token rejected"); rejects(() -> DevCli.Credentials.token("Bearer undefined"), "Undefined token rejected"); rejects(() -> DevCli.Credentials.session("SESSION=x\r\nInjected: value"), "Header injection rejected"); check(!DevCli.Credentials.session("SESSION=never-print").toString().contains("never-print"), "Credentials toString redacted");
        Fixture foreignFile = fixture(source, "TRADE", "НТО", "file01"); foreignFile.cli.create("ROIV"); foreignFile.fake.wrongFileOwner = true; rejects(() -> foreignFile.cli.upload(foreignFile.input, DevCli.Credentials.session("SESSION=test"), false), "Another record file rejected"); check("UPLOAD_FILE".equals(foreignFile.cli.state.get("pending")) && !present(foreignFile.cli.state.get("file")), "Foreign upload metadata not attached or silently retried");
        Fixture extra = fixture(source, "TRADE", "НТО", "extra01"); extra.cli.create("ROIV"); extra.cli.upload(extra.input, DevCli.Credentials.session("SESSION=test"), false); Path docs = extra.home.resolve("documents"); Files.createDirectories(docs); Files.writeString(docs.resolve("fixture.pdf"), "synthetic PDF bytes"); Files.writeString(docs.resolve("fixture.jpg"), "synthetic JPEG bytes"); Files.writeString(docs.resolve("ignored.xlsx"), "not an annex"); extra.cli.uploadDocuments(docs, DevCli.Credentials.session("SESSION=test"));
        check(maps(extra.cli.state.get("extraFiles")).size() == 2, "Attachment directory selects only supported annexes"); check(maps(extra.fake.appeal.get("documents")).isEmpty(), "Document group waits for its process subservice"); check(map(at(DevCli.read(extra.cli.run.resolve("files.json")), "files")).size() == 3, "Main and attachments all allowlisted"); int uploads = extra.fake.uploads; extra.cli.uploadDocuments(docs, DevCli.Credentials.session("SESSION=test")); check(uploads == extra.fake.uploads, "Completed batch upload resumed without duplicates");
        extra.cli.deploy(); extra.cli.start(); Map<String,Object> group = maps(extra.fake.appeal.get("documents")).getFirst(); check(group.get("groupName").equals("Файлы миграции"), "Trade attachment group accepted by domain"); check(extra.cli.value("subGuid").equals(group.get("subserviceGuid")), "Document group linked to the owned process subservice"); check(maps(group.get("files")).size() == 2, "All attachments saved through documents subresource"); check(extra.fake.documentGroupsCreated == 1 && extra.fake.rootDocumentWrites == 0, "No unsupported root documents patch");
        Fixture ignored = fixture(source, "TRADE", "НТО", "ignored01"); ignored.cli.deploy(); ignored.cli.create("ROIV"); ignored.cli.upload(ignored.input, DevCli.Credentials.session("SESSION=test"), false); ignored.cli.upload(docs.resolve("fixture.pdf"), DevCli.Credentials.session("SESSION=test"), true); ignored.fake.ignoreDocuments = true; rejects(ignored.cli::start, "Ignored document group detected by readback"); check("CREATE_DOCUMENT_GROUP".equals(ignored.cli.state.get("pending")), "Unconfirmed document group remains pending"); check(ignored.fake.stage.equals("Setup"), "Validation cannot start without confirmed attachment group");
        Fixture lock = fixture(source, "ADVERTISING", "", "lock01"); try (var first = lock.cli.lock()) { rejects(() -> { try (var second = lock.cli.lock()) { } }, "Concurrent commands rejected"); }
        Map<String,Object> unsafe = context(); unsafe.put("accessToken", "never-copy-this"); Path unsafeContext = lock.home.resolve("unsafe.local.json"); DevCli.write(unsafeContext, unsafe); DevCli unsafeCli = new DevCli(lock.home, "unsafe01", new Fake()); rejects(() -> unsafeCli.init("TRADE", unsafeContext, "НТО"), "Credentials in context rejected"); check(!Files.exists(unsafeCli.run), "Unsafe context did not initialise run");
        System.out.println("PASS CMD client " + passed + " assertions; fake transport only, no DEV requests");
    }
}

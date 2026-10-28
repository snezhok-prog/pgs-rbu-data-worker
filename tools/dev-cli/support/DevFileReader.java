package ru.evolenta.pgs.dev;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import ru.evolenta.pgs.worker.Json;
import static ru.evolenta.pgs.worker.Util.*;

/** Read-only loopback adapter for uploaded files belonging to one acceptance run. */
public final class DevFileReader {
    static URI endpoint(String value, boolean http) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || !(uri.getScheme().equals("https") || http && uri.getScheme().equals("http"))) throw new IllegalArgumentException("Invalid configured endpoint");
        return uri;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Manifest and settings paths required");
        Path manifestPath = Path.of(args[0]); Map<String,Object> settings = map(Json.parse(Files.readString(Path.of(args[1]))));
        Map<String,Object> context = map(settings.get("context")), initial = map(Json.parse(Files.readString(manifestPath)));
        String runId = clean(initial.get("runId")), appealId = clean(initial.get("appealId"));
        if (!runId.matches("[a-f0-9-]{36}") || !appealId.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("Upload files before starting the adapter");
        boolean httpAllowed = bool(context.get("allowHttp"));
        URI configUri = endpoint(clean(context.get("cloudConfigUrl")), httpAllowed), expectedStorage = endpoint(clean(context.get("storageEndpoint")), httpAllowed);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        var response = http.send(HttpRequest.newBuilder(configUri).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("DEV storage configuration unavailable: HTTP " + response.statusCode());
        Map<String,Object> properties = new HashMap<>(); List<Map<String,Object>> sources = maps(map(Json.parse(response.body())).get("propertySources"));
        for (int i = sources.size() - 1; i >= 0; i--) properties.putAll(map(sources.get(i).get("source")));
        URI storage = endpoint(clean(properties.get("app.filestorage.s3.url")), httpAllowed);
        if (!storage.equals(expectedStorage)) throw new IllegalStateException("Storage endpoint differs from explicit run configuration");
        String access = clean(properties.get("app.filestorage.s3.access.key")), secret = clean(properties.get("app.filestorage.s3.secret.key"));
        if (access.isEmpty() || secret.isEmpty()) throw new IllegalStateException("Storage credentials absent from DEV configuration");
        S3Client client = S3Client.builder().endpointOverride(storage).region(Region.of(clean(properties.getOrDefault("app.filestorage.s3.region", "us-east-1"))))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).forcePathStyle(true)
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
        String bucket = clean(properties.getOrDefault("app.filestorage.s3.bucket", "sier")); int port = integer(context.getOrDefault("fileAdapterPort", 18085));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid file adapter port");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/files/", exchange -> {
            try {
                String id = exchange.getRequestURI().getPath().substring(7);
                if (!exchange.getRequestMethod().equals("GET") || !id.matches("[a-f0-9]{24}")) { exchange.sendResponseHeaders(404, -1); return; }
                Map<String,Object> manifest = map(Json.parse(Files.readString(manifestPath)));
                if (!runId.equals(manifest.get("runId")) || !appealId.equals(manifest.get("appealId"))) throw new IllegalStateException("Run manifest identity changed");
                Map<String,Object> file = map(map(manifest.get("files")).get(id));
                if (!id.equals(file.get("_id")) || !appealId.equals(file.get("entryId")) || !"rbuModulesAppeals".equals(file.get("entryName")) || !present(file.get("id"))) { exchange.sendResponseHeaders(404, -1); return; }
                var request = GetObjectRequest.builder().bucket(bucket).key(clean(file.get("id"))).build();
                try (var stream = client.getObject(request)) {
                    byte[] bytes = readBounded(stream, 20L * 1024 * 1024); String hash = sha(bytes);
                    if (!hash.equals(file.get("sha256"))) throw new IllegalStateException("Stored file differs from the uploaded local file");
                    exchange.getResponseHeaders().set("Content-Type", clean(file.getOrDefault("contentType", "application/octet-stream")));
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                    System.out.println(java.time.Instant.now() + " storage-read file=" + id + " bytes=" + bytes.length + " sha256=" + hash);
                }
            } catch (Exception error) {
                System.err.println("storage-read failed: " + error.getClass().getSimpleName());
                try { exchange.sendResponseHeaders(502, -1); } catch (Exception ignored) { }
            } finally { exchange.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); client.close(); })); server.start();
        System.out.println("Read-only storage adapter on 127.0.0.1:" + port + "; uploaded files are allowlisted for one run. Ctrl+C to stop.");
    }
}

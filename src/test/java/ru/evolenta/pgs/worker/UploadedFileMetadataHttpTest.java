package ru.evolenta.pgs.worker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

class UploadedFileMetadataHttpTest {
    @Test void allowsMetadataReadsAndRejectsAllMetadataWritesBeforeNetwork() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "{\"_id\":\"file\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
        try {
            Properties properties = new Properties();
            for (String operation : new String[]{"find", "create", "update", "updateSub", "delete"}) {
                properties.setProperty("pgs." + operation, operation + "/{collection}?mainId={id}");
            }
            HttpPgsApi api = new HttpPgsApi(new HttpJson("http://127.0.0.1:" + server.getAddress().getPort(), null, 1000, true), properties);
            assertEquals("file", id(api.find("uploadedFiles", "file")));
            assertThrows(IllegalArgumentException.class, () -> api.create("uploadedFiles", obj()));
            assertThrows(IllegalArgumentException.class, () -> api.update("uploadedFiles", "file", "guid", obj()));
            assertThrows(IllegalArgumentException.class, () -> api.updateSub("uploadedFiles", "file", "guid", "parent", obj()));
            assertThrows(IllegalArgumentException.class, () -> api.delete("uploadedFiles", "file", "guid"));
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }
}

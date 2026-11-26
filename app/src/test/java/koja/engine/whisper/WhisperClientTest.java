package koja.engine.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WhisperClientTest {
    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/inference", ex -> {
            received.set(ex.getRequestHeaders().getFirst("Content-Type") + "\n"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] body = (status == 200 ? WhisperTranscriberTest.json("japanese", "テスト", 0, 1) : "{\"error\":\"bad\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WhisperClient client(HttpClient http) {
        return new WhisperClient(http, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    void sendsMultipartAndReturnsBody() throws Exception {
        try (HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()) {
            String body = client(http).transcribe(new byte[] {'R', 'I', 'F', 'F'}, "ko");
            assertEquals("テスト", WhisperJson.parse(body, 0, 1000).text());
        }
        String req = received.get();
        String boundary = req.substring(req.indexOf("boundary=") + 9, req.indexOf('\n'));
        assertTrue(req.contains("--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nko\r\n"));
        assertTrue(req.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"));
        assertTrue(req.contains("name=\"temperature\"\r\n\r\n0\r\n"));
        assertTrue(req.contains("filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\nRIFF\r\n--" + boundary + "--\r\n"));
    }

    @Test
    void nonOkStatusFails() {
        status = 500;
        try (HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()) {
            IOException e = assertThrows(IOException.class, () -> client(http).transcribe(new byte[0], "auto"));
            assertTrue(e.getMessage().contains("500"));
        }
    }
}

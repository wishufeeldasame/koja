package koja.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoopbackReceiverTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static int get(URI uri) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void rejectsWrongStateThenAcceptsRightOne() throws Exception {
        try (LoopbackReceiver r = new LoopbackReceiver("good-state")) {
            URI base = r.redirectUri();
            assertTrue(base.toString().startsWith("http://127.0.0.1:"), base.toString());
            assertEquals(404, get(base.resolve("/favicon.ico")));
            assertEquals(400, get(base.resolve("/?code=evil&state=bad-state")));
            assertEquals(400, get(base.resolve("/?code=evil")));
            assertEquals(400, get(base.resolve("/?state=good-state")));
            assertEquals(200, get(base.resolve("/?code=4%2Fabc&state=good-state")));
            assertEquals("4/abc", r.awaitCode(Duration.ofSeconds(5)));
        }
    }

    @Test
    void userDenied() throws Exception {
        try (LoopbackReceiver r = new LoopbackReceiver("s")) {
            assertEquals(200, get(r.redirectUri().resolve("/?error=access_denied&state=s")));
            IOException e = assertThrows(IOException.class, () -> r.awaitCode(Duration.ofSeconds(5)));
            assertTrue(e.getMessage().contains("access_denied"));
        }
    }

    @Test
    void timesOut() throws Exception {
        try (LoopbackReceiver r = new LoopbackReceiver("s")) {
            assertThrows(IOException.class, () -> r.awaitCode(Duration.ofMillis(50)));
        }
    }
}

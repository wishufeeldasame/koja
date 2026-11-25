package koja.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import koja.runtime.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 구글 대신 로컬 가짜 토큰 서버를 쓴다. */
class GoogleAuthTest {
    static final class MemoryStore implements SecretStore {
        final Map<String, String> map = new HashMap<>();

        @Override
        public Optional<String> get(String name) {
            return Optional.ofNullable(map.get(name));
        }

        @Override
        public void put(String name, String value) {
            map.put(name, value);
        }

        @Override
        public void delete(String name) {
            map.remove(name);
        }
    }

    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private final MemoryStore store = new MemoryStore();
    private HttpServer tokenServer;
    private GoogleAuth auth;
    private volatile int status = 200;
    private volatile String response;

    @BeforeEach
    void start() throws IOException {
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", ex -> {
            tokenRequests.add(LoopbackReceiver.query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        tokenServer.start();
        URI base = URI.create("http://127.0.0.1:" + tokenServer.getAddress().getPort());
        auth = new GoogleAuth(new GoogleAuth.Client("cid", "csecret"), store, HttpClient.newHttpClient(),
                base.resolve("/auth"), base.resolve("/token"));
    }

    @AfterEach
    void stop() {
        tokenServer.stop(0);
    }

    @Test
    void authorizationUriHasPkceAndScope() {
        Map<String, String> q = LoopbackReceiver.query(
                auth.authorizationUri(URI.create("http://127.0.0.1:5000/"), "chal", "st").getRawQuery());
        assertEquals("cid", q.get("client_id"));
        assertEquals("http://127.0.0.1:5000/", q.get("redirect_uri"));
        assertEquals(GoogleAuth.SCOPE, q.get("scope"));
        assertEquals("chal", q.get("code_challenge"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals("st", q.get("state"));
        assertEquals("code", q.get("response_type"));
        assertEquals("offline", q.get("access_type"));
    }

    @Test
    void signInExchangesCodeWithVerifierAndStoresRefreshToken() throws Exception {
        response = "{\"access_token\":\"at1\",\"expires_in\":3600,\"refresh_token\":\"rt1\"}";
        // 브라우저 대신: 인가 주소의 state·redirect_uri로 루프백에 코드를 보낸다.
        String[] challenge = new String[1];
        CompletableFuture<Integer> callbackStatus = new CompletableFuture<>();
        String token = auth.signIn(uri -> {
            Map<String, String> q = LoopbackReceiver.query(uri.getRawQuery());
            challenge[0] = q.get("code_challenge");
            URI callback = URI.create(q.get("redirect_uri") + "?code=the-code&state=" + q.get("state"));
            Thread.ofVirtual().start(() -> {
                try {
                    callbackStatus.complete(LoopbackReceiverTest.get(callback));
                } catch (Exception e) {
                    callbackStatus.completeExceptionally(e);
                }
            });
        }, Duration.ofSeconds(5));

        assertEquals(200, callbackStatus.get(5, TimeUnit.SECONDS)); // 브라우저가 안내 페이지를 받았다
        assertEquals("at1", token);
        assertEquals("rt1", store.map.get(GoogleAuth.refreshKey("cid")));
        Map<String, String> req = tokenRequests.getFirst();
        assertEquals("authorization_code", req.get("grant_type"));
        assertEquals("the-code", req.get("code"));
        assertEquals(challenge[0], Pkce.challengeOf(req.get("code_verifier")));
        assertTrue(req.get("redirect_uri").startsWith("http://127.0.0.1:"));
        assertEquals("cid", req.get("client_id"));
        assertTrue(auth.signedIn());
    }

    @Test
    void refreshUsesStoredTokenAndCaches() throws Exception {
        store.put(GoogleAuth.refreshKey("cid"), "rt1");
        response = "{\"access_token\":\"at2\",\"expires_in\":3600}";
        assertEquals("at2", auth.accessToken());
        assertEquals("at2", auth.accessToken());
        assertEquals(1, tokenRequests.size());
        assertEquals("refresh_token", tokenRequests.getFirst().get("grant_type"));
        assertEquals("rt1", tokenRequests.getFirst().get("refresh_token"));
    }

    @Test
    void invalidGrantForgetsRefreshToken() throws Exception {
        store.put(GoogleAuth.refreshKey("cid"), "rt1");
        status = 400;
        response = "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}";
        IOException e = assertThrows(IOException.class, auth::accessToken);
        assertFalse(e.getMessage().contains("rt1"));
        assertFalse(auth.signedIn());
    }

    @Test
    void refreshTokenIsPerClient() {
        assertTrue(GoogleAuth.refreshKey("a.apps.googleusercontent.com").matches("google-refresh-[0-9a-f]{16}"));
        assertFalse(GoogleAuth.refreshKey("a").equals(GoogleAuth.refreshKey("b")));
    }

    @Test
    void notSignedIn() {
        assertThrows(IOException.class, auth::accessToken);
    }
}

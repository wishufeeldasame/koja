package koja.export;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import koja.runtime.SecretStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구글 OAuth Desktop 로그인(루프백 + PKCE, D-009). 리프레시 토큰만 OS 보관소에 두고(D-056),
 * 액세스 토큰은 메모리에만 둔다. 토큰 값은 로그·예외 메시지에 넣지 않는다.
 */
public final class GoogleAuth {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String REFRESH_TOKEN = "google-refresh-token";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * OAuth 클라이언트(D-058). 데스크톱 클라이언트의 secret은 배포물에 들어가 비밀이 아니지만 저장소에는 두지 않는다.
     */
    public record Client(String id, String secret) {
        /** 환경 변수 {@code KOJA_GOOGLE_CLIENT_ID}·{@code KOJA_GOOGLE_CLIENT_SECRET}, 없으면 같은 이름의 시스템 속성. */
        public static Optional<Client> fromEnvironment() {
            String id = setting("KOJA_GOOGLE_CLIENT_ID", "koja.google.clientId");
            String secret = setting("KOJA_GOOGLE_CLIENT_SECRET", "koja.google.clientSecret");
            return id == null || secret == null ? Optional.empty() : Optional.of(new Client(id, secret));
        }

        private static String setting(String env, String property) {
            String v = System.getenv(env);
            if (v == null || v.isBlank()) {
                v = System.getProperty(property);
            }
            return v == null || v.isBlank() ? null : v.strip();
        }
    }

    private final Client client;
    private final SecretStore secrets;
    private final HttpClient http;
    private final URI authEndpoint;
    private final URI tokenEndpoint;
    private String accessToken;
    private Instant accessExpiry = Instant.EPOCH;

    public GoogleAuth(Client client, SecretStore secrets) {
        this(client, secrets, HttpClient.newHttpClient(),
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create("https://oauth2.googleapis.com/token"));
    }

    GoogleAuth(Client client, SecretStore secrets, HttpClient http, URI authEndpoint, URI tokenEndpoint) {
        this.client = client;
        this.secrets = secrets;
        this.http = http;
        this.authEndpoint = authEndpoint;
        this.tokenEndpoint = tokenEndpoint;
    }

    public boolean signedIn() throws IOException {
        return secrets.get(REFRESH_TOKEN).isPresent();
    }

    /** 브라우저로 로그인하고 액세스 토큰을 돌려준다. {@code openBrowser}는 UI가 기본 브라우저로 연다. */
    public synchronized String signIn(Consumer<URI> openBrowser, Duration timeout)
            throws IOException, InterruptedException {
        Pkce pkce = Pkce.create();
        String state = Pkce.randomToken();
        String code;
        URI redirect;
        try (LoopbackReceiver receiver = new LoopbackReceiver(state)) {
            redirect = receiver.redirectUri();
            openBrowser.accept(authorizationUri(redirect, pkce.challenge(), state));
            code = receiver.awaitCode(timeout);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", pkce.verifier());
        form.put("redirect_uri", redirect.toString());
        JsonNode token = postToken(form);
        String refresh = token.path("refresh_token").asString("");
        if (refresh.isEmpty()) {
            // access_type=offline + prompt=consent면 항상 온다. 안 오면 다음 실행에서 다시 로그인해야 한다.
            throw new IOException("구글이 리프레시 토큰을 주지 않았다");
        }
        secrets.put(REFRESH_TOKEN, refresh);
        return remember(token);
    }

    /** 저장된 리프레시 토큰으로 액세스 토큰을 받는다. 만료 1분 전까지는 메모리 값을 다시 쓴다. */
    public synchronized String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(accessExpiry.minusSeconds(60))) {
            return accessToken;
        }
        String refresh = secrets.get(REFRESH_TOKEN)
                .orElseThrow(() -> new IOException("구글 로그인이 필요하다"));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refresh);
        try {
            return remember(postToken(form));
        } catch (TokenException e) {
            if ("invalid_grant".equals(e.error)) {
                // 사용자가 권한을 철회했거나 토큰이 만료됐다. 다시 로그인하게 지운다.
                secrets.delete(REFRESH_TOKEN);
            }
            throw e;
        }
    }

    public synchronized void signOut() throws IOException {
        accessToken = null;
        secrets.delete(REFRESH_TOKEN);
    }

    URI authorizationUri(URI redirect, String challenge, String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", client.id());
        q.put("redirect_uri", redirect.toString());
        q.put("response_type", "code");
        q.put("scope", SCOPE);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        q.put("state", state);
        q.put("access_type", "offline");
        q.put("prompt", "consent");
        return URI.create(authEndpoint + "?" + encode(q));
    }

    private String remember(JsonNode token) throws IOException {
        String access = token.path("access_token").asString("");
        if (access.isEmpty()) {
            throw new IOException("토큰 응답에 access_token이 없다");
        }
        accessToken = access;
        accessExpiry = Instant.now().plusSeconds(token.path("expires_in").asLong(0));
        return access;
    }

    private JsonNode postToken(Map<String, String> form) throws IOException, InterruptedException {
        form.put("client_id", client.id());
        form.put("client_secret", client.secret());
        HttpRequest req = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body;
        try {
            body = MAPPER.readTree(res.body());
        } catch (RuntimeException e) {
            throw new IOException("토큰 응답을 읽을 수 없다(HTTP " + res.statusCode() + ")");
        }
        if (res.statusCode() != 200) {
            // 오류 응답에는 error·error_description만 있다. 본문 전체는 넣지 않는다.
            throw new TokenException(body.path("error").asString("unknown"),
                    body.path("error_description").asString(""), res.statusCode());
        }
        return body;
    }

    static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static final class TokenException extends IOException {
        final String error;

        TokenException(String error, String description, int status) {
            super("구글 토큰 요청 실패(HTTP " + status + "): " + error + (description.isEmpty() ? "" : " " + description));
            this.error = error;
        }
    }
}

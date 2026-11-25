package koja.export;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import koja.runtime.SecretStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설계 9장 미결 PoC: {@code drive.file}로 어디에 시트를 만들 수 있는지 확인한다(D-057).
 * {@code ./gradlew oauthPoc --args="<기존 폴더 ID>"}. 만든 파일은 끝에 지운다. 토큰은 출력하지 않는다.
 */
public final class DriveFolderPoc {
    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        GoogleAuth.Client client = GoogleAuth.Client.fromEnvironment().orElseThrow(() ->
                new IllegalStateException("KOJA_GOOGLE_CLIENT_ID / KOJA_GOOGLE_CLIENT_SECRET 를 설정한다"));
        GoogleAuth auth = new GoogleAuth(client, SecretStore.system());
        String token;
        if (auth.signedIn()) {
            System.out.println("[1] 키체인의 리프레시 토큰으로 액세스 토큰 갱신");
            token = auth.accessToken();
        } else {
            System.out.println("[1] 브라우저에서 아래 주소를 연다");
            token = auth.signIn(uri -> System.out.println(uri), Duration.ofMinutes(5));
        }
        System.out.println("    로그인 OK, 리프레시 토큰 저장됨: " + auth.signedIn());

        String root = create(token, "[2] 내 드라이브 루트에 시트", "koja PoC 시트", sheet(), List.of());
        if (args.length > 0) {
            create(token, "[3] 사용자가 고른 기존 폴더에 시트", "koja PoC 시트(기존 폴더)", sheet(), List.of(args[0]));
        } else {
            System.out.println("[3] 건너뜀: 기존 폴더 ID 인자가 없다");
        }
        String folder = create(token, "[4] 앱이 만든 폴더", "koja PoC 폴더", "application/vnd.google-apps.folder", List.of());
        String inFolder = folder == null ? null
                : create(token, "[5] 앱이 만든 폴더 안에 시트", "koja PoC 시트(앱 폴더)", sheet(), List.of(folder));

        for (String id : new String[] {root, inFolder, folder}) {
            if (id != null) {
                HTTP.send(auth(token, HttpRequest.newBuilder(URI.create(FILES + "/" + enc(id)))).DELETE().build(),
                        HttpResponse.BodyHandlers.discarding());
            }
        }
        System.out.println("[끝] 만든 파일을 지웠다. 기존 폴더 안에 만든 파일은 권한상 남을 수 있다");
    }

    private static String sheet() {
        return "application/vnd.google-apps.spreadsheet";
    }

    /** 성공하면 파일 ID, 실패하면 상태 코드와 오류 메시지만 찍고 null. */
    private static String create(String token, String label, String name, String mime, List<String> parents)
            throws IOException, InterruptedException {
        Map<String, Object> meta = parents.isEmpty() ? Map.of("name", name, "mimeType", mime)
                : Map.of("name", name, "mimeType", mime, "parents", parents);
        HttpRequest req = auth(token, HttpRequest.newBuilder(URI.create(FILES + "?fields=id,parents")))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(meta)))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = MAPPER.readTree(res.body());
        if (res.statusCode() == 200) {
            System.out.println(label + ": 성공 (HTTP 200)");
            return body.path("id").asString();
        }
        System.out.println(label + ": 실패 (HTTP " + res.statusCode() + ") "
                + body.path("error").path("message").asString(""));
        return null;
    }

    private static HttpRequest.Builder auth(String token, HttpRequest.Builder b) {
        return b.timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

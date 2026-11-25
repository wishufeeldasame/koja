package koja.export;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * OAuth 리디렉션을 받는 1회용 로컬 서버(D-009). 127.0.0.1 임의 포트에만 연다.
 * state가 맞지 않는 요청은 거절하고 계속 기다린다(다른 탭·스캐너 요청에 로그인이 깨지지 않게).
 */
final class LoopbackReceiver implements AutoCloseable {
    private final HttpServer server;
    private final String state;
    private final CompletableFuture<String> code = new CompletableFuture<>();

    LoopbackReceiver(String state) throws IOException {
        this.state = state;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI redirectUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** 인가 코드를 돌려준다. 사용자가 거부하면 {@link IOException}. */
    String awaitCode(Duration timeout) throws IOException, InterruptedException {
        try {
            return code.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("구글 로그인 응답을 기다리다 시간이 지났다");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) || !"/".equals(ex.getRequestURI().getPath())) {
            reply(ex, 404, "없는 주소");
            return;
        }
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String got = q.get("state");
        if (got == null || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            reply(ex, 400, "잘못된 요청(state 불일치)");
            return;
        }
        // 응답을 다 보낸 뒤 대기를 끝낸다. 먼저 끝내면 close()가 서버를 내려 브라우저가 연결 오류를 본다.
        if (q.containsKey("error")) {
            reply(ex, 200, "로그인이 취소되었습니다. 이 창을 닫아도 됩니다.");
            code.completeExceptionally(new IOException("구글 로그인 거부: " + q.get("error")));
        } else if (q.get("code") != null && !q.get("code").isEmpty()) {
            reply(ex, 200, code.isDone() ? "이미 처리된 요청입니다." : "로그인되었습니다. 이 창을 닫고 koja로 돌아가세요.");
            code.complete(q.get("code"));
        } else {
            reply(ex, 400, "잘못된 요청(code 없음)");
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            map.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static void reply(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = ("<!doctype html><meta charset=\"utf-8\"><title>koja</title><p>" + message)
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

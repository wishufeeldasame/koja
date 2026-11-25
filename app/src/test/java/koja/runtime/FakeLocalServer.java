package koja.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link LocalServerTest}가 자식 프로세스로 띄우는 크로스 플랫폼(맥·윈도우 공통) 테스트용 로컬 HTTP 서버.
 * 외부 셸이나 파이썬 없이 JDK만으로 기동·헬스체크·자식 프로세스·비정상 종료 시나리오를 재현한다.
 */
public final class FakeLocalServer {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int port = Integer.parseInt(args[1]);

        if ("sleeper".equals(mode)) {
            Thread.sleep(60_000);
            return;
        }
        if ("crash".equals(mode)) {
            System.err.println("오류: 모델 파일 없음(테스트 종료)");
            System.err.flush();
            System.exit(42);
            return;
        }
        if ("hang".equals(mode)) {
            if (args.length > 2) {
                Path pidPath = Path.of(args[2]);
                Path tmp = pidPath.resolveSibling(pidPath.getFileName() + ".tmp");
                java.nio.file.Files.writeString(
                        tmp,
                        Long.toString(ProcessHandle.current().pid()),
                        StandardCharsets.UTF_8);
                java.nio.file.Files.move(tmp, pidPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("준비되지 않는 테스트 서버 시작");
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
        if ("stubborn".equals(mode)) {
            // SIGTERM 시 유예 시간보다 길게 버텨 강제 종료(destroyForcibly) 경로를 검증한다.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ignored) {
                    // 강제 종료 테스트
                }
            }));
        }

        Process child = null;
        if ("with-child".equals(mode) || "child-then-crash".equals(mode)) {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", AppPaths.executableName("java")).toString();
            child = new ProcessBuilder(List.of(
                    javaBin, "-cp", System.getProperty("java.class.path"),
                    FakeLocalServer.class.getName(), "sleeper", "0"))
                    .start();
            if ("child-then-crash".equals(mode) && args.length > 2) {
                Path pidPath = Path.of(args[2]);
                Path tmp = pidPath.resolveSibling(pidPath.getFileName() + ".tmp");
                java.nio.file.Files.writeString(tmp, Long.toString(child.pid()), StandardCharsets.UTF_8);
                java.nio.file.Files.move(tmp, pidPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("CHILD_PID=" + child.pid());
            System.out.flush();
            if ("child-then-crash".equals(mode)) {
                Thread.sleep(400);
                System.exit(42);
                return;
            }
        }

        if ("slow-start".equals(mode)) {
            Thread.sleep(250);
        }

        AtomicInteger healthCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        int actualPort = server.getAddress().getPort();
        server.createContext("/", ex -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/health", ex -> {
            int n = healthCalls.incrementAndGet();
            boolean loading = "llama-health".equals(mode) && n <= 2;
            int status = loading ? 503 : 200;
            String json = loading ? "{\"error\":{\"message\":\"Loading model\"}}" : "{\"status\":\"ok\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("SERVER_READY port=" + actualPort + " 한글로그");
        System.out.flush();

        // 종료 신호가 올 때까지 유지한다.
        Thread.sleep(60_000);
        if (child != null) {
            child.destroyForcibly();
        }
    }
}

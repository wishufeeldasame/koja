package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalServerTest {
    private static List<String> fakeServerCmd(String mode, int port) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", AppPaths.executableName("java")).toString();
        return List.of(
                javaBin,
                "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                FakeLocalServer.class.getName(),
                mode,
                Integer.toString(port));
    }

    @Test
    void startsHealthChecksAndStopsCleanlyWithKoreanWorkDir(@TempDir Path temp) throws Exception {
        Path workDir = temp.resolve("작업 폴더").resolve("한글 경로");
        Files.createDirectories(workDir);

        LocalServer server = LocalServer.builder("llama-test", port -> fakeServerCmd("llama-health", port))
                .workingDirectory(workDir)
                .healthCheck(LocalServer.HealthCheck.jsonStatusOk("/health"))
                .startupTimeout(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .shutdownGracePeriod(Duration.ofSeconds(2))
                .start();

        long pid = server.pid();
        try (server) {
            assertTrue(server.isAlive());
            assertTrue(server.port() > 0);
            assertEquals("127.0.0.1", server.baseUri().getHost());

            HttpRequest req = HttpRequest.newBuilder(server.baseUri().resolve("/health")).GET().build();
            HttpResponse<String> res = server.httpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"ok\""));
            assertTrue(server.recentLogs().stream().anyMatch(line -> line.contains("한글로그")));
        }

        assertFalse(server.isAlive());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        // close()는 여러 번 불러도 안전해야 한다.
        server.close();
    }

    @Test
    void closesProcessTreeIncludingGrandchild() throws Exception {
        LocalServer server = LocalServer.builder("tree-test", port -> fakeServerCmd("with-child", port))
                .healthCheck(LocalServer.HealthCheck.httpOk("/"))
                .startupTimeout(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .shutdownGracePeriod(Duration.ofSeconds(2))
                .start();

        long parentPid = server.pid();
        long childPid = waitForChildPid(server);
        assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));

        server.close();

        assertFalse(server.isAlive());
        assertFalse(ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "하위 프로세스(PID " + childPid + ")가 종료되지 않았습니다");
    }

    @Test
    void failsFastWhenProcessExitsBeforeReady() {
        IOException ex = assertThrows(IOException.class, () ->
                LocalServer.builder("crash-test", port -> fakeServerCmd("crash", port))
                        .healthCheck(LocalServer.HealthCheck.httpOk("/"))
                        .startupTimeout(Duration.ofSeconds(15))
                        .pollInterval(Duration.ofMillis(100))
                        .start());
        assertTrue(ex.getMessage().contains("42"), "종료 코드 포함 필요: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("모델 파일 없음"), "UTF-8 로그 포함 필요: " + ex.getMessage());
    }

    @Test
    void timeoutCleansUpHangingProcess(@TempDir Path temp) throws IOException {
        Path pidFile = temp.resolve("hung.pid");
        IOException ex = assertThrows(IOException.class, () ->
                LocalServer.builder("hang-test", port -> {
                            List<String> base = new java.util.ArrayList<>(fakeServerCmd("hang", port));
                            base.add(pidFile.toString());
                            return base;
                        })
                        .healthCheck(LocalServer.HealthCheck.httpOk("/"))
                        .startupTimeout(Duration.ofMillis(1500))
                        .pollInterval(Duration.ofMillis(100))
                        .shutdownGracePeriod(Duration.ofMillis(300))
                        .start());
        assertTrue(ex.getMessage().contains("준비되지 않았습니다"), ex.getMessage());
        if (Files.isRegularFile(pidFile)) {
            long hungPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
            assertFalse(ProcessHandle.of(hungPid).map(ProcessHandle::isAlive).orElse(false),
                    "타임아웃된 프로세스(PID " + hungPid + ")가 남아 있습니다");
        }
    }

    @Test
    void forciblyTerminatesServerThatIgnoresGracefulShutdown() throws Exception {
        LocalServer server = LocalServer.builder("stubborn-test", port -> fakeServerCmd("stubborn", port))
                .healthCheck(LocalServer.HealthCheck.httpOk("/"))
                .startupTimeout(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .shutdownGracePeriod(Duration.ofMillis(250))
                .start();

        long pid = server.pid();
        assertTrue(server.isAlive());
        server.close();
        assertFalse(server.isAlive());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void loopbackHttpClientBypassesProxy() {
        try (var client = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2))) {
            var selector = client.proxy().orElseThrow();
            var proxies = selector.select(java.net.URI.create("http://127.0.0.1:18080/health"));
            assertEquals(List.of(java.net.Proxy.NO_PROXY), proxies);
        }
    }

    @Test
    void allocateFreePortReturnsValidPort() throws IOException {
        int port = LocalServer.allocateFreePort();
        assertTrue(port > 0 && port <= 65535);
    }

    private static long waitForChildPid(LocalServer server) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Long> pid = server.recentLogs().stream()
                    .filter(line -> line.startsWith("CHILD_PID="))
                    .map(line -> Long.parseLong(line.substring("CHILD_PID=".length()).trim()))
                    .findFirst();
            if (pid.isPresent()) {
                return pid.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("CHILD_PID 로그를 찾지 못했습니다: " + server.recentLogs());
    }
}

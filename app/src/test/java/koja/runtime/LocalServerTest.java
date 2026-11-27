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
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
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
            assertTrue(waitForLogContaining(server, "한글로그"), "UTF-8 로그 누락: " + server.recentLogs());
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
    void loopbackHttpClientSendsPlainHttp11WithoutH2cUpgrade() throws Exception {
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                java.net.http.HttpClient client = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2))) {
            java.net.URI uri = java.net.URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/inference");
            List<HttpRequest> requests = List.of(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}")).build());
            for (HttpRequest request : requests) {
                java.util.concurrent.CompletableFuture<HttpResponse<String>> pending =
                        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                List<String> head;
                try (java.net.Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    head = readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                assertEquals(200, pending.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
                assertTrue(head.get(0).endsWith(" HTTP/1.1"), head.get(0));
                for (String line : head) {
                    String lower = line.toLowerCase(java.util.Locale.ROOT);
                    assertFalse(lower.startsWith("upgrade:"), line);
                    assertFalse(lower.startsWith("http2-settings:"), line);
                    assertFalse(lower.startsWith("connection:") && lower.contains("upgrade"), line);
                }
            }
        }
    }

    /** 요청 줄과 헤더를 읽고, Content-Length만큼 본문도 비워 클라이언트 전송이 막히지 않게 한다. */
    private static List<String> readRequestHead(java.io.InputStream in) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String text = line.toString().strip();
                line.setLength(0);
                if (text.isEmpty()) {
                    break;
                }
                lines.add(text);
            } else {
                line.append((char) c);
            }
        }
        for (String header : lines) {
            if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                in.readNBytes(Integer.parseInt(header.substring("content-length:".length()).trim()));
            }
        }
        return lines;
    }

    @Test
    void startsWithChildBoundPortFromLog() throws Exception {
        LocalServer server = LocalServer.builder("ephemeral-test", port -> fakeServerCmd("llama-health", port))
                .portFromLog(line -> {
                    int idx = line.indexOf("port=");
                    if (idx < 0) {
                        return java.util.OptionalInt.empty();
                    }
                    String rest = line.substring(idx + "port=".length()).trim();
                    int sp = rest.indexOf(' ');
                    return java.util.OptionalInt.of(Integer.parseInt(sp < 0 ? rest : rest.substring(0, sp)));
                })
                .healthCheck(LocalServer.HealthCheck.jsonStatusOk("/health"))
                .startupTimeout(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .shutdownGracePeriod(Duration.ofSeconds(2))
                .start();

        try (server) {
            assertTrue(server.isAlive());
            assertTrue(server.port() > 0);
            assertEquals("127.0.0.1", server.baseUri().getHost());
        }
        assertFalse(server.isAlive());
    }

    @Test
    void rejectsRogueServerOccupyingPreallocatedPort(@TempDir Path temp) throws Exception {
        com.sun.net.httpserver.HttpServer rogue = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        rogue.createContext("/", ex -> {
            byte[] ok = "rogue".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (var out = ex.getResponseBody()) {
                out.write(ok);
            }
        });
        rogue.start();
        int roguePort = rogue.getAddress().getPort();
        Path pidFile = temp.resolve("child.pid");
        try {
            LocalServer.Config config = LocalServer.builder("rogue-check", port -> {
                        List<String> cmd = new java.util.ArrayList<>(fakeServerCmd("hang", port));
                        cmd.add(pidFile.toString());
                        return cmd;
                    })
                    .healthCheck(LocalServer.HealthCheck.httpOk("/"))
                    .startupTimeout(Duration.ofMillis(1200))
                    .pollInterval(Duration.ofMillis(100))
                    .shutdownGracePeriod(Duration.ofMillis(300))
                    .build();

            assertThrows(IOException.class, () -> LocalServer.startWithPort(config, roguePort));
            if (Files.isRegularFile(pidFile)) {
                long childPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
                assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
            }
        } finally {
            rogue.stop(0);
        }
    }

    @Test
    void startupTimeoutEnforcedDuringSlowHealthCheck(@TempDir Path temp) throws IOException {
        Path pidFile = temp.resolve("slow-probe.pid");
        assertThrows(IOException.class, () ->
                LocalServer.builder("slow-probe", port -> {
                            List<String> cmd = new java.util.ArrayList<>(fakeServerCmd("hang", port));
                            cmd.add(pidFile.toString());
                            return cmd;
                        })
                        .healthCheck((client, uri) -> {
                            Thread.sleep(600);
                            return true;
                        })
                        .startupTimeout(Duration.ofMillis(150))
                        .pollInterval(Duration.ofMillis(50))
                        .shutdownGracePeriod(Duration.ofMillis(300))
                        .start());
        if (Files.isRegularFile(pidFile)) {
            long childPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void startupCrashAfterSpawningChildStillCleansUpGrandchild(@TempDir Path temp) throws IOException {
        Path childPidFile = temp.resolve("grandchild.pid");
        assertThrows(IOException.class, () ->
                LocalServer.builder("crash-with-child", port -> {
                            List<String> cmd = new java.util.ArrayList<>(fakeServerCmd("child-then-crash", port));
                            cmd.add(childPidFile.toString());
                            return cmd;
                        })
                        .healthCheck(LocalServer.HealthCheck.httpOk("/"))
                        .startupTimeout(Duration.ofSeconds(10))
                        .pollInterval(Duration.ofMillis(50))
                        .shutdownGracePeriod(Duration.ofMillis(300))
                        .start());
        assertTrue(Files.isRegularFile(childPidFile), "손자 프로세스 PID 기록이 있어야 합니다");
        long grandchildPid = Long.parseLong(Files.readString(childPidFile, StandardCharsets.UTF_8).trim());
        assertFalse(ProcessHandle.of(grandchildPid).map(ProcessHandle::isAlive).orElse(false),
                "부모 크래시 후 고아가 된 손자 프로세스(PID " + grandchildPid + ")가 종료되지 않았습니다");
    }

    @Test
    void allocateFreePortReturnsValidPort() throws IOException {
        int port = LocalServer.allocateFreePort();
        assertTrue(port > 0 && port <= 65535);
    }

    @Test
    void applyEnvironmentRemovesPrefixesCaseInsensitivelyBeforeApplyingOverrides() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("LLAMA_ARG_CONNECT", "1");
        env.put("llama_arg_log_verbosity", "2");
        env.put("LLAMA_LOG_FILE", "/tmp/x");
        env.put("LLAMA_API_KEY", "secret-key");
        env.put("KEEP_ME", "yes");

        LocalServer.applyEnvironment(
                env,
                List.of("LLAMA_ARG_", "LLAMA_LOG_", "LLAMA_API_KEY"),
                java.util.Map.of("LLAMA_ARG_CUSTOM", "ok", "ADDED", "2"));

        assertFalse(env.containsKey("LLAMA_ARG_CONNECT"));
        assertFalse(env.containsKey("llama_arg_log_verbosity"));
        assertFalse(env.containsKey("LLAMA_LOG_FILE"));
        assertFalse(env.containsKey("LLAMA_API_KEY"));
        assertEquals("yes", env.get("KEEP_ME"));
        assertEquals("ok", env.get("LLAMA_ARG_CUSTOM"));
        assertEquals("2", env.get("ADDED"));
    }

    private static boolean waitForLogContaining(LocalServer server, String token) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (server.recentLogs().stream().anyMatch(line -> line.contains(token))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
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

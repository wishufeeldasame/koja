package koja.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code whisper-server}, {@code llama-server} 공용 로컬 HTTP 서버 수명주기 관리(D-051).
 * 127.0.0.1 동적 포트 할당, 시스템 프록시 우회, UTF-8 로그 비우기, 헬스체크 대기,
 * 종료 시 프로세스 트리 정리 및 종료 대기로 GPU 메모리 해제를 보장한다.
 */
public final class LocalServer implements AutoCloseable {
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FORCE_EXIT_WAIT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_LOG_LINES = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 서버가 요청을 받을 준비가 됐는지 확인한다. 연결 실패 등 일시 오류는 IOException으로 던진다. */
    @FunctionalInterface
    public interface HealthCheck {
        boolean isReady(HttpClient client, URI baseUri) throws IOException, InterruptedException;

        /** 지정 경로가 HTTP 200을 돌려주면 준비 완료({@code whisper-server}의 {@code "/"} 등). */
        static HealthCheck httpOk(String path) {
            String normalized = normalizePath(path);
            return (client, baseUri) -> {
                HttpRequest req = HttpRequest.newBuilder(baseUri.resolve(normalized))
                        .timeout(DEFAULT_PROBE_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
                return res.statusCode() == 200;
            };
        }

        /** 지정 경로가 HTTP 200과 JSON {@code {"status":"ok"}}를 돌려주면 준비 완료({@code llama-server}의 {@code "/health"}). */
        static HealthCheck jsonStatusOk(String path) {
            String normalized = normalizePath(path);
            return (client, baseUri) -> {
                HttpRequest req = HttpRequest.newBuilder(baseUri.resolve(normalized))
                        .timeout(DEFAULT_PROBE_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() != 200) {
                    return false;
                }
                try {
                    JsonNode root = JSON.readTree(res.body());
                    return "ok".equals(root.path("status").asString(""));
                } catch (JacksonException e) {
                    return false;
                }
            };
        }

        private static String normalizePath(String path) {
            if (path == null || path.isBlank()) {
                return "/";
            }
            return path.startsWith("/") ? path : "/" + path;
        }
    }

    /** 로컬 서버 실행 설정. {@code command}는 할당된 포트를 받아 실행 파일과 인자 리스트를 돌려준다. */
    public record Config(
            String name,
            IntFunction<List<String>> command,
            Path workingDirectory,
            Map<String, String> environment,
            HealthCheck healthCheck,
            Duration startupTimeout,
            Duration pollInterval,
            Duration shutdownGracePeriod,
            int maxLogLines) {
        public Config {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("서버 이름이 비어 있습니다");
            }
            Objects.requireNonNull(command, "command");
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            Objects.requireNonNull(healthCheck, "healthCheck");
            Objects.requireNonNull(startupTimeout, "startupTimeout");
            Objects.requireNonNull(pollInterval, "pollInterval");
            Objects.requireNonNull(shutdownGracePeriod, "shutdownGracePeriod");
            if (startupTimeout.isNegative() || startupTimeout.isZero()) {
                throw new IllegalArgumentException("startupTimeout은 양수여야 합니다");
            }
            if (pollInterval.isNegative() || pollInterval.isZero()) {
                throw new IllegalArgumentException("pollInterval은 양수여야 합니다");
            }
            if (shutdownGracePeriod.isNegative()) {
                throw new IllegalArgumentException("shutdownGracePeriod는 음수일 수 없습니다");
            }
            if (maxLogLines <= 0) {
                throw new IllegalArgumentException("maxLogLines는 양수여야 합니다");
            }
        }
    }

    public static final class Builder {
        private final String name;
        private final IntFunction<List<String>> command;
        private Path workingDirectory;
        private Map<String, String> environment = Map.of();
        private HealthCheck healthCheck = HealthCheck.httpOk("/");
        private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private Duration shutdownGracePeriod = DEFAULT_SHUTDOWN_GRACE;
        private int maxLogLines = DEFAULT_MAX_LOG_LINES;

        private Builder(String name, IntFunction<List<String>> command) {
            this.name = name;
            this.command = command;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment == null ? Map.of() : Map.copyOf(environment);
            return this;
        }

        public Builder healthCheck(HealthCheck healthCheck) {
            this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
            return this;
        }

        public Builder startupTimeout(Duration startupTimeout) {
            this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            return this;
        }

        public Builder shutdownGracePeriod(Duration shutdownGracePeriod) {
            this.shutdownGracePeriod = Objects.requireNonNull(shutdownGracePeriod, "shutdownGracePeriod");
            return this;
        }

        public Builder maxLogLines(int maxLogLines) {
            this.maxLogLines = maxLogLines;
            return this;
        }

        public Config build() {
            return new Config(name, command, workingDirectory, environment, healthCheck,
                    startupTimeout, pollInterval, shutdownGracePeriod, maxLogLines);
        }

        public LocalServer start() throws IOException {
            return LocalServer.start(build());
        }
    }

    private final String name;
    private final int port;
    private final URI baseUri;
    private final Process process;
    private final Duration shutdownGracePeriod;
    private final HttpClient httpClient;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final int maxLogLines;
    private final Thread logDrainer;
    private final Thread shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean hookRegistered;
    private volatile boolean inShutdownHook;

    private LocalServer(String name, int port, URI baseUri, Process process, Config config) {
        this.name = name;
        this.port = port;
        this.baseUri = baseUri;
        this.process = process;
        this.shutdownGracePeriod = config.shutdownGracePeriod();
        this.maxLogLines = config.maxLogLines();
        this.httpClient = newLoopbackHttpClient(Duration.ofSeconds(5));
        this.logDrainer = Thread.ofVirtual()
                .name("koja-server-log-" + name)
                .start(() -> drainLogs(process.getInputStream()));
        this.shutdownHook = new Thread(() -> {
            inShutdownHook = true;
            close();
        }, "koja-server-shutdown-" + name);
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        hookRegistered = true;
    }

    public static Builder builder(String name, IntFunction<List<String>> command) {
        return new Builder(name, command);
    }

    /** 127.0.0.1에서 현재 비어 있는 임시 포트를 하나 할당한다. */
    public static int allocateFreePort() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (ServerSocket socket = new ServerSocket(0, 0, loopback)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 로컬 루프백 서버 전용 HttpClient.
     * 시스템 {@code HTTP_PROXY}를 우회해 로컬 요청이 외부로 나가지 않게 한다(절대 규칙 3).
     */
    public static HttpClient newLoopbackHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
    }

    /**
     * 빈 포트를 할당해 서버 프로세스를 띄우고 헬스체크가 통과할 때까지 기다린다.
     * 시작 중 실패·시간 초과·인터럽트가 나면 프로세스를 정리한 뒤 예외를 던진다.
     */
    public static LocalServer start(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        int port = allocateFreePort();
        URI baseUri = URI.create("http://127.0.0.1:" + port);
        List<String> cmd = config.command().apply(port);
        validateCommand(config.name(), cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (config.workingDirectory() != null) {
            pb.directory(config.workingDirectory().toFile());
        }
        if (!config.environment().isEmpty()) {
            pb.environment().putAll(config.environment());
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(config.name() + "를 실행할 수 없습니다(" + cmd.getFirst() + ")", e);
        }
        closeStdinQuietly(process);

        LocalServer server = new LocalServer(config.name(), port, baseUri, process, config);
        try {
            server.registerShutdownHook();
            server.waitUntilReady(config);
            return server;
        } catch (Throwable t) {
            server.close();
            throw t;
        }
    }

    public String name() {
        return name;
    }

    public int port() {
        return port;
    }

    public URI baseUri() {
        return baseUri;
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** 시스템 프록시가 꺼진 로컬 전용 HttpClient. 서버를 닫으면 함께 닫힌다. */
    public HttpClient httpClient() {
        return httpClient;
    }

    public List<String> recentLogs() {
        synchronized (logLines) {
            return List.copyOf(logLines);
        }
    }

    private void waitUntilReady(Config config) throws IOException {
        long deadlineNanos = System.nanoTime() + config.startupTimeout().toNanos();
        long pollNanos = config.pollInterval().toNanos();

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(name + " 시작이 취소되었습니다");
            }
            if (!process.isAlive()) {
                joinLogDrainer(500);
                throw new IOException(name + "가 준비되기 전에 종료되었습니다(종료 코드 " + process.exitValue() + ")."
                        + formatLogsSuffix());
            }

            try {
                if (config.healthCheck().isReady(httpClient, baseUri)) {
                    if (!process.isAlive()) {
                        joinLogDrainer(500);
                        throw new IOException(name + " 프로세스가 헬스체크 직후 종료되었습니다(종료 코드 "
                                + process.exitValue() + ")." + formatLogsSuffix());
                    }
                    return;
                }
            } catch (IOException ignored) {
                // 서버 기동 직후 연결 거절·초기화 중 응답 실패는 준비 완료까지 재시도한다.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException(name + " 시작이 중단되었습니다");
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException(name + "가 " + config.startupTimeout() + " 안에 준비되지 않았습니다."
                        + formatLogsSuffix());
            }

            long waitNanos = Math.min(pollNanos, remainingNanos);
            try {
                // sleep 대신 waitFor로 대기하면 프로세스가 일찍 죽었을 때 즉시 깨어난다.
                if (process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
                    joinLogDrainer(500);
                    throw new IOException(name + "가 준비되기 전에 종료되었습니다(종료 코드 " + process.exitValue() + ")."
                            + formatLogsSuffix());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException(name + " 시작이 중단되었습니다");
            }
        }
    }

    /**
     * 서버와 모든 하위 프로세스를 정리하고 종료 완료까지 기다린다(GPU 메모리 해제 보장, 절대 규칙 5).
     * 여러 번 불러도 안전하다.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (hookRegistered && !inShutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 종료 중이면 훅 제거가 불가능하다.
            }
        }
        try {
            terminateProcessTree(process, shutdownGracePeriod);
        } finally {
            joinLogDrainer(1000);
            httpClient.close();
        }
    }

    private static void terminateProcessTree(Process process, Duration gracePeriod) {
        ProcessHandle root = process.toHandle();
        // 부모를 먼저 죽이면 하위 프로세스가 고아가 되어 descendants()에서 사라질 수 있으므로 먼저 수집한다.
        List<ProcessHandle> descendants = collectDescendants(root);
        boolean interrupted = false;

        if (process.isAlive() || anyAlive(descendants)) {
            for (ProcessHandle child : reversed(descendants)) {
                child.destroy();
            }
            process.destroy();

            long graceDeadline = System.nanoTime() + gracePeriod.toNanos();
            try {
                waitExit(process, graceDeadline);
                waitDescendantsExit(descendants, graceDeadline);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        // 정상 종료 유예가 지났거나 인터럽트가 들어왔으면 남은 프로세스를 강제 종료하고 끝날 때까지 기다린다.
        Set<ProcessHandle> remaining = new LinkedHashSet<>(descendants);
        remaining.addAll(collectDescendants(root));
        if (process.isAlive() || anyAlive(remaining)) {
            for (ProcessHandle child : reversed(new ArrayList<>(remaining))) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            long forceDeadline = System.nanoTime() + FORCE_EXIT_WAIT.toNanos();
            try {
                waitExit(process, forceDeadline);
                waitDescendantsExit(remaining, forceDeadline);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitExit(Process process, long deadlineNanos) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            process.waitFor(remaining, TimeUnit.NANOSECONDS);
        }
    }

    private static void waitDescendantsExit(Iterable<ProcessHandle> handles, long deadlineNanos)
            throws InterruptedException {
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) {
                continue;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException ignored) {
                // 타임아웃이면 호출부에서 강제 종료로 넘어간다.
            }
        }
    }

    private static List<ProcessHandle> collectDescendants(ProcessHandle root) {
        try {
            return root.descendants().toList();
        } catch (UnsupportedOperationException e) {
            return List.of();
        }
    }

    private static boolean anyAlive(Iterable<ProcessHandle> handles) {
        for (ProcessHandle h : handles) {
            if (h.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static List<ProcessHandle> reversed(List<ProcessHandle> list) {
        List<ProcessHandle> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    private void drainLogs(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                synchronized (logLines) {
                    logLines.addLast(line);
                    while (logLines.size() > maxLogLines) {
                        logLines.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // 프로세스 종료 시 파이프가 닫힌다.
        }
    }

    private void joinLogDrainer(long millis) {
        try {
            logDrainer.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String formatLogsSuffix() {
        List<String> tail = recentLogs();
        if (tail.isEmpty()) {
            return "";
        }
        return "\n" + String.join("\n", tail);
    }

    private static void validateCommand(String name, List<String> cmd) throws IOException {
        if (cmd == null || cmd.isEmpty()) {
            throw new IllegalArgumentException(name + " 실행 명령이 비어 있습니다");
        }
        for (String arg : cmd) {
            if (arg == null) {
                throw new IllegalArgumentException(name + " 실행 인자에 null이 있습니다");
            }
        }
        String exe = cmd.getFirst();
        if (exe.isBlank()) {
            throw new IllegalArgumentException(name + " 실행 파일이 비어 있습니다");
        }
        Path exePath = Path.of(exe);
        // PATH 탐색용 단순 명령 이름이 아니라 경로로 지정된 파일이면 실행 권한을 미리 확인한다.
        if (exePath.getParent() != null && Files.isRegularFile(exePath)
                && !AppPaths.isWindows() && !Files.isExecutable(exePath)) {
            throw new IOException(name + " 실행 파일에 실행 권한이 없습니다(chmod +x 필요): " + exePath);
        }
    }

    private static void closeStdinQuietly(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 이미 종료된 프로세스일 수 있다.
        }
    }
}

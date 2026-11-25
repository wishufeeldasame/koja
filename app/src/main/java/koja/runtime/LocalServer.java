package koja.runtime;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code whisper-server}, {@code llama-server} 공용 로컬 HTTP 서버 수명주기 관리(D-051).
 * 127.0.0.1 동적 포트 할당(또는 자식 로그 기반 자체 바인딩 포트), 리스너 소유권 검증, 시스템 프록시 우회,
 * UTF-8 로그 비우기, 제한 시간 내 헬스체크, 종료 시 프로세스 트리 정리 및 종료 완료 확인으로 GPU 메모리 해제를 보장한다.
 */
public final class LocalServer implements Closeable {
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FORCE_EXIT_WAIT = Duration.ofSeconds(5);
    private static final Duration OWNER_CHECK_TIMEOUT = Duration.ofSeconds(2);
    private static final int DEFAULT_MAX_LOG_LINES = 100;
    private static final int MAX_HEALTH_BODY_BYTES = 64 * 1024;
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
                HttpResponse<InputStream> res = sendInterruptible(client, req);
                try (InputStream body = res.body()) {
                    return res.statusCode() == 200;
                }
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
                HttpResponse<InputStream> res = sendInterruptible(client, req);
                String bodyText = readBodyBounded(res.body(), DEFAULT_PROBE_TIMEOUT);
                if (res.statusCode() != 200) {
                    return false;
                }
                try {
                    JsonNode root = JSON.readTree(bodyText);
                    return "ok".equals(root.path("status").asString(""));
                } catch (JacksonException e) {
                    return false;
                }
            };
        }

        private static HttpResponse<InputStream> sendInterruptible(HttpClient client, HttpRequest req)
                throws IOException, InterruptedException {
            CompletableFuture<HttpResponse<InputStream>> future =
                    client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
            try {
                return future.get(DEFAULT_PROBE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                future.whenComplete((r, err) -> {
                    if (r != null) {
                        closeQuietly(r.body());
                    }
                });
                throw new IOException("헬스체크 응답 시간 초과", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                future.whenComplete((r, err) -> {
                    if (r != null) {
                        closeQuietly(r.body());
                    }
                });
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("헬스체크 실패", cause);
            }
        }

        /**
         * JDK HttpClient는 응답 헤더 수신 후 본문이 멈추면 request timeout으로 끊어지지 않으므로
         * 별도 제한 시간 감시로 본문 스트림을 닫는다.
         */
        private static String readBodyBounded(InputStream body, Duration timeout)
                throws IOException, InterruptedException {
            AtomicBoolean done = new AtomicBoolean();
            AtomicBoolean timedOut = new AtomicBoolean();
            Thread timer = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(timeout);
                    if (!done.get()) {
                        timedOut.set(true);
                        closeQuietly(body);
                    }
                } catch (InterruptedException ignored) {
                    // 정상 완료
                }
            });
            try (InputStream in = body) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    int allowed = Math.min(n, MAX_HEALTH_BODY_BYTES - out.size());
                    if (allowed > 0) {
                        out.write(buf, 0, allowed);
                    }
                    if (out.size() >= MAX_HEALTH_BODY_BYTES) {
                        break;
                    }
                }
                if (timedOut.get()) {
                    throw new IOException("헬스체크 본문 수신 시간 초과");
                }
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (timedOut.get()) {
                    throw new IOException("헬스체크 본문 수신 시간 초과", e);
                }
                throw e;
            } finally {
                done.set(true);
                timer.interrupt();
            }
        }

        private static String normalizePath(String path) {
            if (path == null || path.isBlank()) {
                return "/";
            }
            return path.startsWith("/") ? path : "/" + path;
        }
    }

    /**
     * 로컬 서버 실행 설정.
     * {@code portExtractor}가 있으면 자식이 직접 바인딩한 포트(예: {@code --port 0})를 로그에서 읽어 쓰고,
     * 없으면 {@link #allocateFreePort()}로 빈 포트를 할당해 {@code command}에 넘긴 뒤 리스너 소유권을 확인한다.
     */
    public record Config(
            String name,
            IntFunction<List<String>> command,
            Path workingDirectory,
            Map<String, String> environment,
            HealthCheck healthCheck,
            Function<String, OptionalInt> portExtractor,
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
        private Function<String, OptionalInt> portExtractor;
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

        /**
         * 서버가 스스로 바인딩한 포트(예: {@code --port 0})를 출력 로그에서 추출한다.
         * 설정하면 {@code command}에는 {@code 0}이 전달되며, 포트 선점(TOCTOU) 없이 자식 소유가 보장된다.
         */
        public Builder portFromLog(Function<String, OptionalInt> portExtractor) {
            this.portExtractor = Objects.requireNonNull(portExtractor, "portExtractor");
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
            return new Config(name, command, workingDirectory, environment, healthCheck, portExtractor,
                    startupTimeout, pollInterval, shutdownGracePeriod, maxLogLines);
        }

        public LocalServer start() throws IOException {
            return LocalServer.start(build());
        }
    }

    private enum PortOwnership {
        OWNED,
        NOT_OWNED,
        UNKNOWN
    }

    private final String name;
    private final AtomicInteger port = new AtomicInteger();
    private volatile URI baseUri;
    private final Process process;
    private final Duration shutdownGracePeriod;
    private final HttpClient httpClient;
    private final Function<String, OptionalInt> portExtractor;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final int maxLogLines;
    private final Set<ProcessHandle> trackedDescendants = ConcurrentHashMap.newKeySet();
    private final Thread logDrainer;
    private final Thread shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean hookRegistered;
    private volatile boolean inShutdownHook;

    private LocalServer(String name, int initialPort, Process process, Config config) {
        this.name = name;
        this.process = process;
        this.shutdownGracePeriod = config.shutdownGracePeriod();
        this.maxLogLines = config.maxLogLines();
        this.portExtractor = config.portExtractor();
        if (initialPort > 0) {
            this.port.set(initialPort);
            this.baseUri = URI.create("http://127.0.0.1:" + initialPort);
        }
        this.httpClient = newLoopbackHttpClient(Duration.ofSeconds(5));
        this.logDrainer = Thread.ofVirtual()
                .name("koja-server-log-" + name)
                .start(() -> drainLogs(process.getInputStream()));
        this.shutdownHook = new Thread(() -> {
            inShutdownHook = true;
            try {
                close();
            } catch (IOException e) {
                System.err.println("[koja] 종료 훅에서 서버 정리 실패(" + name + "): " + e.getMessage());
            }
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
     * 서버 프로세스를 띄우고 헬스체크가 통과할 때까지 기다린다.
     * 시작 중 실패·시간 초과·인터럽트가 나면 프로세스 트리를 정리한 뒤 예외를 던진다.
     */
    public static LocalServer start(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        int initialPort = config.portExtractor() != null ? 0 : allocateFreePort();
        return startWithPort(config, initialPort);
    }

    static LocalServer startWithPort(Config config, int initialPort) throws IOException {
        List<String> cmd = config.command().apply(initialPort);
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

        LocalServer server = new LocalServer(config.name(), initialPort, process, config);
        try {
            server.recordDescendants();
            server.registerShutdownHook();
            server.waitUntilReady(config);
            return server;
        } catch (Throwable t) {
            try {
                server.close();
            } catch (Throwable closeErr) {
                t.addSuppressed(closeErr);
            }
            throw t;
        }
    }

    public String name() {
        return name;
    }

    public int port() {
        return port.get();
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
            recordDescendants();
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(name + " 시작이 취소되었습니다");
            }
            if (!process.isAlive()) {
                joinLogDrainer(500);
                throw new IOException(name + "가 준비되기 전에 종료되었습니다(종료 코드 " + process.exitValue() + ")."
                        + formatLogsSuffix());
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException(name + "가 " + config.startupTimeout() + " 안에 준비되지 않았습니다."
                        + formatLogsSuffix());
            }

            if (port.get() > 0) {
                URI targetUri = baseUri;
                boolean ready = runBoundedHealthCheck(config.healthCheck(), targetUri, remainingNanos);
                if (ready) {
                    if (System.nanoTime() > deadlineNanos) {
                        throw new IOException(name + "가 " + config.startupTimeout() + " 안에 준비되지 않았습니다."
                                + formatLogsSuffix());
                    }
                    if (!process.isAlive()) {
                        joinLogDrainer(500);
                        throw new IOException(name + " 프로세스가 헬스체크 직후 종료되었습니다(종료 코드 "
                                + process.exitValue() + ")." + formatLogsSuffix());
                    }
                    if (verifyListenerOwnership(port.get())) {
                        return;
                    }
                }
            }

            remainingNanos = deadlineNanos - System.nanoTime();
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

    /** 헬스체크가 startupTimeout 남은 시간 안에 끝나도록 가상 스레드에서 실행하고 초과 시 중단한다. */
    private boolean runBoundedHealthCheck(HealthCheck check, URI targetUri, long remainingNanos) throws IOException {
        CompletableFuture<Boolean> probe = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual()
                .name("koja-health-probe-" + name)
                .start(() -> {
                    try {
                        probe.complete(check.isReady(httpClient, targetUri));
                    } catch (Throwable t) {
                        probe.completeExceptionally(t);
                    }
                });
        try {
            return Boolean.TRUE.equals(probe.get(remainingNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            worker.interrupt();
            return false;
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new CancellationException(name + " 시작이 중단되었습니다");
        } catch (ExecutionException e) {
            // 연결 거절·초기화 중 오류는 준비 완료까지 재시도한다.
            return false;
        }
    }

    /**
     * 헬스체크에 응답한 리스너가 실제로 이 서버 프로세스(또는 하위 프로세스) 소유인지 확인한다.
     * 자식이 로그로 직접 바인딩 포트를 알려 준 경우({@code portExtractor})는 OS 도구 조회가 막힌 환경에서도
     * 다른 서버로 확인될 때만 거부한다. 사전 할당 포트 방식에서는 소유가 확인돼야만 통과한다.
     */
    private boolean verifyListenerOwnership(int boundPort) throws IOException {
        recordDescendants();
        Set<Long> treePids = new LinkedHashSet<>();
        treePids.add(process.pid());
        for (ProcessHandle h : trackedDescendants) {
            treePids.add(h.pid());
        }
        PortOwnership ownership = checkPortOwnership(boundPort, treePids);
        if (ownership == PortOwnership.OWNED) {
            return true;
        }
        if (ownership == PortOwnership.NOT_OWNED) {
            return false;
        }
        // OS 포트 소유 조회가 제한된 환경(예: 샌드박스): 자식이 직접 바인딩 포트를 출력한 경우에만 허용한다.
        if (portExtractor != null) {
            return true;
        }
        throw new IOException(name + " 포트(" + boundPort + ")의 리스너 소유 프로세스를 확인할 수 없습니다. "
                + "자식 바인딩 포트(portFromLog)를 사용하거나 실행 환경을 확인하세요.");
    }

    private static PortOwnership checkPortOwnership(int boundPort, Set<Long> treePids) {
        if (AppPaths.isMac()) {
            return checkPortOwnershipMac(boundPort, treePids);
        }
        if (AppPaths.isWindows()) {
            return checkPortOwnershipWindows(boundPort, treePids);
        }
        return checkPortOwnershipMac(boundPort, treePids);
    }

    private static PortOwnership checkPortOwnershipMac(int boundPort, Set<Long> treePids) {
        String lsof = Files.isExecutable(Path.of("/usr/sbin/lsof")) ? "/usr/sbin/lsof" : "lsof";
        String pidList = treePids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<String> cmd = List.of(
                lsof, "-nP", "-a", "-p", pidList, "-iTCP:" + boundPort, "-sTCP:LISTEN", "-Fp");
        CommandResult result = runUtility(cmd);
        if (result == null) {
            return PortOwnership.UNKNOWN;
        }
        if (result.exitCode() == 0) {
            for (String line : result.stdout().split("\\R")) {
                if (line.startsWith("p")) {
                    try {
                        long pid = Long.parseLong(line.substring(1).trim());
                        if (treePids.contains(pid)) {
                            return PortOwnership.OWNED;
                        }
                    } catch (NumberFormatException ignored) {
                        // 다음 줄 확인
                    }
                }
            }
            return PortOwnership.NOT_OWNED;
        }
        if (result.exitCode() == 1 && result.stderr().isBlank()) {
            return PortOwnership.NOT_OWNED;
        }
        return PortOwnership.UNKNOWN;
    }

    private static PortOwnership checkPortOwnershipWindows(int boundPort, Set<Long> treePids) {
        CommandResult result = runUtility(List.of("netstat", "-ano", "-p", "TCP"));
        if (result == null || result.exitCode() != 0) {
            return PortOwnership.UNKNOWN;
        }
        String suffix = ":" + boundPort;
        for (String raw : result.stdout().split("\\R")) {
            String line = raw.trim();
            if (!line.toUpperCase(Locale.ROOT).startsWith("TCP")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            String localAddr = parts[1];
            String foreignAddr = parts[2];
            if (!isLoopbackOrWildcardPort(localAddr, suffix) || !foreignAddr.endsWith(":0")) {
                continue;
            }
            try {
                long pid = Long.parseLong(parts[parts.length - 1]);
                if (treePids.contains(pid)) {
                    return PortOwnership.OWNED;
                }
            } catch (NumberFormatException ignored) {
                // 다음 줄 확인
            }
        }
        return PortOwnership.NOT_OWNED;
    }

    private static boolean isLoopbackOrWildcardPort(String addr, String portSuffix) {
        return addr.equals("127.0.0.1" + portSuffix)
                || addr.equals("0.0.0.0" + portSuffix)
                || addr.equals("[::1]" + portSuffix)
                || addr.equals("[::]" + portSuffix);
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {}

    private static CommandResult runUtility(List<String> cmd) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            return null;
        }
        closeStdinQuietly(p);
        try {
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAllUtf8(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readAllUtf8(p.getErrorStream()));
            if (!p.waitFor(OWNER_CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new CommandResult(p.exitValue(), out.join(), err.join());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            p.destroyForcibly();
            return null;
        }
    }

    private static String readAllUtf8(InputStream in) {
        try (InputStream s = in) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 서버와 모든 하위 프로세스를 정리하고 종료 완료를 확인한다(GPU 메모리 해제 보장, 절대 규칙 5).
     * 강제 종료 후에도 생존 프로세스가 남으면 {@link IOException}을 던져 다음 GPU 단계 진입을 막는다.
     * 여러 번 불러도 안전하다.
     */
    @Override
    public void close() throws IOException {
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
        IOException stopFailure = null;
        try {
            stopFailure = terminateProcessTree(name, process, trackedDescendants, shutdownGracePeriod);
        } finally {
            joinLogDrainer(1000);
            httpClient.close();
        }
        if (stopFailure != null) {
            throw stopFailure;
        }
    }

    private static IOException terminateProcessTree(
            String name,
            Process process,
            Set<ProcessHandle> trackedDescendants,
            Duration gracePeriod) {
        ProcessHandle root = process.toHandle();
        // 부모를 먼저 죽이면 하위 프로세스가 고아가 되어 descendants()에서 사라질 수 있으므로 먼저 수집한다.
        Set<ProcessHandle> descendants = new LinkedHashSet<>(trackedDescendants);
        descendants.addAll(collectDescendants(root));
        boolean interrupted = false;

        if (process.isAlive() || anyAlive(descendants)) {
            for (ProcessHandle child : reversed(new ArrayList<>(descendants))) {
                tryDestroy(child);
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
                    tryDestroyForcibly(child);
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            long forceDeadline = System.nanoTime() + FORCE_EXIT_WAIT.toNanos();
            // 강제 종료 대기는 인터럽트가 들어와도 기한까지 계속 기다려 GPU 해제를 확인한다.
            interrupted |= waitExitUninterruptibly(process, forceDeadline);
            interrupted |= waitDescendantsExitUninterruptibly(remaining, forceDeadline);
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        List<Long> survivors = new ArrayList<>();
        if (process.isAlive()) {
            survivors.add(process.pid());
        }
        for (ProcessHandle child : remaining) {
            if (child.isAlive()) {
                survivors.add(child.pid());
            }
        }
        if (!survivors.isEmpty()) {
            return new IOException(name + " 프로세스를 완전히 종료하지 못했습니다(생존 PID: " + survivors + ")");
        }
        return null;
    }

    private static void tryDestroy(ProcessHandle handle) {
        try {
            handle.destroy();
        } catch (RuntimeException ignored) {
            // 이미 종료됐거나 권한이 없는 경우 루트 종료를 계속 진행한다.
        }
    }

    private static void tryDestroyForcibly(ProcessHandle handle) {
        try {
            handle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 이미 종료됐거나 권한이 없는 경우 루트 종료를 계속 진행한다.
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

    private static boolean waitExitUninterruptibly(Process process, long deadlineNanos) {
        boolean interrupted = false;
        while (process.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
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

    private static boolean waitDescendantsExitUninterruptibly(Iterable<ProcessHandle> handles, long deadlineNanos) {
        boolean interrupted = false;
        for (ProcessHandle handle : handles) {
            while (handle.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return interrupted;
                }
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException | ExecutionException ignored) {
                    break;
                }
            }
        }
        return interrupted;
    }

    private void recordDescendants() {
        trackedDescendants.addAll(collectDescendants(process.toHandle()));
    }

    private static List<ProcessHandle> collectDescendants(ProcessHandle root) {
        try {
            return root.descendants().toList();
        } catch (RuntimeException e) {
            // macOS 샌드박스 등에서 sysctl(KERN_PROC_ALL)이 거부돼도 루트 프로세스 정리를 막지 않는다.
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
                if (portExtractor != null && port.get() == 0) {
                    OptionalInt parsed = portExtractor.apply(line);
                    if (parsed.isPresent() && parsed.getAsInt() > 0 && parsed.getAsInt() <= 65535
                            && port.compareAndSet(0, parsed.getAsInt())) {
                        baseUri = URI.create("http://127.0.0.1:" + parsed.getAsInt());
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

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 정리 예외 무시
            }
        }
    }
}

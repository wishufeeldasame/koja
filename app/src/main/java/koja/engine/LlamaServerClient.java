package koja.engine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import koja.runtime.LlamaServer;
import koja.runtime.LocalServer;
import koja.runtime.ModelStore;

/**
 * 로컬 {@code llama-server} 기반 {@link LlmClient} 구현(D-005, D-044, D-046, D-055, D-066).
 * OpenAI 호환 {@code /v1/chat/completions} API로 JSON 스키마 강제 응답을 받으며,
 * {@link LocalServer}로 서버 수명주기를 관리해 {@link #close()} 시 GPU 메모리를 해제한다(절대 규칙 5).
 */
public final class LlamaServerClient implements LlmClient {
    public static final int DEFAULT_CONTEXT_SIZE = 8192;
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(15);

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    // GGUF 메타데이터 등에 루프백 URL이 찍힐 수 있으므로 실제 바인딩 문구("listening on")에서만 포트를 읽는다.
    private static final Pattern LISTENING_PORT_PATTERN = Pattern.compile("listening on http://127\\.0\\.0\\.1:(\\d+)");
    // 로컬 추론 튜닝 옵션만 허용해 원격 RPC·터널·프롬프트 파일 기록 등 비공개 유출 경로를 원천 차단한다(절대 규칙 3).
    private static final List<String> ALLOWED_VALUE_ARGS = List.of(
            "--n-cpu-moe",
            "-t", "--threads",
            "-tb", "--threads-batch",
            "-b", "--batch-size",
            "-ub", "--ubatch-size",
            "--seed",
            "-fa", "--flash-attn",
            "-ctk", "--cache-type-k",
            "-ctv", "--cache-type-v",
            "-fitt", "--fit-target",
            "-fitc", "--fit-ctx");
    private static final List<String> ALLOWED_FLAG_ARGS = List.of(
            "--mlock",
            "--mmap",
            "--no-mmap");

    /** {@code llama-server} 기동·호출 설정. */
    public record Config(
            String executable,
            Path modelPath,
            String modelName,
            int contextSize,
            List<String> extraArgs,
            Duration startupTimeout,
            Duration requestTimeout,
            int maxRetries,
            Duration retryDelay) {
        public Config {
            if (executable == null || executable.isBlank()) {
                throw new IllegalArgumentException("llama-server 실행 파일 경로가 비어 있습니다");
            }
            Objects.requireNonNull(modelPath, "modelPath");
            if (modelName == null || modelName.isBlank()) {
                modelName = modelPath.getFileName() == null ? "custom" : modelPath.getFileName().toString();
            }
            if (contextSize <= 0) {
                throw new IllegalArgumentException("contextSize는 양수여야 합니다: " + contextSize);
            }
            extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            validateExtraArgs(extraArgs);
            Objects.requireNonNull(startupTimeout, "startupTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(retryDelay, "retryDelay");
            if (startupTimeout.isNegative() || startupTimeout.isZero()) {
                throw new IllegalArgumentException("startupTimeout은 양수여야 합니다");
            }
            if (requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("requestTimeout은 양수여야 합니다");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries는 0 이상이어야 합니다: " + maxRetries);
            }
            if (retryDelay.isNegative()) {
                throw new IllegalArgumentException("retryDelay는 음수일 수 없습니다");
            }
        }
    }

    public static final class Builder {
        private String executable = LlamaServer.executable();
        private final Path modelPath;
        private String modelName;
        private int contextSize = DEFAULT_CONTEXT_SIZE;
        private List<String> extraArgs = List.of();
        private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration retryDelay = DEFAULT_RETRY_DELAY;

        private Builder(Path modelPath) {
            this.modelPath = Objects.requireNonNull(modelPath, "modelPath");
        }

        public Builder executable(String executable) {
            this.executable = executable;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder contextSize(int contextSize) {
            this.contextSize = contextSize;
            return this;
        }

        public Builder extraArgs(List<String> extraArgs) {
            this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            return this;
        }

        public Builder startupTimeout(Duration startupTimeout) {
            this.startupTimeout = startupTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        public Config build() {
            return new Config(executable, modelPath, modelName, contextSize, extraArgs,
                    startupTimeout, requestTimeout, maxRetries, retryDelay);
        }

        public LlamaServerClient start() throws IOException {
            return LlamaServerClient.start(build());
        }
    }

    private final String engineName;
    private final URI chatUri;
    private final HttpClient httpClient;
    private final LocalServer server;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LlamaServerClient(
            String engineName,
            URI baseUri,
            HttpClient httpClient,
            LocalServer server,
            Duration requestTimeout,
            int maxRetries,
            Duration retryDelay) {
        this.engineName = engineName;
        this.chatUri = baseUri.resolve(CHAT_COMPLETIONS_PATH);
        this.httpClient = httpClient;
        this.server = server;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
    }

    public static Builder builder(Path modelPath) {
        return new Builder(modelPath);
    }

    /** 기본 모델(Gemma 4 E4B)을 {@link ModelStore}에서 준비한 뒤 {@code llama-server}를 시작한다. */
    public static LlamaServerClient open(ModelStore modelStore) throws IOException {
        return open(modelStore, LlamaModel.DEFAULT, null, null);
    }

    /** 지정 모델을 {@link ModelStore}에서 준비한 뒤 {@code llama-server}를 시작한다. */
    public static LlamaServerClient open(
            ModelStore modelStore,
            LlamaModel model,
            ModelStore.ProgressListener progress,
            ModelStore.CancelToken cancelToken) throws IOException {
        Objects.requireNonNull(modelStore, "modelStore");
        Objects.requireNonNull(model, "model");
        Path modelPath = modelStore.ensureDownloaded(model.spec(), progress, cancelToken);
        return builder(modelPath)
                .modelName(model.id())
                .start();
    }

    /** 지정 설정으로 {@code llama-server} 프로세스를 시작하고 준비 완료까지 기다린다. */
    public static LlamaServerClient start(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        Path normalizedModel = config.modelPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedModel)) {
            throw new IOException("LLM 모델 파일이 없습니다: " + normalizedModel);
        }
        String resolvedExecutable = LlamaServer.resolveExecutable(config.executable());
        return startWithCommand(config, resolvedExecutable, port -> buildCommand(resolvedExecutable, config, normalizedModel, port));
    }

    static LlamaServerClient startWithCommand(Config config, IntFunction<List<String>> commandBuilder)
            throws IOException {
        String resolvedExecutable = LlamaServer.resolveExecutable(config.executable());
        return startWithCommand(config, resolvedExecutable, commandBuilder);
    }

    private static LlamaServerClient startWithCommand(
            Config config, String resolvedExecutable, IntFunction<List<String>> commandBuilder) throws IOException {
        LocalServer.Builder serverBuilder = LocalServer.builder("llama-server", commandBuilder)
                .portFromLog(LlamaServerClient::extractListeningPort)
                .healthCheck(LocalServer.HealthCheck.jsonStatusOk("/health"))
                .startupTimeout(config.startupTimeout())
                .unsetEnvironmentPrefixes(LlamaServer.clearedEnvironmentPrefixes())
                .environment(LlamaServer.processEnvironment(resolvedExecutable));
        Path workDir = LlamaServer.workingDirectory(resolvedExecutable);
        if (workDir != null) {
            serverBuilder.workingDirectory(workDir);
        }
        LocalServer started;
        try {
            started = serverBuilder.start();
        } catch (IOException e) {
            if (LlamaChatProtocol.isOutOfMemoryText(e.getMessage())) {
                throw new OutOfMemoryException("llama-server 시작 중 메모리 부족(OOM): " + e.getMessage(), e);
            }
            throw e;
        }
        return new LlamaServerClient(
                "llama-server/" + config.modelName(),
                started.baseUri(),
                started.httpClient(),
                started,
                config.requestTimeout(),
                config.maxRetries(),
                config.retryDelay());
    }

    /** 단위 테스트에서 이미 떠 있는 가짜 HTTP 서버에 직접 붙을 때 쓴다. */
    static LlamaServerClient connectForTest(
            String modelName,
            URI baseUri,
            HttpClient httpClient,
            Duration requestTimeout,
            int maxRetries,
            Duration retryDelay) {
        return new LlamaServerClient(
                "llama-server/" + modelName,
                baseUri,
                httpClient,
                null,
                requestTimeout,
                maxRetries,
                retryDelay);
    }

    /**
     * {@code llama-server} 실행 인자를 조립한다.
     * {@code -ngl auto}(--fit 기본 켜짐)로 8~10GB 카드에서 E4B 전부 GPU·26B 일부 CPU 자동 배치를 쓰고,
     * {@code --reasoning off}로 추론 토큰 낭비를 막으며 {@code --log-verbosity 3}으로 포트 시작 로그를 보장한다(D-041, D-055, D-066).
     */
    static List<String> buildCommand(Config config, Path modelPath, int port) {
        return buildCommand(LlamaServer.resolveExecutable(config.executable()), config, modelPath, port);
    }

    private static List<String> buildCommand(String resolvedExecutable, Config config, Path modelPath, int port) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedExecutable);
        cmd.add("-m");
        cmd.add(modelPath.toString());
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(Integer.toString(port));
        cmd.add("-ngl");
        cmd.add("auto");
        cmd.add("-c");
        cmd.add(Integer.toString(config.contextSize()));
        cmd.add("-np");
        cmd.add("1");
        cmd.add("--reasoning");
        cmd.add("off");
        cmd.add("--log-verbosity");
        cmd.add("3");
        cmd.addAll(config.extraArgs());
        return cmd;
    }

    private static void validateExtraArgs(List<String> extraArgs) {
        for (int i = 0; i < extraArgs.size(); i++) {
            String arg = extraArgs.get(i);
            if (arg == null || arg.isBlank()) {
                throw new IllegalArgumentException("extraArgs에 비어 있는 인자가 있습니다");
            }
            String trimmed = arg.trim();
            if (ALLOWED_FLAG_ARGS.contains(trimmed)) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq);
                String val = trimmed.substring(eq + 1);
                if (!ALLOWED_VALUE_ARGS.contains(name) || val.isBlank()) {
                    throw new IllegalArgumentException("허용되지 않은 llama-server 추가 인자입니다: " + trimmed);
                }
                continue;
            }
            if (ALLOWED_VALUE_ARGS.contains(trimmed)) {
                if (i + 1 >= extraArgs.size()) {
                    throw new IllegalArgumentException("extraArgs 옵션 값이 빠졌습니다: " + trimmed);
                }
                String value = extraArgs.get(++i);
                if (value == null || value.isBlank() || value.trim().startsWith("-")) {
                    throw new IllegalArgumentException("extraArgs 옵션 값이 올바르지 않습니다: " + trimmed);
                }
                continue;
            }
            throw new IllegalArgumentException("허용되지 않은 llama-server 추가 인자입니다: " + trimmed);
        }
    }

    static OptionalInt extractListeningPort(String line) {
        if (line == null || line.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher matcher = LISTENING_PORT_PATTERN.matcher(line);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        try {
            int port = Integer.parseInt(matcher.group(1));
            return (port >= 1 && port <= 65535) ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public String name() {
        return engineName;
    }

    @Override
    public Response complete(Request request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            throw new IllegalStateException("이미 닫힌 LlamaServerClient입니다");
        }
        if (Thread.interrupted()) {
            throw new InterruptedException("LLM 요청이 취소되었습니다");
        }
        byte[] payload = LlamaChatProtocol.buildRequestBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(chatUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ensureServerAlive();
            if (Thread.interrupted()) {
                throw new InterruptedException("LLM 요청이 취소되었습니다");
            }
            HttpResponse<String> res;
            try {
                res = sendInterruptible(httpRequest);
            } catch (IOException e) {
                if (!isServerAlive() || hasOutOfMemoryInServerLogs()) {
                    throw serverDiedException(e);
                }
                if (e instanceof HttpTimeoutException) {
                    throw e;
                }
                lastError = e;
                if (attempt < maxRetries && !retryDelay.isZero()) {
                    Thread.sleep(retryDelay);
                }
                continue;
            }

            int status = res.statusCode();
            if (status == 200) {
                Response parsed = LlamaChatProtocol.parseSuccessResponse(res.body());
                if (request.jsonSchema() != null && "length".equals(parsed.finishReason())) {
                    throw new OutputTruncatedException(
                            "JSON 스키마 응답이 max_tokens(" + request.maxTokens() + ") 제한에 도달해 잘렸습니다",
                            parsed.content(),
                            parsed.usage());
                }
                return parsed;
            }
            IOException classified = LlamaChatProtocol.classifyHttpError(status, res.body(), hasOutOfMemoryInServerLogs());
            if (classified instanceof ContextLengthExceededException
                    || classified instanceof OutOfMemoryException
                    || !LlamaChatProtocol.isRetryableStatus(status)
                    || !isServerAlive()) {
                throw classified;
            }
            lastError = classified;
            if (attempt < maxRetries && !retryDelay.isZero()) {
                Thread.sleep(retryDelay);
            }
        }
        if (!isServerAlive() || hasOutOfMemoryInServerLogs()) {
            throw serverDiedException(lastError);
        }
        throw lastError != null ? lastError : new IOException("llama-server 요청에 실패했습니다");
    }

    private HttpResponse<String> sendInterruptible(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("llama-server 응답 시간 초과(" + requestTimeout + ")");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("llama-server 통신 중 오류", cause);
        }
    }

    private void ensureServerAlive() throws IOException {
        if (!isServerAlive()) {
            throw serverDiedException(null);
        }
    }

    private boolean isServerAlive() {
        return server == null || server.isAlive();
    }

    private IOException serverDiedException(Throwable cause) {
        String tail = formatRecentLogs();
        if (hasOutOfMemoryInServerLogs()) {
            return new OutOfMemoryException("llama-server가 메모리 부족(OOM)으로 종료되었습니다." + tail, cause);
        }
        return new IOException("llama-server 프로세스가 종료되었습니다." + tail, cause);
    }

    private boolean hasOutOfMemoryInServerLogs() {
        if (server == null) {
            return false;
        }
        for (String line : server.recentLogs()) {
            if (LlamaChatProtocol.isOutOfMemoryText(line)) {
                return true;
            }
        }
        return false;
    }

    private String formatRecentLogs() {
        if (server == null) {
            return "";
        }
        List<String> logs = server.recentLogs();
        if (logs.isEmpty()) {
            return "";
        }
        int start = Math.max(0, logs.size() - 10);
        return "\n최근 로그:\n" + String.join("\n", logs.subList(start, logs.size()));
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (server != null) {
            server.close();
        }
    }
}

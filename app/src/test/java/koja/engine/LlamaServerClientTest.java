package koja.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import koja.engine.LlmClient.ContextLengthExceededException;
import koja.engine.LlmClient.Message;
import koja.engine.LlmClient.OutOfMemoryException;
import koja.engine.LlmClient.OutputTruncatedException;
import koja.engine.LlmClient.Request;
import koja.engine.LlmClient.Response;
import koja.engine.LlmClient.Role;
import koja.engine.LlmClient.Usage;
import koja.runtime.LocalServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LlamaServerClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SAMPLE_SCHEMA = """
            {
              "type": "object",
              "required": ["ok"],
              "properties": {
                "ok": { "type": "boolean" }
              }
            }
            """;

    @Test
    void buildsOpenAiRequestJsonWithAndWithoutSchema() {
        Request withSchema = new Request(
                List.of(
                        new Message(Role.SYSTEM, "시스템 지침"),
                        new Message(Role.USER, "한국어 발화"),
                        new Message(Role.ASSISTANT, "{\"ok\":false}"),
                        new Message(Role.USER, "이전 출력 검증 실패")),
                SAMPLE_SCHEMA,
                0.0,
                2048);

        JsonNode root = JSON.readTree(LlamaChatProtocol.buildRequestBody(withSchema));
        assertEquals(4, root.path("messages").size());
        assertEquals("system", root.path("messages").get(0).path("role").asString());
        assertEquals("시스템 지침", root.path("messages").get(0).path("content").asString());
        assertEquals("assistant", root.path("messages").get(2).path("role").asString());
        assertEquals(0.0, root.path("temperature").asDouble());
        assertEquals(2048, root.path("max_tokens").asInt());
        assertFalse(root.path("stream").asBoolean());
        assertEquals("json_schema", root.path("response_format").path("type").asString());
        assertTrue(root.path("response_format").path("json_schema").path("strict").asBoolean());
        assertEquals("object", root.path("response_format").path("json_schema").path("schema").path("type").asString());

        Request plain = Request.of("지침", "질문");
        JsonNode plainRoot = JSON.readTree(LlamaChatProtocol.buildRequestBody(plain));
        assertTrue(plainRoot.path("response_format").isMissingNode());
        assertEquals(Request.DEFAULT_MAX_TOKENS, plainRoot.path("max_tokens").asInt());

        Request badSchema = new Request(List.of(new Message(Role.USER, "hi")), "[1,2]", 0.0, 64);
        assertThrows(IllegalArgumentException.class, () -> LlamaChatProtocol.buildRequestBody(badSchema));
    }

    @Test
    void parsesResponseAndRejectsMalformedPayload() throws IOException {
        String ok = """
                {
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": { "role": "assistant", "content": "{\\"ok\\":true}" }
                    }
                  ],
                  "usage": { "prompt_tokens": 321, "completion_tokens": 45 }
                }
                """;
        Response res = LlamaChatProtocol.parseSuccessResponse(ok);
        assertEquals("{\"ok\":true}", res.content());
        assertEquals(new Usage(321, 45), res.usage());
        assertEquals("stop", res.finishReason());

        String noUsage = "{\"choices\":[{\"message\":{\"content\":\"안녕\"}}]}";
        Response minimal = LlamaChatProtocol.parseSuccessResponse(noUsage);
        assertEquals("안녕", minimal.content());
        assertEquals(Usage.EMPTY, minimal.usage());
        assertNull(minimal.finishReason());

        assertThrows(IOException.class, () -> LlamaChatProtocol.parseSuccessResponse(""));
        assertThrows(IOException.class, () -> LlamaChatProtocol.parseSuccessResponse("not-json"));
        assertThrows(IOException.class, () -> LlamaChatProtocol.parseSuccessResponse("{\"choices\":[]}"));
        assertThrows(IOException.class, () -> LlamaChatProtocol.parseSuccessResponse("{\"choices\":[{\"message\":{}}]}"));
    }

    @Test
    void buildsExpectedCliCommandWith8kContextAndReasoningOff(@TempDir Path dir) {
        Path model = dir.resolve("모델 폴더").resolve("gemma-4-E4B-it-Q4_K_M.gguf");
        Path relativeExe = Path.of("상대 폴더", "llama-server");
        LlamaServerClient.Config cfg = LlamaServerClient.builder(model)
                .executable(relativeExe.toString())
                .modelName(LlamaModel.GEMMA_4_E4B.id())
                .extraArgs(List.of("--seed", "42"))
                .build();

        List<String> cmd = LlamaServerClient.buildCommand(cfg, model, 0);
        assertEquals(List.of(
                relativeExe.toAbsolutePath().normalize().toString(),
                "-m", model.toString(),
                "--host", "127.0.0.1",
                "--port", "0",
                "-ngl", "auto",
                "-c", "8192",
                "-np", "1",
                "--reasoning", "off",
                "--log-verbosity", "3",
                "--seed", "42"), cmd);
    }

    @Test
    void allowsOnlyLocalTuningExtraArgsAndRejectsRemoteOrLogFlags(@TempDir Path dir) {
        Path model = dir.resolve("dummy.gguf");
        LlamaServerClient.Config allowed = LlamaServerClient.builder(model)
                .executable("llama-server")
                .extraArgs(List.of("--n-cpu-moe", "20", "--threads=4", "--seed", "42", "--mlock"))
                .build();
        assertEquals(6, allowed.extraArgs().size());

        for (List<String> bad : List.of(
                List.of("--connect"),
                List.of("--connect=1"),
                List.of("--connect-code", "abc"),
                List.of("--rpc", "10.0.0.2:50052"),
                List.of("--rpc=10.0.0.2:50052"),
                List.of("--slot-save-path", "/tmp"),
                List.of("--host", "0.0.0.0"),
                List.of("--port", "8080"),
                List.of("--log-disable"),
                List.of("--log-file", "/tmp/log"),
                List.of("--log-jsonl"),
                List.of("-lv", "2"),
                List.of("--log-verbosity=1"),
                List.of("--seed"),
                List.of("--seed", "--mlock"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LlamaServerClient.builder(model)
                            .executable("llama-server")
                            .extraArgs(bad)
                            .build(),
                    "허용되지 않은 추가 인자가 거부되어야 합니다: " + bad);
        }
    }

    @Test
    void extractsListeningPortOnlyFromListeningLine() {
        assertEquals(
                OptionalInt.of(62285),
                LlamaServerClient.extractListeningPort(
                        "0.01.742.071 I srv  server_on_li: listening on http://127.0.0.1:62285"));
        assertEquals(
                OptionalInt.empty(),
                LlamaServerClient.extractListeningPort("metadata: homepage = http://127.0.0.1:9999"));
        assertEquals(OptionalInt.empty(), LlamaServerClient.extractListeningPort("loading model..."));
        assertEquals(
                OptionalInt.empty(),
                LlamaServerClient.extractListeningPort("listening on http://127.0.0.1:99999"));
    }

    @Test
    void doesNotMisclassifyMetalInitFailureAsOom() {
        assertFalse(LlamaChatProtocol.isOutOfMemoryText(
                "0.02.067.689 E ggml_metal_init: error: failed to create command queue\n"
                        + "0.02.067.692 E ggml_backend_metal_device_init_backend: error: failed to allocate context"));
        assertTrue(LlamaChatProtocol.isOutOfMemoryText("CUDA error: out of memory"));
        assertTrue(LlamaChatProtocol.isOutOfMemoryText("kIOGPUCommandBufferCallbackErrorOutOfMemory"));
    }

    @Test
    void throwsOutputTruncatedWhenSchemaResponseHitsLengthLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = startFakeHttp(ex -> {
            calls.incrementAndGet();
            sendJson(ex, 200, """
                    {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"{\\"ok\\":"}}],
                     "usage":{"prompt_tokens":80,"completion_tokens":64}}
                    """);
        });
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-e4b", uriOf(fake), http, Duration.ofSeconds(5), 2, Duration.ofMillis(5))) {
            OutputTruncatedException ex = assertThrows(
                    OutputTruncatedException.class,
                    () -> client.complete(Request.withSchema("sys", "usr", SAMPLE_SCHEMA, 64)));
            assertEquals("{\"ok\":", ex.partialContent());
            assertEquals(new Usage(80, 64), ex.usage());
            assertEquals(1, calls.get());

            // 스키마 없는 일반 호출은 finishReason="length"를 그대로 반환한다.
            Response plain = client.complete(Request.of("sys", "usr"));
            assertEquals("length", plain.finishReason());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void retriesTransient503AndSucceedsOnThirdAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> capturedBodies = new CopyOnWriteArrayList<>();
        HttpServer fake = startFakeHttp(ex -> {
            capturedBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = calls.incrementAndGet();
            if (n <= 2) {
                sendJson(ex, 503, "{\"error\":{\"code\":503,\"message\":\"Loading model\",\"type\":\"unavailable_error\"}}");
                return;
            }
            sendJson(ex, 200, """
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\\"ok\\":true}"}}],
                     "usage":{"prompt_tokens":50,"completion_tokens":8}}
                    """);
        });
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-e4b", uriOf(fake), http, Duration.ofSeconds(5), 2, Duration.ofMillis(10))) {
            Response res = client.complete(Request.withSchema("지침", "테스트 문장", SAMPLE_SCHEMA, 256));
            assertEquals("{\"ok\":true}", res.content());
            assertEquals(new Usage(50, 8), res.usage());
            assertEquals(3, calls.get());
            assertEquals(3, capturedBodies.size());
            JsonNode reqJson = JSON.readTree(capturedBodies.get(0));
            assertEquals("테스트 문장", reqJson.path("messages").get(1).path("content").asString());
            assertEquals("json_schema", reqJson.path("response_format").path("type").asString());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void throwsAfterExhaustingRetriesOn503() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = startFakeHttp(ex -> {
            calls.incrementAndGet();
            sendJson(ex, 503, "{\"error\":{\"message\":\"Server busy\"}}");
        });
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-e4b", uriOf(fake), http, Duration.ofSeconds(5), 2, Duration.ofMillis(5))) {
            IOException ex = assertThrows(IOException.class, () -> client.complete("sys", "usr"));
            assertTrue(ex.getMessage().contains("503"));
            assertEquals(3, calls.get());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void doesNotRetryOnContextLengthExceededAndExtractsTokenCounts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = startFakeHttp(ex -> {
            calls.incrementAndGet();
            sendJson(ex, 400, """
                    {"error":{"code":400,
                      "message":"request (12000 tokens) exceeds the available context size (8192 tokens), try increasing it",
                      "type":"exceed_context_size_error",
                      "n_prompt_tokens":12000,
                      "n_ctx":8192}}
                    """);
        });
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-e4b", uriOf(fake), http, Duration.ofSeconds(5), 3, Duration.ofMillis(5))) {
            ContextLengthExceededException ex = assertThrows(
                    ContextLengthExceededException.class,
                    () -> client.complete("sys", "매우 긴 프롬프트"));
            assertEquals(12000, ex.promptTokens());
            assertEquals(8192, ex.contextSize());
            assertEquals(1, calls.get());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void doesNotRetryOnHttpOutOfMemoryError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = startFakeHttp(ex -> {
            calls.incrementAndGet();
            sendJson(ex, 500, "{\"error\":{\"message\":\"CUDA error: out of memory\"}}");
        });
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-26b-a4b", uriOf(fake), http, Duration.ofSeconds(5), 3, Duration.ofMillis(5))) {
            assertThrows(OutOfMemoryException.class, () -> client.complete("sys", "usr"));
            assertEquals(1, calls.get());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void doesNotRetryOnNonRetryable400Error() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = startFakeHttp(ex -> {
            calls.incrementAndGet();
            sendJson(ex, 400, "{\"error\":{\"message\":\"invalid grammar\"}}");
        });
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-e4b", uriOf(fake), http, Duration.ofSeconds(5), 3, Duration.ofMillis(5))) {
            IOException ex = assertThrows(IOException.class, () -> client.complete("sys", "usr"));
            assertTrue(ex.getMessage().contains("invalid grammar"));
            assertEquals(1, calls.get());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void startsManagedFakeServerRunsCompletionAndClosesCleanly(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("한글 모델").resolve("gemma-4-E4B-it-Q4_K_M.gguf");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "dummy-gguf", StandardCharsets.UTF_8);
        Path argsDump = dir.resolve("args.txt");

        LlamaServerClient client = startManagedFakeClient(model, "ok", argsDump);
        try (client) {
            assertEquals("llama-server/gemma-4-e4b", client.name());
            Response res = client.complete(Request.withSchema("지침", "입력", SAMPLE_SCHEMA, 512));
            assertEquals("{\"ok\":true,\"items\":[1]}", res.content());
            assertEquals(new Usage(120, 15), res.usage());
            assertEquals("stop", res.finishReason());
        }
        // 닫은 뒤 다시 닫아도 안전하고, 요청은 거부해야 한다.
        client.close();
        assertThrows(IllegalStateException.class, () -> client.complete("sys", "usr"));

        List<String> capturedArgs = Files.readAllLines(argsDump, StandardCharsets.UTF_8);
        assertTrue(capturedArgs.contains("-m"));
        assertTrue(capturedArgs.contains(model.toAbsolutePath().normalize().toString()));
        assertTrue(capturedArgs.contains("-ngl"));
        assertTrue(capturedArgs.contains("auto"));
        assertTrue(capturedArgs.contains("-c"));
        assertTrue(capturedArgs.contains("8192"));
        assertTrue(capturedArgs.contains("--reasoning"));
        assertTrue(capturedArgs.contains("off"));
    }

    @Test
    void stopsRetryingWhenInterrupted() throws Exception {
        HttpServer fake = startFakeHttp(ex -> sendJson(ex, 503, "{\"error\":{\"message\":\"busy\"}}"));
        try (HttpClient http = LocalServer.newLoopbackHttpClient(Duration.ofSeconds(2));
                LlamaServerClient client = LlamaServerClient.connectForTest(
                        "gemma-4-e4b", uriOf(fake), http, Duration.ofSeconds(5), 5, Duration.ofSeconds(5))) {
            Thread caller = Thread.currentThread();
            Thread interrupter = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                    caller.interrupt();
                } catch (InterruptedException ignored) {
                    // 테스트 종료
                }
            });
            try {
                assertThrows(InterruptedException.class, () -> client.complete("sys", "usr"));
            } finally {
                interrupter.interrupt();
                Thread.interrupted();
            }
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void failsFastWhenModelFileDoesNotExist(@TempDir Path dir) {
        Path missing = dir.resolve("없는 모델.gguf");
        LlamaServerClient.Config cfg = LlamaServerClient.builder(missing).executable("llama-server").build();
        IOException ex = assertThrows(IOException.class, () -> LlamaServerClient.start(cfg));
        assertTrue(ex.getMessage().contains("없습니다"));
    }

    @Test
    void translatesStartupOomCrashToOutOfMemoryException(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("dummy.gguf");
        Files.writeString(model, "dummy", StandardCharsets.UTF_8);

        assertThrows(OutOfMemoryException.class, () -> startManagedFakeClient(model, "startup-oom", null));
    }

    @Test
    void translatesRuntimeOomCrashToOutOfMemoryException(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("dummy.gguf");
        Files.writeString(model, "dummy", StandardCharsets.UTF_8);

        try (LlamaServerClient client = startManagedFakeClient(model, "runtime-oom", null)) {
            assertThrows(OutOfMemoryException.class, () -> client.complete("sys", "usr"));
        }
    }

    @Test
    void definesDefaultAndOptionalGemma4ModelSpecs() {
        assertEquals(LlamaModel.GEMMA_4_E4B, LlamaModel.DEFAULT);
        assertEquals("llm/gemma-4-E4B-it-Q4_K_M.gguf", LlamaModel.GEMMA_4_E4B.spec().relativePath());
        assertEquals(4_977_171_584L, LlamaModel.GEMMA_4_E4B.spec().expectedSize());
        assertEquals("llm/gemma-4-26B-A4B-it-UD-Q4_K_M.gguf", LlamaModel.GEMMA_4_26B.spec().relativePath());
        assertEquals(16_947_541_728L, LlamaModel.GEMMA_4_26B.spec().expectedSize());
    }

    private static LlamaServerClient startManagedFakeClient(Path model, String mode, Path argsDump) throws IOException {
        LlamaServerClient.Config config = LlamaServerClient.builder(model)
                .executable("llama-server")
                .modelName(LlamaModel.GEMMA_4_E4B.id())
                .startupTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(5))
                .maxRetries(1)
                .retryDelay(Duration.ofMillis(50))
                .build();
        Path normalized = model.toAbsolutePath().normalize();
        return LlamaServerClient.startWithCommand(config, port -> {
            List<String> cmd = new ArrayList<>(FakeLlamaServer.launcherPrefix());
            List<String> llamaArgs = LlamaServerClient.buildCommand(config, normalized, port);
            // 첫 요소("llama-server")를 제외한 실제 CLI 인자를 FakeLlamaServer에 그대로 넘긴다.
            cmd.addAll(llamaArgs.subList(1, llamaArgs.size()));
            cmd.add("--fake-mode");
            cmd.add(mode);
            if (argsDump != null) {
                cmd.add("--dump-args");
                cmd.add(argsDump.toString());
            }
            return cmd;
        });
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private static HttpServer startFakeHttp(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler::handle);
        server.start();
        return server;
    }

    private static URI uriOf(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}

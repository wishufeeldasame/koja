package koja.engine;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlamaServerClientTest}가 실제 자식 프로세스로 띄우는 크로스 플랫폼 가짜 {@code llama-server}.
 * CLI 인자 기록, {@code --port 0} 로그 출력, {@code /health}, {@code /v1/chat/completions}, OOM 종료를 재현한다.
 */
public final class FakeLlamaServer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        String mode = "ok";
        Path argsDump = null;
        int port = 0;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--fake-mode".equals(args[i]) && i + 1 < args.length) {
                mode = args[++i];
            } else if ("--dump-args".equals(args[i]) && i + 1 < args.length) {
                argsDump = Path.of(args[++i]);
            }
        }
        if (argsDump != null) {
            Files.writeString(argsDump, String.join("\n", Arrays.asList(args)), StandardCharsets.UTF_8);
        }
        if ("startup-oom".equals(mode)) {
            System.err.println("ggml_backend_cuda_buffer_type_alloc_buffer: failed to allocate 4800.00 MiB (CUDA error: out of memory)");
            System.err.flush();
            System.exit(1);
            return;
        }

        String finalMode = mode;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        int actualPort = server.getAddress().getPort();
        server.createContext("/health", ex -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/v1/chat/completions", ex -> {
            if ("runtime-oom".equals(finalMode)) {
                System.err.println("CUDA error: out of memory during graph compute");
                System.err.flush();
                ex.close();
                server.stop(0);
                System.exit(137);
                return;
            }
            ObjectNode root = JSON.createObjectNode();
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", "{\"ok\":true,\"items\":[1]}");
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", 120);
            usage.put("completion_tokens", 15);
            byte[] body = JSON.writeValueAsBytes(root);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        // 실제 llama-server가 --port 0으로 뜰 때 출력하는 형식과 같게 맞춘다.
        System.out.println("0.01.000.000 I srv  server_on_li: listening on http://127.0.0.1:" + actualPort);
        System.out.flush();
        Thread.sleep(60_000);
    }

    static List<String> launcherPrefix() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                koja.runtime.AppPaths.executableName("java")).toString();
        return List.of(
                javaBin,
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                FakeLlamaServer.class.getName());
    }
}

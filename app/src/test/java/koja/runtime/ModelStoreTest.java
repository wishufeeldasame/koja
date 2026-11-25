package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelStoreTest {
    private HttpServer server;
    private HttpClient client;
    private URI baseUri;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void downloadsVerifiesAndMovesAtomicallyWithKoreanSpacePath(@TempDir Path temp) throws Exception {
        byte[] payload = sampleBytes(200_000);
        String expectedSha = sha256(payload);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/model.bin", ex -> {
            requests.incrementAndGet();
            serveRange(ex, payload, true);
        });

        Path modelsDir = temp.resolve("모델 저장소").resolve("서브 폴더");
        ModelStore store = new ModelStore(modelsDir, client);
        ModelStore.ModelSpec spec = new ModelStore.ModelSpec(
                "llm/테스트 모델.gguf", baseUri.resolve("/model.bin"), payload.length, expectedSha);

        List<Long> seenBytes = new ArrayList<>();
        Path downloaded = store.ensureDownloaded(spec, (got, total) -> {
            assertEquals(payload.length, total);
            seenBytes.add(got);
        }, null);

        assertEquals(modelsDir.resolve("llm").resolve("테스트 모델.gguf"), downloaded);
        assertArrayEquals(payload, Files.readAllBytes(downloaded));
        assertFalse(Files.exists(store.partPathOf(spec)));
        assertTrue(store.isInstalled(spec));
        assertTrue(store.verify(spec));
        assertEquals(1, requests.get());
        assertEquals(payload.length, seenBytes.getLast());

        // 이미 검증된 모델은 네트워크를 다시 타지 않는다.
        Path again = store.ensureDownloaded(spec);
        assertEquals(downloaded, again);
        assertEquals(1, requests.get());
    }

    @Test
    void checksumMismatchDeletesPartAndLeavesDestinationAbsent(@TempDir Path temp) throws Exception {
        byte[] payload = sampleBytes(64_000);
        String wrongSha = "0".repeat(64);
        server.createContext("/corrupt.bin", ex -> serveRange(ex, payload, true));

        ModelStore store = new ModelStore(temp.resolve("models"), client);
        ModelStore.ModelSpec spec = new ModelStore.ModelSpec(
                "corrupt.bin", baseUri.resolve("/corrupt.bin"), payload.length, wrongSha);

        ModelStore.ChecksumMismatchException ex = assertThrows(
                ModelStore.ChecksumMismatchException.class,
                () -> store.ensureDownloaded(spec));
        assertEquals(wrongSha, ex.expectedSha256());
        assertEquals(sha256(payload), ex.actualSha256());
        assertFalse(Files.exists(store.pathOf(spec)));
        assertFalse(Files.exists(store.partPathOf(spec)));
    }

    @Test
    void cancelMidDownloadPreservesPartAndResumesWithRange(@TempDir Path temp) throws Exception {
        byte[] payload = sampleBytes(300_000);
        String expectedSha = sha256(payload);
        List<String> receivedRanges = new ArrayList<>();
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch unblockFirst = new CountDownLatch(1);

        server.createContext("/resume.bin", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            synchronized (receivedRanges) {
                receivedRanges.add(range == null ? "" : range);
            }
            if (range == null) {
                ex.getResponseHeaders().add("Accept-Ranges", "bytes");
                ex.sendResponseHeaders(200, payload.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(payload, 0, 80_000);
                    out.flush();
                    firstChunkSent.countDown();
                    unblockFirst.await(5, TimeUnit.SECONDS);
                    out.write(payload, 80_000, payload.length - 80_000);
                } catch (InterruptedException | IOException ignored) {
                    // 클라이언트가 취소해 연결을 끊으면 정상 종료한다.
                }
            } else {
                serveRange(ex, payload, true);
            }
        });

        ModelStore store = new ModelStore(temp.resolve("models"), client);
        ModelStore.ModelSpec spec = new ModelStore.ModelSpec(
                "whisper/ggml-test.bin", baseUri.resolve("/resume.bin"), payload.length, expectedSha);

        ModelStore.CancelToken token = new ModelStore.CancelToken();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                store.ensureDownloaded(spec, (got, total) -> {
                    if (got >= 64_000) {
                        token.cancel();
                    }
                }, token);
            } catch (Throwable t) {
                firstError.set(t);
            }
        });

        assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS));
        worker.join(5_000);
        unblockFirst.countDown();
        assertFalse(worker.isAlive());
        assertTrue(firstError.get() instanceof CancellationException, "기대: CancellationException, 실제: " + firstError.get());

        Path part = store.partPathOf(spec);
        assertTrue(Files.isRegularFile(part));
        long partialSize = Files.size(part);
        assertTrue(partialSize > 0 && partialSize < payload.length, "partialSize=" + partialSize);
        assertFalse(Files.exists(store.pathOf(spec)));

        // 이어 받기: Range 헤더로 남은 바이트만 받아 완성한다.
        Path completed = store.ensureDownloaded(spec);
        assertArrayEquals(payload, Files.readAllBytes(completed));
        assertFalse(Files.exists(part));
        assertEquals(2, receivedRanges.size());
        assertEquals("", receivedRanges.get(0));
        assertEquals("bytes=" + partialSize + "-", receivedRanges.get(1));
    }

    @Test
    void resumesAfterTruncatedConnectionDrop(@TempDir Path temp) throws Exception {
        byte[] payload = sampleBytes(180_000);
        String expectedSha = sha256(payload);
        AtomicBoolean failFirst = new AtomicBoolean(true);
        List<String> ranges = new ArrayList<>();

        server.createContext("/flaky.bin", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            synchronized (ranges) {
                ranges.add(range == null ? "" : range);
            }
            if (failFirst.compareAndSet(true, false)) {
                ex.sendResponseHeaders(200, payload.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(payload, 0, 70_000);
                    out.flush();
                }
                return;
            }
            serveRange(ex, payload, true);
        });

        ModelStore store = new ModelStore(temp.resolve("models"), client);
        ModelStore.ModelSpec spec = new ModelStore.ModelSpec(
                "flaky.bin", baseUri.resolve("/flaky.bin"), payload.length, expectedSha);

        assertThrows(IOException.class, () -> store.ensureDownloaded(spec));
        Path part = store.partPathOf(spec);
        assertTrue(Files.isRegularFile(part));
        long saved = Files.size(part);
        assertTrue(saved > 0 && saved < payload.length);

        Path completed = store.ensureDownloaded(spec);
        assertArrayEquals(payload, Files.readAllBytes(completed));
        assertFalse(Files.exists(part));
        assertEquals("bytes=" + saved + "-", ranges.get(1));
    }

    @Test
    void handles200Fallback416AndRedirect(@TempDir Path temp) throws Exception {
        byte[] payload = sampleBytes(100_000);
        String expectedSha = sha256(payload);

        // 1) Range 미지원(200 OK) 시 처음부터 덮어쓰고 완료한다.
        server.createContext("/no-range.bin", ex -> serveRange(ex, payload, false));
        ModelStore store = new ModelStore(temp.resolve("models"), client);
        ModelStore.ModelSpec noRangeSpec = new ModelStore.ModelSpec(
                "no-range.bin", baseUri.resolve("/no-range.bin"), payload.length, expectedSha);
        Files.createDirectories(store.partPathOf(noRangeSpec).getParent());
        Files.write(store.partPathOf(noRangeSpec), Arrays.copyOf(payload, 10_000));
        Path noRangeFile = store.ensureDownloaded(noRangeSpec);
        assertArrayEquals(payload, Files.readAllBytes(noRangeFile));

        // 2) HTTP 416 시 .part를 지우고 0부터 다시 받는다.
        server.createContext("/stale-416.bin", ex -> {
            if (ex.getRequestHeaders().containsKey("Range")) {
                ex.sendResponseHeaders(416, -1);
                ex.close();
            } else {
                serveRange(ex, payload, true);
            }
        });
        ModelStore.ModelSpec spec416 = new ModelStore.ModelSpec(
                "stale-416.bin", baseUri.resolve("/stale-416.bin"), -1, expectedSha);
        Files.write(store.partPathOf(spec416), new byte[500]);
        Path file416 = store.ensureDownloaded(spec416);
        assertArrayEquals(payload, Files.readAllBytes(file416));

        // 3) Hugging Face처럼 302 리다이렉트 뒤 Range 이어 받기가 동작한다.
        server.createContext("/redirect.bin", ex -> {
            ex.getResponseHeaders().add("Location", baseUri.resolve("/cdn/target.bin").toString());
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        List<String> cdnRanges = new ArrayList<>();
        server.createContext("/cdn/target.bin", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            cdnRanges.add(range == null ? "" : range);
            serveRange(ex, payload, true);
        });
        ModelStore.ModelSpec redirectSpec = new ModelStore.ModelSpec(
                "redirect.bin", baseUri.resolve("/redirect.bin"), payload.length, expectedSha);
        Files.write(store.partPathOf(redirectSpec), Arrays.copyOf(payload, 25_000));
        Path redirected = store.ensureDownloaded(redirectSpec);
        assertArrayEquals(payload, Files.readAllBytes(redirected));
        assertEquals(List.of("bytes=25000-"), cdnRanges);
    }

    @Test
    void replacesCorruptedExistingFileAndCompletesFullPartWithoutHttp(@TempDir Path temp) throws Exception {
        byte[] payload = sampleBytes(50_000);
        String expectedSha = sha256(payload);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/good.bin", ex -> {
            hits.incrementAndGet();
            serveRange(ex, payload, true);
        });

        ModelStore store = new ModelStore(temp.resolve("models"), client);
        ModelStore.ModelSpec spec = new ModelStore.ModelSpec(
                "good.bin", baseUri.resolve("/good.bin"), payload.length, expectedSha);
        Path dest = store.pathOf(spec);
        Files.createDirectories(dest.getParent());
        Files.write(dest, new byte[payload.length]); // 크기는 같지만 손상된 파일

        assertFalse(store.verify(spec));
        store.ensureDownloaded(spec);
        assertArrayEquals(payload, Files.readAllBytes(dest));
        assertEquals(1, hits.get());

        // 이미 다 받은 .part가 남아 있으면 네트워크 요청 없이 검증 후 이동한다.
        Files.delete(dest);
        Files.write(store.partPathOf(spec), payload);
        store.ensureDownloaded(spec);
        assertArrayEquals(payload, Files.readAllBytes(dest));
        assertFalse(Files.exists(store.partPathOf(spec)));
        assertEquals(1, hits.get());
    }

    @Test
    void huggingFaceSpecAndPathValidation() {
        String sha = "A".repeat(64);
        ModelStore.ModelSpec spec = ModelStore.ModelSpec.huggingFace(
                "llm/gemma-4-E4B-it-Q4_K_M.gguf",
                "unsloth/gemma-4-E4B-it-GGUF",
                "bfc15c382204943c3a8fff0c750b94ae2364d7a3",
                "gemma-4-E4B-it-Q4_K_M.gguf",
                1234L,
                sha);
        assertEquals(
                URI.create("https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/bfc15c382204943c3a8fff0c750b94ae2364d7a3/gemma-4-E4B-it-Q4_K_M.gguf"),
                spec.uri());
        assertEquals("a".repeat(64), spec.sha256());

        assertThrows(IllegalArgumentException.class, () -> new ModelStore.ModelSpec(
                "../escape.bin", URI.create("https://example.com/a"), 10, sha));
        assertThrows(IllegalArgumentException.class, () -> new ModelStore.ModelSpec(
                "/abs.bin", URI.create("https://example.com/a"), 10, sha));
        assertThrows(IllegalArgumentException.class, () -> new ModelStore.ModelSpec(
                "win\\path.bin", URI.create("https://example.com/a"), 10, sha));
        assertThrows(IllegalArgumentException.class, () -> new ModelStore.ModelSpec(
                "bad-sha.bin", URI.create("https://example.com/a"), 10, "not-a-sha"));
    }

    private static void serveRange(HttpExchange ex, byte[] payload, boolean supportRange) throws IOException {
        String range = ex.getRequestHeaders().getFirst("Range");
        if (supportRange && range != null && range.startsWith("bytes=")) {
            long start = Long.parseLong(range.substring("bytes=".length(), range.indexOf('-')));
            if (start < 0 || start >= payload.length) {
                ex.sendResponseHeaders(416, -1);
                ex.close();
                return;
            }
            int offset = (int) start;
            int len = payload.length - offset;
            ex.getResponseHeaders().add("Content-Range", "bytes " + offset + "-" + (payload.length - 1) + "/" + payload.length);
            ex.sendResponseHeaders(206, len);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(payload, offset, len);
            }
            return;
        }
        ex.sendResponseHeaders(200, payload.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(payload);
        }
    }

    private static byte[] sampleBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}

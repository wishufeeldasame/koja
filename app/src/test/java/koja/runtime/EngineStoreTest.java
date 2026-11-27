package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import koja.runtime.EngineStore.Engine;
import koja.runtime.ModelStore.ModelSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineStoreTest {
    private HttpServer server;
    private HttpClient client;
    private URI base;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void onlyWindowsDownloadsEnginesAndCudaIsShared() {
        Function<String, String> none = name -> null;
        assertEquals(List.of(EngineStore.WHISPER, EngineStore.CUDA), EngineStore.required(true, false, "Windows 11", none));
        assertEquals(List.of(EngineStore.LLAMA, EngineStore.CUDA), EngineStore.required(false, true, "Windows 11", none));
        assertEquals(List.of(EngineStore.WHISPER, EngineStore.CUDA, EngineStore.LLAMA),
                EngineStore.required(true, true, "Windows 11", none));
        assertEquals(List.of(), EngineStore.required(true, true, "Mac OS X", none));
        // 환경 변수로 지정한 실행 파일이 먼저 쓰이므로 받지 않는다.
        assertEquals(List.of(EngineStore.LLAMA, EngineStore.CUDA), EngineStore.required(true, true, "Windows 11",
                Map.of("KOJA_WHISPER_SERVER", "C:\\whisper\\whisper-server.exe")::get));
        // whisper와 cuda는 같은 zip이라 한 번만 받는다.
        assertEquals(735_571_214L, EngineStore.downloadSize(List.of(EngineStore.WHISPER, EngineStore.CUDA)));
    }

    @Test
    void installsFromZipIntoKoreanSpacePathAndDeletesZip(@TempDir Path temp) throws Exception {
        byte[] zip = zip(Map.of("Release/tool.exe", "exe", "Release/lib.dll", "dll", "Release/extra.txt", "x"));
        Engine tool = engine("도구", zip, List.of("Release/tool.exe", "Release/lib.dll"), "v1\n");
        Path dir = temp.resolve("데이터 폴더").resolve("engines");
        try (EngineStore store = new EngineStore(dir, new ModelStore(dir, client))) {
            assertFalse(store.isInstalled(tool));
            store.install(List.of(tool), null, null);
            Path installed = dir.resolve("도구");
            assertTrue(store.isInstalled(tool));
            assertEquals("exe", Files.readString(installed.resolve("tool.exe")));
            assertEquals("MIT", Files.readString(installed.resolve("LICENSE")));
            assertEquals("v1\n", Files.readString(installed.resolve(EngineStore.NOTICE), StandardCharsets.UTF_8));
            assertFalse(Files.exists(installed.resolve("extra.txt")));
            assertFalse(Files.exists(dir.resolve(".downloads").resolve("도구.zip")));
            assertEquals(installed.resolve("tool.exe"), store.executable(tool).orElseThrow());

            // 버전(고지 문구)이 바뀌면 다시 받아 폴더를 바꾸고 이전 폴더는 남기지 않는다.
            Engine next = engine("도구", zip, List.of("Release/tool.exe"), "v2\n");
            assertFalse(store.isInstalled(next));
            store.install(List.of(next), null, null);
            assertTrue(store.isInstalled(next));
            assertFalse(Files.exists(installed.resolve("lib.dll")));
            assertFalse(Files.exists(dir.resolve(".old-도구")));
        }
    }

    @Test
    void installCleansLeftoverZipButKeepsOtherPart(@TempDir Path temp) throws Exception {
        Engine tool = engine("tool", zip(Map.of("Release/tool.exe", "exe")), List.of("Release/tool.exe"), "v1\n");
        try (EngineStore store = new EngineStore(temp, new ModelStore(temp, client))) {
            store.install(List.of(tool), null, null);
            // 지난번 설치 뒤 지우지 못한 zip과 다른 엔진이 받다 만 .part.
            Path leftover = Files.writeString(temp.resolve(".downloads").resolve("tool.zip"), "zip");
            Path otherPart = Files.writeString(temp.resolve(".downloads").resolve("llama.zip.part"), "part");
            store.install(List.of(tool), null, null);
            assertFalse(Files.exists(leftover));
            assertTrue(Files.exists(otherPart));
        }
    }

    @Test
    void userFolderIsNotReplacedOrDownloaded(@TempDir Path temp) throws Exception {
        Engine tool = engine("whisper", zip(Map.of("Release/tool.exe", "exe")), List.of("Release/tool.exe"), "v1\n");
        Path mine = Files.writeString(Files.createDirectories(temp.resolve("whisper")).resolve("내 빌드.exe"), "mine");
        try (EngineStore store = new EngineStore(temp, new ModelStore(temp, client))) {
            IOException e = assertThrows(IOException.class, () -> store.install(List.of(tool), null, null));
            assertTrue(e.getMessage().contains("koja가 설치한"), e.getMessage());
            assertEquals("mine", Files.readString(mine));
            assertFalse(Files.exists(temp.resolve(".downloads").resolve("whisper.zip")));
        }
    }

    @Test
    void linkedEngineFolderIsNotReplaced(@TempDir Path temp) throws Exception {
        Path elsewhere = Files.createDirectories(temp.resolve("다른 드라이브").resolve("cuda"));
        Files.writeString(elsewhere.resolve(EngineStore.MARKER), "cuda\n");
        Path dir = Files.createDirectories(temp.resolve("engines"));
        try {
            Files.createSymbolicLink(dir.resolve("cuda"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 윈도우 CI처럼 링크를 만들 권한이 없으면 건너뛴다.
        }
        Engine tool = engine("cuda", zip(Map.of("Release/tool.exe", "exe")), List.of("Release/tool.exe"), "v1\n");
        try (EngineStore store = new EngineStore(dir, new ModelStore(dir, client))) {
            IOException e = assertThrows(IOException.class, () -> store.install(List.of(tool), null, null));
            assertTrue(e.getMessage().contains("링크"), e.getMessage());
            assertTrue(Files.exists(elsewhere.resolve(EngineStore.MARKER)));
        }
    }

    @Test
    void missingEntryFailsWithoutInstalling(@TempDir Path temp) throws Exception {
        Engine broken = engine("broken", zip(Map.of("Release/tool.exe", "exe")), List.of("Release/none.dll"), "v1\n");
        try (EngineStore store = new EngineStore(temp, new ModelStore(temp, client))) {
            assertThrows(IOException.class, () -> store.install(List.of(broken), null, null));
            assertFalse(store.isInstalled(broken));
            assertFalse(Files.exists(temp.resolve("broken")));
        }
    }

    @Test
    void halfInstalledFolderIsNotInstalled(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("whisper"));
        Files.writeString(dir.resolve("whisper-server.exe"), "x");
        EngineStore store = new EngineStore(temp, null);
        assertFalse(store.isInstalled(EngineStore.WHISPER));
        assertTrue(store.executable(EngineStore.WHISPER).isEmpty());
    }

    // URL·해시·파일 목록이 번들 스크립트와 어긋나면 윈도우만 다른 엔진을 받게 된다.
    @Test
    void specsMatchBundleScripts() throws IOException {
        String whisper = Files.readString(script("bundle_whisper.sh"), StandardCharsets.UTF_8);
        String llama = Files.readString(script("bundle_llama.sh"), StandardCharsets.UTF_8);
        assertSpecIn(whisper, EngineStore.WHISPER);
        assertSpecIn(whisper, EngineStore.CUDA);
        assertSpecIn(llama, EngineStore.LLAMA);
    }

    private static void assertSpecIn(String script, Engine engine) {
        assertTrue(script.contains(engine.archive().sha256()), engine.dir() + " zip SHA-256");
        assertTrue(script.contains(engine.license().sha256()), engine.dir() + " license SHA-256");
        String name = engine.archive().relativePath().substring(engine.archive().relativePath().lastIndexOf('/') + 1);
        assertTrue(script.contains(name.replace("b5907", "$WHISPER_TAG").replace("b12655", "$LLAMA_TAG"))
                || script.contains(name), engine.dir() + " zip 이름");
        for (String entry : engine.entries()) {
            String file = entry.substring(entry.lastIndexOf('/') + 1);
            assertTrue(script.contains(file) || script.contains("ggml-cpu-*.dll") && file.startsWith("ggml-cpu-"),
                    engine.dir() + " 파일 " + file);
        }
    }

    private static Path script(String name) {
        Path here = Path.of("").toAbsolutePath();
        Path inApp = here.resolve("../scripts").resolve(name).normalize();
        return Files.isRegularFile(inApp) ? inApp : here.resolve("scripts").resolve(name);
    }

    private Engine engine(String name, byte[] zip, List<String> entries, String notice) throws Exception {
        String path = "/" + System.nanoTime();
        serve(path + "/a.zip", zip);
        byte[] license = "MIT".getBytes(StandardCharsets.UTF_8);
        serve(path + "/LICENSE", license);
        return new Engine(name, "tool",
                new ModelSpec(".downloads/" + name + ".zip", base.resolve(path + "/a.zip"), zip.length, sha256(zip)),
                entries, new ModelSpec(".downloads/" + name + "-LICENSE", base.resolve(path + "/LICENSE"), -1,
                        sha256(license)), "LICENSE", notice);
    }

    private void serve(String path, byte[] body) {
        server.createContext(path, ex -> {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                out.putNextEntry(new ZipEntry(f.getKey()));
                out.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}

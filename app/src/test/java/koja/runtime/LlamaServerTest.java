package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlamaServerTest {
    @Test
    void downloadedEngineComesAfterEnvBeforePath() {
        Optional<Path> downloaded = Optional.of(Path.of("engines", "llama", "llama-server.exe"));
        assertEquals("/env/llama-server", LlamaServer.executable(null, "/env/llama-server", "Windows 11", downloaded));
        assertEquals(downloaded.get().toString(), LlamaServer.executable(null, null, "Windows 11", downloaded));
    }

    @Test
    void bundledOnMac(@TempDir Path dir) throws IOException {
        Path contents = dir.resolve("앱 폴더").resolve("koja.app").resolve("Contents");
        Path launcher = contents.resolve("MacOS").resolve("koja");
        Path server = touch(contents.resolve("llama").resolve("llama-server"));
        assertEquals(server.toString(), LlamaServer.executable(launcher.toString(), "/env/llama-server", "Mac OS X"));
    }

    @Test
    void bundledOnWindows(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("앱 폴더").resolve("koja");
        Path server = touch(root.resolve("llama").resolve("llama-server.exe"));
        assertEquals(server.toString(), LlamaServer.executable(root.resolve("koja.exe").toString(), null, "Windows 11"));
    }

    @Test
    void fallsBackToEnvThenPath(@TempDir Path dir) {
        String launcher = dir.resolve("koja.app/Contents/MacOS/koja").toString();
        assertEquals("/env/llama-server", LlamaServer.executable(launcher, " /env/llama-server ", "Mac OS X"));
        assertEquals("/env/llama-server", LlamaServer.executable(null, "/env/llama-server", "Mac OS X"));
        assertEquals("llama-server", LlamaServer.executable(launcher, " ", "Mac OS X"));
        assertEquals("llama-server.exe", LlamaServer.executable(null, null, "Windows 11"));
    }

    @Test
    void workingDirectoryReturnsParentOnlyForExistingFile(@TempDir Path dir) throws IOException {
        Path server = touch(dir.resolve("번들 폴더").resolve("llama-server"));
        assertEquals(server.getParent().toAbsolutePath(), LlamaServer.workingDirectory(server.toString()));
        assertNull(LlamaServer.workingDirectory("llama-server"));
        assertNull(LlamaServer.workingDirectory(dir.resolve("missing").toString()));
        assertNull(LlamaServer.workingDirectory(" "));
    }

    @Test
    void resolvesRelativePathToAbsoluteAndPreservesBareCommandName() {
        String bare = LlamaServer.resolveExecutable(" llama-server ");
        assertEquals("llama-server", bare);

        Path relative = Path.of("한글 폴더", "하위 폴더", "llama-server");
        String resolved = LlamaServer.resolveExecutable(relative.toString());
        assertEquals(relative.toAbsolutePath().normalize().toString(), resolved);
    }

    @Test
    void clearsLlamaArgAndLogEnvironmentPrefixes() {
        assertEquals(
                List.of("LLAMA_ARG_", "LLAMA_LOG_", "LLAMA_API_KEY"),
                LlamaServer.clearedEnvironmentPrefixes());
    }

    @Test
    void prependsSiblingCudaDirOnWindowsPreservingPathKeyCase(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("앱 폴더").resolve("koja");
        Path exe = touch(root.resolve("llama").resolve("llama-server.exe"));
        Path cuda = Files.createDirectories(root.resolve("cuda"));

        Map<String, String> env = LlamaServer.processEnvironment(
                exe.toString(), "Windows 11", Map.of("Path", "C:\\Windows", "TEMP", "t"));
        assertEquals(Map.of("Path", cuda + ";C:\\Windows"), env);

        assertEquals(
                Map.of("PATH", cuda.toString()),
                LlamaServer.processEnvironment(exe.toString(), "Windows 11", Map.of()));
        assertEquals(
                Map.of("Path", cuda.toString()),
                LlamaServer.processEnvironment(exe.toString(), "Windows 11", Map.of("Path", "  ")));
    }

    @Test
    void prependsSiblingCudaDirForBareCommandFoundOnWindowsPath(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("앱 폴더").resolve("koja");
        Path llamaDir = root.resolve("llama");
        touch(llamaDir.resolve("llama-server.exe"));
        Path cuda = Files.createDirectories(root.resolve("cuda"));

        String quotedPath = "\"" + llamaDir + "\";C:\\Windows";
        assertEquals(
                Map.of("Path", cuda + ";" + quotedPath),
                LlamaServer.processEnvironment("llama-server.exe", "Windows 11", Map.of("Path", quotedPath)));
        assertEquals(
                Map.of("Path", cuda + ";" + llamaDir),
                LlamaServer.processEnvironment("llama-server", "Windows 11", Map.of("Path", llamaDir.toString())));

        // PATH 앞쪽에 형제 cuda/가 없는 llama-server.exe가 먼저 잡히면 뒤쪽 번들의 cuda/를 붙이지 않는다.
        Path systemLlamaDir = dir.resolve("system-llama");
        touch(systemLlamaDir.resolve("llama-server.exe"));
        String shadowedPath = systemLlamaDir + ";" + llamaDir;
        assertEquals(
                Map.of(),
                LlamaServer.processEnvironment("llama-server.exe", "Windows 11", Map.of("Path", shadowedPath)));
    }

    @Test
    void skipsCudaPathOnMacWithoutCudaDirOrWhenNotFoundOnPath(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("koja");
        Path exe = touch(root.resolve("llama").resolve("llama-server.exe"));

        assertEquals(Map.of(), LlamaServer.processEnvironment(exe.toString(), "Windows 11", Map.of("PATH", "x")));
        Files.createDirectories(root.resolve("cuda"));
        assertEquals(Map.of(), LlamaServer.processEnvironment(exe.toString(), "Mac OS X", Map.of("PATH", "x")));
        assertEquals(Map.of(), LlamaServer.processEnvironment("llama-server.exe", "Windows 11", Map.of("PATH", "x")));
        assertEquals(Map.of(), LlamaServer.processEnvironment(" ", "Windows 11", Map.of("PATH", "x")));
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}

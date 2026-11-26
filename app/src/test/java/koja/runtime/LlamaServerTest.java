package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlamaServerTest {
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
                java.util.List.of("LLAMA_ARG_", "LLAMA_LOG_", "LLAMA_API_KEY"),
                LlamaServer.clearedEnvironmentPrefixes());
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}

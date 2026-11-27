package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperServerTest {
    @Test
    void cudaDirGoesFirstOnWindowsPath(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("앱 폴더/koja");
        Path exe = root.resolve("whisper").resolve("whisper-server.exe");
        Path cuda = Files.createDirectories(root.resolve("cuda"));
        Map<String, String> env = WhisperServer.environment(exe, "Windows 11", Map.of("Path", "C:\\Windows", "TEMP", "t"));
        assertEquals(1, env.size());
        String path = env.get("Path");
        assertTrue(path.startsWith(cuda.toString()), path);
        assertTrue(path.endsWith("C:\\Windows"), path);
        assertEquals(Map.of("PATH", cuda.toString()), WhisperServer.environment(exe, "Windows 11", Map.of()));
    }

    @Test
    void noPathChangeWithoutCudaOrOnMac(@TempDir Path dir) throws IOException {
        Path exe = dir.resolve("koja/whisper/whisper-server.exe");
        assertEquals(Map.of(), WhisperServer.environment(exe, "Windows 11", Map.of("PATH", "x")));
        Files.createDirectories(dir.resolve("koja/cuda"));
        assertEquals(Map.of(), WhisperServer.environment(exe, "Mac OS X", Map.of("PATH", "x")));
    }

    @Test
    void downloadedEngineOnlyWithoutBundleOrEnv() {
        Optional<Path> bundled = Optional.of(Path.of("bundled", "whisper-server"));
        Optional<Path> downloaded = Optional.of(Path.of("engines", "whisper", "whisper-server.exe"));
        assertEquals(bundled, WhisperServer.local(bundled, null, () -> downloaded));
        assertEquals(Optional.empty(), WhisperServer.local(Optional.empty(), "/env/whisper-server", () -> downloaded));
        assertEquals(downloaded, WhisperServer.local(Optional.empty(), " ", () -> downloaded));
    }

    @Test
    void fallsBackToEnvThenPath() {
        assertEquals(Path.of("/env/whisper-server").toAbsolutePath().toString(), WhisperServer.fallback("/env/whisper-server"));
        assertEquals("whisper-server", WhisperServer.fallback(" "));
        assertEquals("whisper-server", WhisperServer.fallback(null));
        assertEquals("my-whisper", WhisperServer.fallback("my-whisper"));
        assertEquals(Path.of("build", "whisper-server").toAbsolutePath().toString(),
                WhisperServer.fallback(Path.of("build", "whisper-server").toString()));
    }
}

package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {
    @Test
    void windowsPathsUseLocalAppData(@TempDir Path temp) {
        Path localAppData = temp.resolve("홍 길동").resolve("AppData").resolve("Local");
        assertEquals(
                localAppData.resolve("koja"),
                AppPaths.dataDir("Windows 11", localAppData.toString(), null, temp.toString()));
        assertEquals(
                localAppData.resolve("koja").resolve("models"),
                AppPaths.modelsDir("Windows 11", localAppData.toString(), null, temp.toString()));
    }

    @Test
    void windowsFallsBackToUserHomeWhenLocalAppDataMissing(@TempDir Path temp) {
        Path home = temp.resolve("사용자 폴더");
        assertEquals(
                home.resolve("AppData").resolve("Local").resolve("koja").resolve("models"),
                AppPaths.modelsDir("Windows 10", "  ", null, home.toString()));
    }

    @Test
    void macPathsUseApplicationSupport(@TempDir Path temp) {
        Path home = temp.resolve("사용자 홈");
        assertEquals(
                home.resolve("Library").resolve("Application Support").resolve("koja"),
                AppPaths.dataDir("Mac OS X", "/ignored", "/ignored", home.toString()));
        assertEquals(
                home.resolve("Library").resolve("Application Support").resolve("koja").resolve("models"),
                AppPaths.modelsDir("Mac OS X", null, null, home.toString()));
    }

    @Test
    void linuxPathsUseXdgOrLocalShare(@TempDir Path temp) {
        Path xdg = temp.resolve("xdg data");
        Path home = temp.resolve("home");
        assertEquals(
                xdg.resolve("koja").resolve("models"),
                AppPaths.modelsDir("Linux", null, xdg.toString(), home.toString()));
        assertEquals(
                home.resolve(".local").resolve("share").resolve("koja").resolve("models"),
                AppPaths.modelsDir("Linux", null, null, home.toString()));
    }

    @Test
    void executableNamePerOs() {
        assertEquals("whisper-server.exe", AppPaths.executableName("whisper-server", "Windows 11"));
        assertEquals("llama-server.exe", AppPaths.executableName("llama-server.exe", "Windows 11"));
        assertEquals("whisper-server", AppPaths.executableName("whisper-server", "Mac OS X"));
        assertEquals("llama-server", AppPaths.executableName("llama-server", "Linux"));
        assertThrows(IllegalArgumentException.class, () -> AppPaths.executableName(" ", "Mac OS X"));
    }

    @Test
    void bundledExecutablePerOs(@TempDir Path dir) throws IOException {
        Path macContents = dir.resolve("앱 폴더").resolve("koja.app").resolve("Contents");
        Path macLauncher = macContents.resolve("MacOS").resolve("koja");
        Path macServer = touch(macContents.resolve("whisper").resolve("whisper-server"));
        assertEquals(
                Optional.of(macServer),
                AppPaths.bundledExecutable(macLauncher.toString(), "whisper", "whisper-server", "Mac OS X"));

        Path winRoot = dir.resolve("앱 폴더").resolve("koja");
        Path winLauncher = winRoot.resolve("koja.exe");
        Path winServer = touch(winRoot.resolve("llama").resolve("llama-server.exe"));
        assertEquals(
                Optional.of(winServer),
                AppPaths.bundledExecutable(winLauncher.toString(), "llama", "llama-server", "Windows 11"));

        assertTrue(AppPaths.bundledExecutable(null, "llama", "llama-server", "Mac OS X").isEmpty());
        assertFalse(AppPaths.bundledExecutable(winLauncher.toString(), "missing", "foo", "Windows 11").isPresent());
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}

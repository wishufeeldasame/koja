package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
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
    void dataDirPrefersOverrideThenParentOfWindowsProgramDir(@TempDir Path temp) {
        Path local = temp.resolve("AppData").resolve("Local");
        Path root = temp.resolve("F 드라이브").resolve("koja");
        String launcher = root.resolve("program").resolve("koja.exe").toString();
        Path override = temp.resolve("내 데이터");
        assertEquals(override, AppPaths.dataDir(override.toString(), launcher, "Windows 11", local.toString(), null,
                temp.toString(), p -> true));
        // 설치 폴더(program\)는 제거·업그레이드 때 통째로 지워져 그 부모에 둔다.
        assertEquals(root, AppPaths.dataDir(null, launcher, "Windows 11", local.toString(), null,
                temp.toString(), p -> true));
        // program\ 없이 고른 다른 폴더는 OS 기본 폴더.
        assertEquals(local.resolve("koja"), AppPaths.dataDir(null, root.resolve("koja.exe").toString(), "Windows 11",
                local.toString(), null, temp.toString(), p -> true));
        // 설치 폴더가 %LOCALAPPDATA%\koja 자체면 기본 폴더도 지워질 자리라 옆 koja-data.
        assertEquals(local.resolve("koja-data"), AppPaths.dataDir(null, local.resolve("koja").resolve("koja.exe")
                .toString(), "Windows 11", local.toString(), null, temp.toString(), p -> true));
        // Program Files처럼 쓸 수 없는 설치 폴더와 개발 실행(jpackage.app-path 없음)은 %LOCALAPPDATA%\koja.
        assertEquals(local.resolve("koja"), AppPaths.dataDir(" ", launcher, "Windows 11", local.toString(), null,
                temp.toString(), p -> false));
        assertEquals(local.resolve("koja"), AppPaths.dataDir(null, null, "Windows 11", local.toString(), null,
                temp.toString(), p -> true));
        // 맥 .app은 읽기 전용으로 두고 Application Support를 쓴다.
        assertEquals(temp.resolve("Library").resolve("Application Support").resolve("koja"),
                AppPaths.dataDir(null, "/Applications/koja.app/Contents/MacOS/koja", "Mac OS X", null, null,
                        temp.toString(), p -> true));
    }

    @Test
    void invalidDataDirOverrideExplainsVariable() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppPaths.dataDir("bad\0path",
                null, "Mac OS X", null, null, "/home", p -> true));
        assertTrue(e.getMessage().contains("KOJA_DATA_DIR"), e.getMessage());
    }

    @Test
    void pipelineLockIgnoresDataDirOverride() {
        assertTrue(AppPaths.pipelineLockFile().endsWith(Path.of("koja", "pipeline.lock")));
    }

    @Test
    void writableProbeLeavesNoFile(@TempDir Path temp) throws IOException {
        assertTrue(AppPaths.isWritableDirectory(temp));
        try (Stream<Path> files = Files.list(temp)) {
            assertEquals(0, files.count());
        }
        assertFalse(AppPaths.isWritableDirectory(temp.resolve("없는 폴더")));
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

    @Test
    void bundledDirectoryPerOs(@TempDir Path dir) throws IOException {
        Path macContents = dir.resolve("앱 폴더").resolve("koja.app").resolve("Contents");
        Path macLauncher = macContents.resolve("MacOS").resolve("koja");
        Path macNative = Files.createDirectories(macContents.resolve("sherpa-native"));
        assertEquals(
                Optional.of(macNative),
                AppPaths.bundledDirectory(macLauncher.toString(), "sherpa-native", "Mac OS X"));

        Path winRoot = dir.resolve("앱 폴더").resolve("koja");
        Path winLauncher = winRoot.resolve("koja.exe");
        Path winNative = Files.createDirectories(winRoot.resolve("sherpa-native"));
        assertEquals(
                Optional.of(winNative),
                AppPaths.bundledDirectory(winLauncher.toString(), "sherpa-native", "Windows 11"));

        assertTrue(AppPaths.bundledDirectory(null, "sherpa-native", "Mac OS X").isEmpty());
        assertTrue(AppPaths.bundledDirectory(winLauncher.toString(), "missing", "Windows 11").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> AppPaths.bundledDirectory(winLauncher.toString(), " ", "Windows 11"));
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}

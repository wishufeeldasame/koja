package koja.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/** OS별 사용자 데이터·모델 폴더와 내장 바이너리 위치(설계 3장). OS 분기는 runtime 패키지에만 둔다. */
public final class AppPaths {
    public static final String APP_DIR_NAME = "koja";
    public static final String MODELS_DIR_NAME = "models";
    public static final String ENV_DATA_DIR = "KOJA_DATA_DIR";

    private AppPaths() {}

    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name"));
    }

    static boolean isWindows(String osName) {
        return normalizeOs(osName).contains("win");
    }

    public static boolean isMac() {
        return isMac(System.getProperty("os.name"));
    }

    static boolean isMac(String osName) {
        return normalizeOs(osName).contains("mac");
    }

    /** 윈도우는 {@code .exe}를 붙이고, 맥·기타 OS는 그대로 반환한다. */
    public static String executableName(String baseName) {
        return executableName(baseName, System.getProperty("os.name"));
    }

    static String executableName(String baseName, String osName) {
        if (baseName == null || baseName.isBlank()) {
            throw new IllegalArgumentException("실행 파일 이름이 비어 있습니다");
        }
        if (isWindows(osName) && !baseName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return baseName + ".exe";
        }
        return baseName;
    }

    /**
     * 모델·엔진·잠금 파일을 두는 한 폴더(D-076). {@code KOJA_DATA_DIR} → 윈도우 설치판은 쓸 수 있는 설치 폴더
     * → OS 기본 폴더(Win {@code %LOCALAPPDATA%\koja}, Mac {@code ~/Library/Application Support/koja}) 순서.
     */
    public static Path dataDir() {
        return DataDirHolder.DIR;
    }

    // 설치 폴더 쓰기 검사는 파일을 만들어 보므로 한 번만 한다.
    private static final class DataDirHolder {
        static final Path DIR = dataDir(System.getenv(ENV_DATA_DIR), System.getProperty("jpackage.app-path"),
                System.getProperty("os.name"), System.getenv("LOCALAPPDATA"), System.getenv("XDG_DATA_HOME"),
                System.getProperty("user.home"), AppPaths::isWritableDirectory);
    }

    static Path dataDir(String override, String appPath, String osName, String localAppData, String xdgDataHome,
            String userHome, Predicate<Path> writable) {
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        // 윈도우 사용자별 설치(%LOCALAPPDATA%\koja, 또는 설치 창에서 고른 F:\koja)는 앱과 받은 파일을 한 폴더에 둔다.
        // Program Files처럼 쓸 수 없는 곳을 골랐으면 OS 기본 폴더로 간다.
        if (isWindows(osName) && appPath != null && !appPath.isBlank()) {
            Path installDir = Path.of(appPath).toAbsolutePath().getParent();
            if (installDir != null && writable.test(installDir)) {
                return installDir;
            }
        }
        return dataDir(osName, localAppData, xdgDataHome, userHome);
    }

    /** 윈도우 ACL·읽기 전용 매체는 {@link Files#isWritable}이 맞지 않을 수 있어 파일을 실제로 만들어 본다. */
    static boolean isWritableDirectory(Path dir) {
        try {
            Path probe = Files.createTempFile(dir, ".koja-write-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    static Path dataDir(String osName, String localAppData, String xdgDataHome, String userHome) {
        if (isWindows(osName)) {
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData).resolve(APP_DIR_NAME);
            }
            return requireUserHome(userHome).resolve("AppData").resolve("Local").resolve(APP_DIR_NAME);
        }
        if (isMac(osName)) {
            return requireUserHome(userHome)
                    .resolve("Library")
                    .resolve("Application Support")
                    .resolve(APP_DIR_NAME);
        }
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome).resolve(APP_DIR_NAME);
        }
        return requireUserHome(userHome).resolve(".local").resolve("share").resolve(APP_DIR_NAME);
    }

    /** 모델 저장 폴더: {@code <데이터 폴더>/models}. */
    public static Path modelsDir() {
        return dataDir().resolve(MODELS_DIR_NAME);
    }

    /** 받은 엔진 폴더: {@code <데이터 폴더>/engines} (윈도우만 쓴다, D-077). */
    public static Path enginesDir() {
        return dataDir().resolve(EngineStore.DIR_NAME);
    }

    static Path modelsDir(String osName, String localAppData, String xdgDataHome, String userHome) {
        return dataDir(osName, localAppData, xdgDataHome, userHome).resolve(MODELS_DIR_NAME);
    }

    /**
     * jpackage {@code --app-content}로 번들된 폴더를 찾는다.
     * 맥은 {@code Contents/<dirName>}, 윈도우는 {@code <설치 폴더>/<dirName>}.
     */
    public static Optional<Path> bundledDirectory(String dirName) {
        return bundledDirectory(
                System.getProperty("jpackage.app-path"),
                dirName,
                System.getProperty("os.name"));
    }

    static Optional<Path> bundledDirectory(String appPath, String dirName, String osName) {
        if (dirName == null || dirName.isBlank()) {
            throw new IllegalArgumentException("번들 폴더 이름이 비어 있습니다");
        }
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        Path launcherDir = Path.of(appPath).toAbsolutePath().getParent();
        if (launcherDir == null) {
            return Optional.empty();
        }
        Path candidate = isMac(osName)
                ? launcherDir.resolveSibling(dirName)
                : launcherDir.resolve(dirName);
        return Files.isDirectory(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * jpackage {@code --app-content}로 번들된 실행 파일을 찾는다.
     * 맥은 {@code Contents/<dirName>/<baseName>}, 윈도우는 {@code <설치 폴더>/<dirName>/<baseName>.exe}.
     */
    public static Optional<Path> bundledExecutable(String dirName, String baseName) {
        return bundledExecutable(
                System.getProperty("jpackage.app-path"),
                dirName,
                baseName,
                System.getProperty("os.name"));
    }

    static Optional<Path> bundledExecutable(String appPath, String dirName, String baseName, String osName) {
        String exeName = executableName(baseName, osName);
        return bundledDirectory(appPath, dirName, osName)
                .map(dir -> dir.resolve(exeName))
                .filter(Files::isRegularFile);
    }

    private static Path requireUserHome(String userHome) {
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("사용자 홈 디렉터리(user.home)를 알 수 없습니다");
        }
        return Path.of(userHome);
    }

    private static String normalizeOs(String osName) {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }
}

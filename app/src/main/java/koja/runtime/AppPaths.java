package koja.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** OS별 사용자 데이터·모델 폴더와 내장 바이너리 위치(설계 3장). OS 분기는 runtime 패키지에만 둔다. */
public final class AppPaths {
    public static final String APP_DIR_NAME = "koja";
    public static final String MODELS_DIR_NAME = "models";

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

    /** 사용자 데이터 폴더: Win {@code %LOCALAPPDATA%\koja}, Mac {@code ~/Library/Application Support/koja}. */
    public static Path dataDir() {
        return dataDir(
                System.getProperty("os.name"),
                System.getenv("LOCALAPPDATA"),
                System.getenv("XDG_DATA_HOME"),
                System.getProperty("user.home"));
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

    /** 모델 저장 폴더: Win {@code %LOCALAPPDATA%\koja\models}, Mac {@code ~/Library/Application Support/koja/models}. */
    public static Path modelsDir() {
        return dataDir().resolve(MODELS_DIR_NAME);
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

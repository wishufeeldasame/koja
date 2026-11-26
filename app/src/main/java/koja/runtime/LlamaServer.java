package koja.runtime;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code llama-server} 실행 파일 위치와 OS별 실행 환경(D-005, D-060, D-065, D-069).
 * 번들 위치와 OS 분기는 {@code runtime} 패키지 한곳에 모은다.
 */
public final class LlamaServer {
    public static final String BUNDLE_DIR = "llama";
    public static final String CUDA_DIR = "cuda";
    public static final String EXECUTABLE_BASE = "llama-server";
    public static final String ENV_EXECUTABLE = "KOJA_LLAMA_SERVER";

    // 부모 셸의 LLAMA_ARG_CONNECT(원격 터널)·LLAMA_ARG_LOG_VERBOSITY(시작 로그 억제)·LLAMA_API_KEY(인증 강제)가
    // 관리되는 로컬 서버에 상속되지 않도록 제거한다(D-051, D-066).
    private static final List<String> CLEARED_ENV_PREFIXES = List.of("LLAMA_ARG_", "LLAMA_LOG_", "LLAMA_API_KEY");

    private LlamaServer() {}

    /** 설치판에 들어 있는 llama-server → 환경 변수 {@code KOJA_LLAMA_SERVER} → PATH 순서. */
    public static String executable() {
        return executable(
                System.getProperty("jpackage.app-path"),
                System.getenv(ENV_EXECUTABLE),
                System.getProperty("os.name"));
    }

    static String executable(String appPath, String env, String osName) {
        Optional<Path> bundled = AppPaths.bundledExecutable(appPath, BUNDLE_DIR, EXECUTABLE_BASE, osName);
        if (bundled.isPresent()) {
            return bundled.get().toString();
        }
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return AppPaths.executableName(EXECUTABLE_BASE, osName);
    }

    /**
     * 디렉터리 구분자가 들어간 실행 파일 경로(상대 경로 포함)는 작업 폴더 변경 전 절대 경로로 정규화한다.
     * PATH 검색용 단일 이름(예: {@code llama-server})은 그대로 돌려준다.
     */
    public static String resolveExecutable(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("llama-server 실행 파일 경로가 비어 있습니다");
        }
        String trimmed = executable.trim();
        Path path = Path.of(trimmed);
        if (path.getParent() != null) {
            return path.toAbsolutePath().normalize().toString();
        }
        return trimmed;
    }

    /**
     * 실행 파일이 구체적인 파일 경로를 가리키면 그 상위 폴더를 작업 디렉터리로 돌려준다.
     * PATH 검색 이름만 넘겼을 때는 {@code null}을 돌려줘 기본 작업 디렉터리를 유지한다.
     */
    public static Path workingDirectory(String executable) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        Path path = Path.of(executable.trim());
        if (path.getParent() != null && Files.isRegularFile(path)) {
            return path.toAbsolutePath().normalize().getParent();
        }
        return null;
    }

    /** 관리되는 {@code llama-server} 자식 프로세스에서 제거할 부모 환경 변수 접두사. */
    public static List<String> clearedEnvironmentPrefixes() {
        return CLEARED_ENV_PREFIXES;
    }

    /**
     * OS별 추가 프로세스 환경 변수.
     * 윈도우 설치판은 CUDA 런타임 DLL이 {@code llama/} 옆 {@code cuda/}에 있어 {@code PATH} 맨 앞에 붙인다(D-060, D-069).
     */
    public static Map<String, String> processEnvironment(String executable) {
        return processEnvironment(executable, System.getProperty("os.name"), System.getenv());
    }

    /**
     * 윈도우 환경 변수 이름은 보통 {@code Path}이고 Java 자식 환경 맵은 대소문자를 가리므로,
     * 부모 환경의 실제 키 이름으로 덮어써 중복 {@code Path}/{@code PATH}를 막는다(D-060).
     */
    static Map<String, String> processEnvironment(String executable, String osName, Map<String, String> env) {
        if (!AppPaths.isWindows(osName) || executable == null || executable.isBlank()) {
            return Map.of();
        }
        Map<String, String> parentEnv = env == null ? Map.of() : env;
        String key = parentEnv.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String path = parentEnv.get(key);
        Path parent = resolveExecutableParent(executable.trim(), path);
        if (parent == null) {
            return Map.of();
        }
        Path cuda = parent.resolveSibling(CUDA_DIR);
        if (!Files.isDirectory(cuda)) {
            return Map.of();
        }
        // 윈도우 PATH 구분자는 항상 ';'다(맥 단위 테스트에서도 실제 윈도우 값을 검증한다).
        String value = path == null || path.isBlank() ? cuda.toString() : cuda + ";" + path;
        return Map.of(key, value);
    }

    private static Path resolveExecutableParent(String executable, String windowsPath) {
        Path exePath = Path.of(executable);
        if (exePath.getParent() != null) {
            return exePath.toAbsolutePath().normalize().getParent();
        }
        if (windowsPath == null || windowsPath.isBlank()) {
            return null;
        }
        List<String> candidates = executable.toLowerCase(Locale.ROOT).endsWith(".exe")
                ? List.of(executable)
                : List.of(executable, executable + ".exe");
        for (String entry : windowsPath.split(";")) {
            String dirText = entry.trim();
            if (dirText.length() >= 2 && dirText.startsWith("\"") && dirText.endsWith("\"")) {
                dirText = dirText.substring(1, dirText.length() - 1).trim();
            }
            if (dirText.isEmpty()) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(dirText);
            } catch (InvalidPathException ignored) {
                continue;
            }
            for (String candidate : candidates) {
                Path file = dir.resolve(candidate);
                if (Files.isRegularFile(file)) {
                    return file.toAbsolutePath().normalize().getParent();
                }
            }
        }
        return null;
    }
}

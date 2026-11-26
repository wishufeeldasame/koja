package koja.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code llama-server} 실행 파일 위치와 OS별 실행 환경(D-005, D-065).
 * 번들 위치와 OS 분기는 {@code runtime} 패키지 한곳에 모은다.
 */
public final class LlamaServer {
    public static final String BUNDLE_DIR = "llama";
    public static final String EXECUTABLE_BASE = "llama-server";
    public static final String ENV_EXECUTABLE = "KOJA_LLAMA_SERVER";

    // 부모 셸의 LLAMA_ARG_CONNECT(원격 터널)·LLAMA_ARG_LOG_VERBOSITY(시작 로그 억제) 등이
    // 관리되는 로컬 서버에 상속되지 않도록 제거한다(D-051, D-066).
    private static final List<String> CLEARED_ENV_PREFIXES = List.of("LLAMA_ARG_", "LLAMA_LOG_");

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

    /** OS별 추가 프로세스 환경 변수. */
    public static Map<String, String> processEnvironment(String executable) {
        return Map.of();
    }
}

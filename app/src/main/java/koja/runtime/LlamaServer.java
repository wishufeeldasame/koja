package koja.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
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
     * 실행 파일이 구체적인 파일 경로를 가리키면 그 상위 폴더를 작업 디렉터리로 돌려준다.
     * PATH 검색 이름만 넘겼을 때는 {@code null}을 돌려줘 기본 작업 디렉터리를 유지한다.
     */
    public static Path workingDirectory(String executable) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        Path path = Path.of(executable);
        if (path.getParent() != null && Files.isRegularFile(path)) {
            return path.toAbsolutePath().getParent();
        }
        return null;
    }

    /** OS별 추가 프로세스 환경 변수. */
    public static Map<String, String> processEnvironment(String executable) {
        return Map.of();
    }
}

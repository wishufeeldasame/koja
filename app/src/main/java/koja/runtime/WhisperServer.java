package koja.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import koja.runtime.ModelStore.ModelSpec;

/** whisper-server 위치(맥 번들, 윈도우 받아 둔 엔진)·실행 인자·모델(D-059, D-060, D-077). */
public final class WhisperServer {
    /** large-v3-turbo. M0 PoC와 같은 파일(poc/win_setup.py). */
    public static final ModelSpec MODEL = ModelSpec.huggingFace("whisper/ggml-large-v3-turbo.bin",
            "ggerganov/whisper.cpp", "5359861c739e955e79d9a303bcbc70fb988958b1", "ggml-large-v3-turbo.bin",
            1_624_555_275L, "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69");

    public static final String ENV_EXECUTABLE = "KOJA_WHISPER_SERVER";
    static final String DIR = "whisper";
    static final String CUDA_DIR = "cuda";

    private WhisperServer() {}

    /** 경로가 든 값은 작업 폴더(모델 폴더)로 옮기기 전에 지금 폴더 기준 절대 경로로 바꾼다. 이름만이면 PATH에서 찾는다. */
    static String fallback(String env) {
        if (env == null || env.isBlank()) {
            return "whisper-server";
        }
        Path p = Path.of(env);
        return p.getParent() == null ? env : p.toAbsolutePath().toString();
    }

    /**
     * 서버를 띄우고 모델이 올라갈 때까지 기다린다. 다 쓰면 close()로 내려 GPU를 비운다(절대 규칙 5).
     * 실행 파일은 설치판에 들어 있는 것 → 환경 변수 {@code KOJA_WHISPER_SERVER} → 받아 둔 엔진(engines/whisper) → PATH 순서로 찾는다(D-078).
     */
    public static LocalServer start(Path model) throws IOException {
        String env = System.getenv(ENV_EXECUTABLE);
        Optional<Path> bundled = local(AppPaths.bundledExecutable(DIR, "whisper-server"), env,
                () -> EngineStore.installedExecutable(EngineStore.WHISPER));
        String exe = bundled.map(Path::toString).orElseGet(() -> fallback(env));
        String threads = Integer.toString(Math.min(8, Runtime.getRuntime().availableProcessors()));
        // -nlp: verbose_json마다 언어 확률을 따로 한 번 더 계산하지 않게 한다. 언어는 language 필드로 충분하다.
        // 윈도우 whisper-server는 인자를 ANSI 코드 페이지로 받아 UTF-8로 해석해서 한글 경로가 깨진다(whisper.cpp #4018).
        // 모델 폴더를 작업 폴더로 두고 ASCII 파일 이름만 넘긴다.
        return LocalServer.builder("whisper-server", port -> List.of(exe, "-m", model.getFileName().toString(),
                        "--host", "127.0.0.1", "--port", Integer.toString(port), "-t", threads, "-nlp"))
                .workingDirectory(model.toAbsolutePath().getParent())
                .environment(bundled.map(p -> environment(p, System.getProperty("os.name"), System.getenv()))
                        .orElse(Map.of()))
                .healthCheck(LocalServer.HealthCheck.httpOk("/health"))
                .startupTimeout(Duration.ofMinutes(3))
                .start();
    }

    /** 번들이 없고 환경 변수도 없을 때만 받아 둔 엔진을 쓴다. 이 둘은 cuda/를 옆에 두므로 PATH에 cuda를 붙인다. */
    static Optional<Path> local(Optional<Path> bundled, String env, Supplier<Optional<Path>> downloaded) {
        if (bundled.isPresent() || (env != null && !env.isBlank())) {
            return bundled;
        }
        return downloaded.get();
    }

    /**
     * 윈도우는 CUDA 런타임 DLL이 whisper/ 옆 cuda/(engines/cuda)에 있어서 PATH 앞에 붙인다(D-060).
     * 윈도우 변수 이름은 보통 {@code Path}다. 자식 환경 맵은 대소문자를 가려서 같은 이름으로 덮어써야 두 개가 되지 않는다.
     */
    static Map<String, String> environment(Path exe, String osName, Map<String, String> env) {
        Path cuda = exe.toAbsolutePath().getParent().resolveSibling(CUDA_DIR);
        if (!AppPaths.isWindows(osName) || !Files.isDirectory(cuda)) {
            return Map.of();
        }
        String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String path = env.get(key);
        return Map.of(key, path == null || path.isBlank() ? cuda.toString() : cuda + File.pathSeparator + path);
    }
}

package koja.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import koja.runtime.ModelStore.ModelSpec;

/** 번들 whisper-server 위치·실행 인자·모델(D-059, D-060). */
public final class WhisperServer {
    /** large-v3-turbo. M0 PoC와 같은 파일(poc/win_setup.py). */
    public static final ModelSpec MODEL = ModelSpec.huggingFace("whisper/ggml-large-v3-turbo.bin",
            "ggerganov/whisper.cpp", "5359861c739e955e79d9a303bcbc70fb988958b1", "ggml-large-v3-turbo.bin",
            1_624_555_275L, "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69");

    static final String DIR = "whisper";
    static final String CUDA_DIR = "cuda";

    private WhisperServer() {}

    static String fallback(String env) {
        return env != null && !env.isBlank() ? env : "whisper-server";
    }

    /**
     * 서버를 띄우고 모델이 올라갈 때까지 기다린다. 다 쓰면 close()로 내려 GPU를 비운다(절대 규칙 5).
     * 실행 파일은 설치판에 들어 있는 것 → 환경 변수 {@code KOJA_WHISPER_SERVER} → PATH 순서로 찾는다.
     */
    public static LocalServer start(Path model) throws IOException {
        Optional<Path> bundled = AppPaths.bundledExecutable(DIR, "whisper-server");
        String exe = bundled.map(Path::toString).orElse(fallback(System.getenv("KOJA_WHISPER_SERVER")));
        String threads = Integer.toString(Math.min(8, Runtime.getRuntime().availableProcessors()));
        // -nlp: verbose_json마다 언어 확률을 따로 한 번 더 계산하지 않게 한다. 언어는 language 필드로 충분하다.
        return LocalServer.builder("whisper-server", port -> List.of(exe, "-m", model.toString(),
                        "--host", "127.0.0.1", "--port", Integer.toString(port), "-t", threads, "-nlp"))
                .environment(bundled.map(p -> environment(p, System.getProperty("os.name"), System.getenv("PATH")))
                        .orElse(Map.of()))
                .healthCheck(LocalServer.HealthCheck.httpOk("/health"))
                .startupTimeout(Duration.ofMinutes(3))
                .start();
    }

    /** 윈도우 설치판은 CUDA 런타임 DLL이 whisper/ 옆 cuda/에 있어서 PATH 앞에 붙인다(D-060). */
    static Map<String, String> environment(Path exe, String osName, String path) {
        Path cuda = exe.toAbsolutePath().getParent().resolveSibling(CUDA_DIR);
        if (!AppPaths.isWindows(osName) || !Files.isDirectory(cuda)) {
            return Map.of();
        }
        return Map.of("PATH", path == null || path.isBlank() ? cuda.toString() : cuda + File.pathSeparator + path);
    }
}

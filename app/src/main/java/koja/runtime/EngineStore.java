package koja.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import koja.runtime.ModelStore.CancelToken;
import koja.runtime.ModelStore.ModelSpec;
import koja.runtime.ModelStore.ProgressListener;

/**
 * 윈도우 로컬 AI 엔진(whisper-server·llama-server·CUDA 런타임)을 공식 릴리스 zip에서 받아
 * {@code <데이터 폴더>/engines/<이름>}에 설치한다(D-077). 맥은 .app에 번들돼 있어 받을 것이 없다.
 * zip 이어 받기·SHA-256은 {@link ModelStore}를 그대로 쓰고, 설치는 임시 폴더에 푼 뒤 폴더 이름만 바꿔 원자적으로 끝낸다.
 */
public final class EngineStore implements AutoCloseable {
    public static final String DIR_NAME = "engines";
    static final String NOTICE = "NOTICE.txt";
    private static final String DOWNLOADS = ".downloads";

    /**
     * zip 하나에서 푸는 엔진 폴더 하나. {@code entries}는 zip 안 경로이고 폴더에는 파일 이름만 둔다.
     * {@code notice}가 폴더의 NOTICE.txt와 같아야 설치된 것으로 본다(버전이 바뀌면 다시 받는다).
     */
    public record Engine(String dir, String executable, ModelSpec archive, List<String> entries,
            ModelSpec license, String licenseName, String notice) {
        public Engine {
            entries = List.copyOf(entries);
        }
    }

    private static final String WHISPER_TAG = "b5907";
    private static final String WHISPER_COMMIT = "5eaee0cc9024eb9e9a99469199920c8b7f5cb9ec";
    private static final String WHISPER_ZIP = "whisper-bin-win-cuda-12.4.0-x64.zip";
    private static final String WHISPER_ZIP_URL =
            "https://github.com/ggml-org/whisper.cpp/releases/download/" + WHISPER_TAG + "/" + WHISPER_ZIP;
    private static final String WHISPER_ZIP_SHA256 = "34f872ce724ca1ff0a4afca481853e5ee74250f8cb22153e499f953562846039";
    private static final ModelSpec WHISPER_ARCHIVE = archive(WHISPER_ZIP, WHISPER_ZIP_URL, 735_571_214L,
            WHISPER_ZIP_SHA256);

    private static final String LLAMA_TAG = "b12655";
    private static final String LLAMA_COMMIT = "969d7333172c522d6a60fffb8dd35288ce8df236";
    private static final String LLAMA_ZIP = "llama-" + LLAMA_TAG + "-bin-win-cuda-12.4-x64.zip";
    private static final String LLAMA_ZIP_SHA256 = "0dbd9120332552733dc94bd23582dc3bfb3a60879ae907ddedf3afdb8a628b06";
    private static final ModelSpec LLAMA_ARCHIVE = archive(LLAMA_ZIP,
            "https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_TAG + "/" + LLAMA_ZIP, 358_033_331L,
            LLAMA_ZIP_SHA256);

    // whisper.cpp·llama.cpp LICENSE는 같은 MIT 전문이다(해시가 같다).
    private static final String MIT_SHA256 = "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d";
    private static final String CUDA_LICENSE_URL =
            "https://developer.download.nvidia.com/compute/cuda/redist/cuda_cudart/LICENSE.txt";
    private static final String CUDA_LICENSE_SHA256 = "e2c71babfd18a8e69542dd7e9ca018f9caa438094001a58e6bc4d8c999bf0d07";

    /** whisper-server.exe가 바로 또는 백엔드 DLL을 거쳐 읽는 것만 넣는다(scripts/bundle_whisper.sh WIN_FILES와 같다). */
    public static final Engine WHISPER = new Engine("whisper", "whisper-server", WHISPER_ARCHIVE,
            Stream.of("whisper-server.exe", "whisper.dll", "ggml.dll", "ggml-base.dll", "ggml-cuda.dll",
                    "ggml-cpu-x64.dll", "ggml-cpu-sse42.dll", "ggml-cpu-sandybridge.dll", "ggml-cpu-haswell.dll",
                    "ggml-cpu-skylakex.dll", "ggml-cpu-cannonlake.dll", "ggml-cpu-cascadelake.dll",
                    "ggml-cpu-icelake.dll", "ggml-cpu-alderlake.dll").map(f -> "Release/" + f).toList(),
            license("whisper-LICENSE", "https://raw.githubusercontent.com/ggml-org/whisper.cpp/" + WHISPER_COMMIT
                    + "/LICENSE", MIT_SHA256), "LICENSE",
            "whisper.cpp " + WHISPER_TAG + " (MIT), 공식 윈도우 CUDA 12.4 빌드를 처음 실행할 때 받았다\n"
                    + "빌드: https://github.com/ggml-org/whisper.cpp/releases/tag/" + WHISPER_TAG + "\n"
                    + "소스: https://github.com/ggml-org/whisper.cpp/tree/" + WHISPER_COMMIT + "\n"
                    + "파일: " + WHISPER_ZIP + " (SHA-256: " + WHISPER_ZIP_SHA256 + ")\n"
                    + "CUDA 런타임 DLL은 ../cuda/ (NVIDIA CUDA 12.4, cuda/NOTICE.txt)\n"
                    + "라이선스 전문: LICENSE\n");

    /** whisper zip에 든 CUDA 런타임. llama zip을 받을 때도 이것을 함께 쓴다(D-060과 같은 공유 폴더). */
    public static final Engine CUDA = new Engine("cuda", null, WHISPER_ARCHIVE,
            Stream.of("cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll").map(f -> "Release/" + f).toList(),
            license("cuda-LICENSE.txt", CUDA_LICENSE_URL, CUDA_LICENSE_SHA256), "LICENSE.txt",
            "NVIDIA CUDA 12.4 런타임 DLL: cudart64_12.dll cublas64_12.dll cublasLt64_12.dll\n"
                    + "whisper.cpp " + WHISPER_TAG + " 윈도우 CUDA 12.4 빌드에 든 것을 처음 실행할 때 받았다: "
                    + WHISPER_ZIP_URL + "\n"
                    + "SHA-256: " + WHISPER_ZIP_SHA256 + "\n"
                    + "NVIDIA CUDA Toolkit EULA 부록 A(재배포 가능 파일)·부록 B(cuBLAS 제3자 고지): "
                    + "https://docs.nvidia.com/cuda/eula/\n"
                    + "라이선스 전문: LICENSE.txt (" + CUDA_LICENSE_URL + ", SHA-256 " + CUDA_LICENSE_SHA256 + ")\n"
                    + "whisper-server와 llama-server가 함께 쓴다(koja D-060, D-077).\n");

    /** llama-server.exe 실행과 동적 백엔드 로딩에 필요한 것만 푼다(scripts/bundle_llama.sh와 같다). */
    public static final Engine LLAMA = new Engine("llama", "llama-server", LLAMA_ARCHIVE,
            List.of("llama-server.exe", "llama-server-impl.dll", "llama.dll", "llama-common.dll", "mtmd.dll",
                    "ggml.dll", "ggml-base.dll", "ggml-cuda.dll", "ggml-rpc.dll", "ggml-cpu-alderlake.dll",
                    "ggml-cpu-cannonlake.dll", "ggml-cpu-cascadelake.dll", "ggml-cpu-cooperlake.dll",
                    "ggml-cpu-haswell.dll", "ggml-cpu-icelake.dll", "ggml-cpu-ivybridge.dll",
                    "ggml-cpu-piledriver.dll", "ggml-cpu-sandybridge.dll", "ggml-cpu-sapphirerapids.dll",
                    "ggml-cpu-skylakex.dll", "ggml-cpu-sse42.dll", "ggml-cpu-x64.dll", "ggml-cpu-zen4.dll",
                    "libomp.dll", "LICENSE-LLVM-OpenMP"),
            license("llama-LICENSE", "https://raw.githubusercontent.com/ggml-org/llama.cpp/" + LLAMA_COMMIT
                    + "/LICENSE", MIT_SHA256), "LICENSE",
            "llama.cpp " + LLAMA_TAG + " (MIT License), Windows x64 CUDA 12.4 공식 빌드를 처음 실행할 때 받았다\n"
                    + "릴리스: https://github.com/ggml-org/llama.cpp/releases/tag/" + LLAMA_TAG + "\n"
                    + "커밋: https://github.com/ggml-org/llama.cpp/tree/" + LLAMA_COMMIT + "\n"
                    + "파일: " + LLAMA_ZIP + " (SHA-256: " + LLAMA_ZIP_SHA256 + ")\n"
                    + "CUDA 런타임 DLL은 ../cuda/ (NVIDIA CUDA 12.4, cuda/NOTICE.txt)\n"
                    + "라이선스 전문: LICENSE, LICENSE-LLVM-OpenMP\n");

    private final Path enginesDir;
    private final ModelStore downloads;

    public EngineStore(Path enginesDir) {
        this(enginesDir, new ModelStore(enginesDir));
    }

    EngineStore(Path enginesDir, ModelStore downloads) {
        this.enginesDir = enginesDir.toAbsolutePath().normalize();
        this.downloads = downloads;
    }

    /**
     * STT·LLM 단계에 필요한 엔진. 윈도우만 받고, 맥·기타는 번들 또는 PATH를 쓴다(D-077).
     * 환경 변수로 실행 파일을 지정했으면 그것이 먼저 쓰이므로 받지 않는다(D-078).
     */
    public static List<Engine> required(boolean stt, boolean llm) {
        // System.getenv(name)은 윈도우에서 이름 대소문자를 가리지 않는다. 서버 실행 쪽과 같은 조회를 쓴다.
        return required(stt, llm, System.getProperty("os.name"), System::getenv);
    }

    static List<Engine> required(boolean stt, boolean llm, String osName, Function<String, String> env) {
        if (!AppPaths.isWindows(osName)) {
            return List.of();
        }
        Set<Engine> engines = new LinkedHashSet<>();
        if (stt && isBlank(env.apply(WhisperServer.ENV_EXECUTABLE))) {
            engines.add(WHISPER);
            engines.add(CUDA);
        }
        if (llm && isBlank(env.apply(LlamaServer.ENV_EXECUTABLE))) {
            engines.add(LLAMA);
            engines.add(CUDA);
        }
        return List.copyOf(engines);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 받아 둔 엔진의 실행 파일. 설치가 끝나지 않았거나 버전이 다르면 비어 있다. */
    public static Optional<Path> installedExecutable(Engine engine) {
        // 윈도우 데이터 폴더를 KOJA_DATA_DIR로 맥에서 함께 써도 윈도우 .exe를 고르지 않는다.
        if (!AppPaths.isWindows()) {
            return Optional.empty();
        }
        return new EngineStore(AppPaths.enginesDir(), null).executable(engine);
    }

    Optional<Path> executable(Engine engine) {
        if (engine.executable() == null || !isInstalled(engine)) {
            return Optional.empty();
        }
        return Optional.of(dirOf(engine).resolve(AppPaths.executableName(engine.executable(), "Windows")));
    }

    public Path enginesDir() {
        return enginesDir;
    }

    public Path dirOf(Engine engine) {
        return enginesDir.resolve(engine.dir());
    }

    /** NOTICE.txt(설치 마지막에 써서 폴더 이름을 바꾼다)가 같고 파일이 다 있으면 설치된 것으로 본다. 해시는 zip에서 이미 봤다. */
    public boolean isInstalled(Engine engine) {
        Path dir = dirOf(engine);
        try {
            if (!Files.isRegularFile(dir.resolve(NOTICE))
                    || !Files.readString(dir.resolve(NOTICE), StandardCharsets.UTF_8).equals(engine.notice())) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return Files.isRegularFile(dir.resolve(engine.licenseName()))
                && engine.entries().stream().allMatch(e -> Files.isRegularFile(dir.resolve(fileName(e))));
    }

    public List<Engine> missing(List<Engine> engines) {
        return engines.stream().filter(e -> !isInstalled(e)).toList();
    }

    /** 받을 바이트(zip은 같은 것을 한 번만 센다). 라이선스 전문은 크기를 몰라 빼지만 몇십 KB다. */
    public static long downloadSize(List<Engine> engines) {
        return downloadsOf(engines).stream().mapToLong(s -> Math.max(0, s.expectedSize())).sum();
    }

    /**
     * 사용자가 확인한 엔진 중 빠진 것만 받아 설치한다. 취소되면 받던 zip은 {@code .part}로 남아 다음에 이어 받는다.
     * 끝나면 {@link #cleanup}으로 설치된 엔진의 zip을 지운다.
     */
    public void install(List<Engine> engines, ProgressListener listener, CancelToken token) throws IOException {
        List<Engine> todo = missing(engines);
        if (!todo.isEmpty()) {
            fetchAndUnpack(todo, listener, token);
        }
        cleanup(engines);
    }

    /**
     * 설치된 엔진의 zip·라이선스 파일을 지운다. 아직 설치 안 된 엔진이 쓰는 파일과 다른 엔진이 받다 만 {@code .part}는 남긴다.
     * 처리할 때마다 불러 지난번에 못 지운 zip(앱 종료·파일 잠금)을 다시 지운다.
     */
    public void cleanup(List<Engine> engines) {
        Set<ModelSpec> keep = new HashSet<>(downloadsOf(missing(engines)));
        for (ModelSpec spec : downloadsOf(engines)) {
            if (keep.contains(spec)) {
                continue;
            }
            try {
                Files.deleteIfExists(downloads.pathOf(spec));
            } catch (IOException e) {
                // 백신 등이 잠가 못 지워도 엔진은 설치돼 있으니 처리는 계속하고 다음 처리 때 다시 지운다.
            }
        }
    }

    private void fetchAndUnpack(List<Engine> todo, ProgressListener listener, CancelToken token) throws IOException {
        long total = downloadSize(todo);
        long before = 0;
        for (ModelSpec spec : downloadsOf(todo)) {
            long offset = before;
            downloads.ensureDownloaded(spec, (done, unused) -> {
                if (listener != null) {
                    listener.onProgress(offset + done, total);
                }
            }, token);
            before += Math.max(0, spec.expectedSize());
        }
        for (Engine engine : todo) {
            unpack(engine, token);
        }
    }

    private static List<ModelSpec> downloadsOf(List<Engine> engines) {
        Set<ModelSpec> specs = new LinkedHashSet<>();
        for (Engine e : engines) {
            specs.add(e.archive());
            specs.add(e.license());
        }
        return new ArrayList<>(specs);
    }

    /** 임시 폴더에 풀고 고지 파일을 쓴 뒤 이름을 바꾼다. 도중에 끊기면 다음 설치가 임시 폴더를 지우고 처음부터 푼다. */
    void unpack(Engine engine, CancelToken token) throws IOException {
        Path target = dirOf(engine);
        Path stage = enginesDir.resolve(".staging-" + engine.dir());
        Path old = enginesDir.resolve(".old-" + engine.dir());
        deleteTree(stage);
        Files.createDirectories(stage);
        try (ZipFile zip = new ZipFile(downloads.pathOf(engine.archive()).toFile(), StandardCharsets.UTF_8)) {
            for (String name : engine.entries()) {
                if (token != null && token.isCancelled()) {
                    throw new CancellationException("엔진 설치를 취소했습니다");
                }
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException(engine.archive().relativePath() + " 안에 " + name + "이(가) 없습니다");
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, stage.resolve(fileName(name)));
                }
            }
        }
        Files.copy(downloads.pathOf(engine.license()), stage.resolve(engine.licenseName()));
        Files.writeString(stage.resolve(NOTICE), engine.notice(), StandardCharsets.UTF_8);
        deleteTree(old);
        if (Files.exists(target)) {
            Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(old);
    }

    private static String fileName(String entry) {
        return entry.substring(entry.lastIndexOf('/') + 1);
    }

    private static ModelSpec archive(String fileName, String url, long size, String sha256) {
        return new ModelSpec(DOWNLOADS + "/" + fileName, URI.create(url), size, sha256);
    }

    private static ModelSpec license(String fileName, String url, String sha256) {
        return new ModelSpec(DOWNLOADS + "/" + fileName, URI.create(url), -1, sha256);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Override
    public void close() {
        if (downloads != null) {
            downloads.close();
        }
    }
}

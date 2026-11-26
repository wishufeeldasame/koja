package koja.engine;

import com.k2fsa.sherpa.onnx.VersionInfo;
import java.nio.file.Path;
import java.util.Optional;
import koja.runtime.AppPaths;

/**
 * sherpa-onnx JNI 네이티브 라이브러리 경로 초기화와 로딩 확인(D-062).
 * jpackage 설치판에서는 {@code Contents/sherpa-native}(맥) 또는 {@code <설치 폴더>/sherpa-native}(윈도우)를
 * {@code sherpa_onnx.native.path}로 지정해 임시 폴더 추출 없이 바로 읽는다.
 */
public final class SherpaNative {
    public static final String BUNDLE_DIR_NAME = "sherpa-native";
    public static final String NATIVE_PATH_PROPERTY = "sherpa_onnx.native.path";

    private SherpaNative() {}

    /** {@code sherpa_onnx.native.path}가 비어 있으면 번들 폴더를 찾아 지정한다. */
    public static synchronized void configurePath() {
        String existing = System.getProperty(NATIVE_PATH_PROPERTY);
        if (existing != null && !existing.isBlank()) {
            return;
        }
        Optional<Path> bundled = AppPaths.bundledDirectory(BUNDLE_DIR_NAME);
        if (bundled.isPresent()) {
            System.setProperty(NATIVE_PATH_PROPERTY, bundled.get().toAbsolutePath().toString());
        }
    }

    /** 네이티브 라이브러리를 적재하고 sherpa-onnx 버전 문자열을 돌려준다. */
    public static String load() {
        configurePath();
        String version = VersionInfo.getVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("sherpa-onnx 네이티브 버전을 읽지 못했습니다");
        }
        return version;
    }
}

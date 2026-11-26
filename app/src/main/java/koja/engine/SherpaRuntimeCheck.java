package koja.engine;

/**
 * JPMS 모듈 경로({@code --module koja/...})에서 sherpa-onnx JNI 네이티브 라이브러리가 실제로 적재되는지 검증한다.
 * {@code ./gradlew build}의 {@code verifyModuleNativeLoad} 태스크가 맥·윈도우에서 실행한다.
 */
public final class SherpaRuntimeCheck {
    private SherpaRuntimeCheck() {}

    public static void main(String[] args) {
        String version = SherpaNative.load();
        System.out.println("sherpa-onnx native loaded: " + version);
    }
}

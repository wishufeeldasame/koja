package koja.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.CancelToken;
import koja.runtime.ModelStore.ModelSpec;
import koja.runtime.ModelStore.ProgressListener;

/**
 * sherpa-onnx VAD·화자 구분 모델 명세와 다운로드 도우미(D-039, D-062).
 * 모든 모델은 {@link ModelStore}(D-050)로 받아 SHA-256을 검증한다.
 */
public final class SherpaModels {
    public static final ModelSpec SILERO_VAD = new ModelSpec(
            "sherpa/silero_vad.onnx",
            URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
            643_854L,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6");

    public static final ModelSpec PYANNOTE_SEGMENTATION = ModelSpec.huggingFace(
            "sherpa/pyannote-segmentation-3-0/model.onnx",
            "csukuangfj/sherpa-onnx-pyannote-segmentation-3-0",
            "9403a6902bb58e3d5ae8c7e77c3422de279db2e0",
            "model.onnx",
            5_992_913L,
            "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079");

    public static final ModelSpec CAMPPLUS_EMBEDDING = new ModelSpec(
            "sherpa/campplus_zh_en.onnx",
            URI.create("https://github.com/k2-fsa/sherpa-onnx/releases/download/"
                    + "speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"),
            28_281_165L,
            "53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b");

    public record DiarModels(Path segmentation, Path embedding) {
        public DiarModels {
            Objects.requireNonNull(segmentation, "segmentation");
            Objects.requireNonNull(embedding, "embedding");
        }
    }

    private SherpaModels() {}

    public static Path ensureVad(ModelStore store, ProgressListener listener, CancelToken cancelToken)
            throws IOException {
        Objects.requireNonNull(store, "store");
        return store.ensureDownloaded(SILERO_VAD, listener, cancelToken);
    }

    public static DiarModels ensureDiar(ModelStore store, ProgressListener listener, CancelToken cancelToken)
            throws IOException {
        Objects.requireNonNull(store, "store");
        long segSize = PYANNOTE_SEGMENTATION.expectedSize();
        long embSize = CAMPPLUS_EMBEDDING.expectedSize();
        long total = segSize + embSize;

        ProgressListener segProgress = listener == null ? null
                : (done, unused) -> listener.onProgress(Math.min(done, segSize), total);
        Path seg = store.ensureDownloaded(PYANNOTE_SEGMENTATION, segProgress, cancelToken);

        ProgressListener embProgress = listener == null ? null
                : (done, unused) -> listener.onProgress(segSize + Math.min(done, embSize), total);
        Path emb = store.ensureDownloaded(CAMPPLUS_EMBEDDING, embProgress, cancelToken);
        return new DiarModels(seg, emb);
    }

    /**
     * JNI 생성자에 넘기기 전 모델 파일 존재·크기·SHA-256을 검증한다.
     * sherpa-onnx C++는 손상됐거나 없는 ONNX 파일에서 exit(-1) 또는 미처리 예외로 JVM을 종료할 수 있다.
     */
    static void verifyModelFile(Path file, ModelSpec spec, String label) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(label + " 모델 파일이 없습니다: " + file);
        }
        long size = Files.size(file);
        if (size == 0L || (spec.expectedSize() >= 0L && size != spec.expectedSize())) {
            throw new IOException(label + " 모델 파일 크기가 예상(" + spec.expectedSize() + ")과 다릅니다(" + size + "): " + file);
        }
        String actualSha = ModelStore.sha256(file);
        if (!actualSha.equals(spec.sha256())) {
            throw new IOException(label + " 모델 체크섬이 일치하지 않습니다: " + file);
        }
    }
}

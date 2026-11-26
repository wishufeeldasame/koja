package koja.engine;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import koja.core.StageStore;
import koja.core.StageStore.DiarResult;
import koja.core.StageStore.Turn;
import koja.engine.SherpaModels.DiarModels;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.CancelToken;
import koja.runtime.ModelStore.ProgressListener;

/**
 * sherpa-onnx 기반 로컬 화자 구분기(pyannote segmentation-3.0 + 3D-Speaker CAM++, CPU, D-039·D-064).
 * STT 결과를 보지 않고 16kHz 모노 오디오만으로 {@link DiarResult}(diar.json, D-048)를 만든다.
 */
public final class SherpaDiarizer implements Diarizer {
    public static final int SAMPLE_RATE = WavAudio.SAMPLE_RATE;
    public static final float DEFAULT_THRESHOLD = 0.9f;
    // pyannote segmentation 최소 창보다 지나치게 짧은 조각(약 0.05초 미만)은 빈 턴으로 처리해 네이티브 예외를 피한다.
    private static final int MIN_SAMPLES = SAMPLE_RATE / 20;

    /**
     * 화자 구분 설정.
     * {@code numClusters}가 -1이면 {@code threshold}로 화자 수를 자동 결정하고, 양수이면 지정된 화자 수로 군집한다.
     */
    public record Config(
            int numClusters,
            float threshold,
            float minDurationOn,
            float minDurationOff,
            int numThreads) {
        public static final Config DEFAULT = new Config(-1, DEFAULT_THRESHOLD, 0.3f, 0.5f, defaultThreads());

        public Config {
            if (numClusters != -1 && numClusters < 1) {
                throw new IllegalArgumentException("numClusters는 -1(자동) 또는 1 이상이어야 합니다: " + numClusters);
            }
            if (threshold <= 0.0f) {
                throw new IllegalArgumentException("threshold는 양수여야 합니다: " + threshold);
            }
            if (minDurationOn < 0.0f || minDurationOff < 0.0f) {
                throw new IllegalArgumentException("최소 지속 시간은 0 이상이어야 합니다");
            }
            if (numThreads < 1) {
                throw new IllegalArgumentException("numThreads는 1 이상이어야 합니다: " + numThreads);
            }
        }

        public static Config withThreshold(float threshold) {
            return new Config(-1, threshold, 0.3f, 0.5f, defaultThreads());
        }

        public static Config withNumClusters(int numClusters) {
            return new Config(numClusters, DEFAULT_THRESHOLD, 0.3f, 0.5f, defaultThreads());
        }

        static int defaultThreads() {
            return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        }
    }

    private final OfflineSpeakerDiarization nativeDiarizer;
    private final Config config;
    private boolean closed;

    private SherpaDiarizer(OfflineSpeakerDiarization nativeDiarizer, Config config) {
        this.nativeDiarizer = nativeDiarizer;
        this.config = config;
    }

    public static SherpaDiarizer open(Path segmentationModel, Path embeddingModel) throws IOException {
        return open(segmentationModel, embeddingModel, Config.DEFAULT);
    }

    public static SherpaDiarizer open(Path segmentationModel, Path embeddingModel, Config config) throws IOException {
        Objects.requireNonNull(segmentationModel, "segmentationModel");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
        Objects.requireNonNull(config, "config");
        // sherpa-onnx C++는 모델 파일이 없으면 exit(-1)로 JVM을 종료하므로 사전에 검사한다.
        requireModelFile(segmentationModel, "화자 분할(pyannote)");
        requireModelFile(embeddingModel, "화자 임베딩(CAM++)");

        SherpaNative.configurePath();
        OfflineSpeakerSegmentationModelConfig segCfg = OfflineSpeakerSegmentationModelConfig.builder()
                .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                        .setModel(segmentationModel.toAbsolutePath().toString())
                        .build())
                .setNumThreads(config.numThreads())
                .setProvider("cpu")
                .setDebug(false)
                .build();
        SpeakerEmbeddingExtractorConfig embCfg = SpeakerEmbeddingExtractorConfig.builder()
                .setModel(embeddingModel.toAbsolutePath().toString())
                .setNumThreads(config.numThreads())
                .setProvider("cpu")
                .setDebug(false)
                .build();
        FastClusteringConfig clusterCfg = FastClusteringConfig.builder()
                .setNumClusters(config.numClusters())
                .setThreshold(config.threshold())
                .build();
        OfflineSpeakerDiarizationConfig diarCfg = OfflineSpeakerDiarizationConfig.builder()
                .setSegmentation(segCfg)
                .setEmbedding(embCfg)
                .setClustering(clusterCfg)
                .setMinDurationOn(config.minDurationOn())
                .setMinDurationOff(config.minDurationOff())
                .build();
        try {
            return new SherpaDiarizer(new OfflineSpeakerDiarization(diarCfg), config);
        } catch (RuntimeException e) {
            throw new IOException("sherpa-onnx 화자 구분기 초기화에 실패했습니다", e);
        }
    }

    public static SherpaDiarizer open(ModelStore store, ProgressListener listener, CancelToken cancelToken)
            throws IOException {
        return open(store, Config.DEFAULT, listener, cancelToken);
    }

    public static SherpaDiarizer open(
            ModelStore store, Config config, ProgressListener listener, CancelToken cancelToken) throws IOException {
        DiarModels models = SherpaModels.ensureDiar(store, listener, cancelToken);
        return open(models.segmentation(), models.embedding(), config);
    }

    @Override
    public String name() {
        return engineName(config);
    }

    static String engineName(Config config) {
        if (config.numClusters() > 0) {
            return "sherpa-campplus#k" + config.numClusters();
        }
        return String.format(Locale.ROOT, "sherpa-campplus@%.2f", config.threshold())
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    public Config config() {
        return config;
    }

    @Override
    public DiarResult diarize(Path audio, DoubleConsumer progress) throws IOException, InterruptedException {
        ensureOpen();
        float[] samples = WavAudio.readMono16k(audio);
        return diarize(samples, progress);
    }

    /** 16kHz 모노 float 샘플([-1.0, 1.0])에서 화자 턴을 추출한다. */
    public DiarResult diarize(float[] samples, DoubleConsumer progress) throws InterruptedException {
        Objects.requireNonNull(samples, "samples");
        ensureOpen();
        if (Thread.interrupted()) {
            throw new InterruptedException("화자 구분 취소");
        }
        if (samples.length < MIN_SAMPLES) {
            notifyProgress(progress, 1.0);
            return new DiarResult(StageStore.CURRENT_VERSION, name(), List.of());
        }

        notifyProgress(progress, 0.0);
        OfflineSpeakerDiarizationSegment[] raw = nativeDiarizer.processWithCallback(
                samples,
                (processed, total, unused) -> {
                    if (total > 0) {
                        try {
                            notifyProgress(progress, (double) processed / total);
                        } catch (RuntimeException ignored) {
                            // JNI 콜백 안에서 예외가 전파되지 않게 막는다.
                        }
                    }
                    return 0;
                });
        if (Thread.interrupted()) {
            throw new InterruptedException("화자 구분 취소");
        }

        List<Turn> turns = toTurns(raw);
        notifyProgress(progress, 1.0);
        return new DiarResult(StageStore.CURRENT_VERSION, name(), turns);
    }

    static List<Turn> toTurns(OfflineSpeakerDiarizationSegment[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<Turn> turns = new ArrayList<>(raw.length);
        for (OfflineSpeakerDiarizationSegment seg : raw) {
            if (seg == null) {
                continue;
            }
            long startMs = Math.max(0L, Math.round(seg.getStart() * 1000.0));
            long endMs = Math.max(startMs, Math.round(seg.getEnd() * 1000.0));
            turns.add(new Turn(startMs, endMs, "S" + seg.getSpeaker()));
        }
        turns.sort(Comparator.comparingLong(Turn::startMs).thenComparingLong(Turn::endMs));
        return List.copyOf(turns);
    }

    private static void requireModelFile(Path file, String label) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            throw new IOException(label + " 모델 파일이 없거나 비어 있습니다: " + file);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("이미 닫힌 화자 구분기입니다");
        }
    }

    private static void notifyProgress(DoubleConsumer progress, double fraction) {
        if (progress != null) {
            progress.accept(Math.max(0.0, Math.min(1.0, fraction)));
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            nativeDiarizer.release();
        }
    }
}

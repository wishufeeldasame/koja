package koja.engine;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.CancelToken;
import koja.runtime.ModelStore.ProgressListener;

/**
 * Silero VAD 로컬 실행기(sherpa-onnx Java, CPU, D-039·D-063).
 * 16kHz 모노 오디오에서 무음·BGM을 제외한 발화 구간 목록을 뽑는다.
 * 기본값은 강제 분할 없는 기본 VAD(D-054·D-063)이며, 필요 시 {@code maxSegmentSeconds > 0}으로 저에너지 지점 분할을 켤 수 있다.
 */
public final class Vad implements Engine {
    public static final int SAMPLE_RATE = WavAudio.SAMPLE_RATE;
    public static final int SILERO_WINDOW_SIZE = 512;
    private static final int INTERRUPT_CHECK_WINDOWS = 32;
    private static final int SPLIT_HOP_SAMPLES = SAMPLE_RATE * 30 / 1000;

    /** 검출된 발화 구간(영상 시작 기준 ms, 0 ≤ startMs ≤ endMs). */
    public record Segment(long startMs, long endMs) {
        public Segment {
            if (startMs < 0 || endMs < startMs) {
                throw new IllegalArgumentException("VAD 구간 시각이 잘못됐습니다: " + startMs + ".." + endMs);
            }
        }

        public long durationMs() {
            return endMs - startMs;
        }
    }

    /**
     * Silero VAD 설정.
     * {@code maxSegmentSeconds}가 0이면 강제 분할을 하지 않는다(기본 VAD, D-063).
     */
    public record Config(
            float threshold,
            float minSilenceDuration,
            float minSpeechDuration,
            float maxSpeechDuration,
            int windowSize,
            int maxSegmentSeconds,
            int numThreads) {
        public static final Config DEFAULT = new Config(0.5f, 0.3f, 0.25f, 20.0f, 512, 0, 1);

        public Config {
            if (threshold <= 0.0f || threshold >= 1.0f) {
                throw new IllegalArgumentException("threshold는 (0, 1) 범위여야 합니다: " + threshold);
            }
            if (minSilenceDuration < 0.0f || minSpeechDuration < 0.0f || maxSpeechDuration <= 0.0f) {
                throw new IllegalArgumentException("VAD 길이 설정이 잘못됐습니다");
            }
            if (windowSize != SILERO_WINDOW_SIZE) {
                throw new IllegalArgumentException(
                        "고정 Silero VAD 16kHz 모델은 windowSize=" + SILERO_WINDOW_SIZE + "만 지원합니다: " + windowSize);
            }
            if (maxSegmentSeconds < 0) {
                throw new IllegalArgumentException("maxSegmentSeconds는 0 이상이어야 합니다: " + maxSegmentSeconds);
            }
            if (numThreads <= 0) {
                throw new IllegalArgumentException("numThreads는 양수여야 합니다: " + numThreads);
            }
        }
    }

    private final com.k2fsa.sherpa.onnx.Vad nativeVad;
    private final Config config;
    private final Object stateLock = new Object();
    private boolean inUse;
    private boolean closeRequested;
    private boolean released;

    private Vad(com.k2fsa.sherpa.onnx.Vad nativeVad, Config config) {
        this.nativeVad = nativeVad;
        this.config = config;
    }

    public static Vad open(Path modelPath) throws IOException {
        return open(modelPath, Config.DEFAULT);
    }

    public static Vad open(Path modelPath, Config config) throws IOException {
        Objects.requireNonNull(modelPath, "modelPath");
        Objects.requireNonNull(config, "config");
        SherpaModels.verifyModelFile(modelPath, SherpaModels.SILERO_VAD, "Silero VAD");
        SherpaNative.configurePath();
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(modelPath.toAbsolutePath().toString())
                .setThreshold(config.threshold())
                .setMinSilenceDuration(config.minSilenceDuration())
                .setMinSpeechDuration(config.minSpeechDuration())
                .setMaxSpeechDuration(config.maxSpeechDuration())
                .setWindowSize(config.windowSize())
                .build();
        VadModelConfig vadConfig = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(config.numThreads())
                .setProvider("cpu")
                .setDebug(false)
                .build();
        try {
            return new Vad(new com.k2fsa.sherpa.onnx.Vad(vadConfig), config);
        } catch (RuntimeException e) {
            throw new IOException("Silero VAD 초기화에 실패했습니다: " + modelPath, e);
        }
    }

    public static Vad open(ModelStore store, ProgressListener listener, CancelToken cancelToken) throws IOException {
        return open(store, Config.DEFAULT, listener, cancelToken);
    }

    public static Vad open(ModelStore store, Config config, ProgressListener listener, CancelToken cancelToken)
            throws IOException {
        Path modelPath = SherpaModels.ensureVad(store, listener, cancelToken);
        return open(modelPath, config);
    }

    @Override
    public String name() {
        return config.maxSegmentSeconds() > 0
                ? "sherpa-silero-vad@" + config.maxSegmentSeconds() + "s"
                : "sherpa-silero-vad";
    }

    public Config config() {
        return config;
    }

    /** 16kHz 모노 16비트 PCM WAV 파일에서 발화 구간을 검출한다. */
    public List<Segment> detect(Path audio, DoubleConsumer progress) throws IOException, InterruptedException {
        ensureNotClosed();
        float[] samples = WavAudio.readMono16k(audio);
        return detect(samples, progress);
    }

    /** 16kHz 모노 float 샘플([-1.0, 1.0])에서 발화 구간을 검출한다. */
    public List<Segment> detect(float[] samples, DoubleConsumer progress) throws InterruptedException {
        Objects.requireNonNull(samples, "samples");
        beginUse();
        try {
            if (Thread.interrupted()) {
                throw new InterruptedException("VAD 취소");
            }
            if (samples.length == 0) {
                notifyProgress(progress, 1.0);
                if (Thread.interrupted()) {
                    throw new InterruptedException("VAD 취소");
                }
                return List.of();
            }

            nativeVad.reset();
            int win = config.windowSize();
            float[] frame = new float[win];
            List<int[]> rawRanges = new ArrayList<>();
            int windows = 0;

            for (int pos = 0; pos < samples.length; pos += win) {
                if ((windows++ % INTERRUPT_CHECK_WINDOWS) == 0) {
                    if (Thread.interrupted()) {
                        nativeVad.reset();
                        throw new InterruptedException("VAD 취소");
                    }
                    notifyProgress(progress, (double) pos / samples.length);
                    if (Thread.interrupted()) {
                        nativeVad.reset();
                        throw new InterruptedException("VAD 취소");
                    }
                }
                int len = Math.min(win, samples.length - pos);
                System.arraycopy(samples, pos, frame, 0, len);
                if (len < win) {
                    Arrays.fill(frame, len, win, 0.0f);
                }
                nativeVad.acceptWaveform(frame);
                drain(rawRanges, samples.length);
            }
            if (Thread.interrupted()) {
                nativeVad.reset();
                throw new InterruptedException("VAD 취소");
            }
            nativeVad.flush();
            drain(rawRanges, samples.length);

            List<int[]> ranges = rawRanges;
            if (config.maxSegmentSeconds() > 0) {
                int maxSamples = Math.multiplyExact(config.maxSegmentSeconds(), SAMPLE_RATE);
                ranges = new ArrayList<>();
                for (int[] r : rawRanges) {
                    splitAtLowEnergy(samples, r[0], r[1], maxSamples, ranges);
                }
            }

            List<Segment> segments = new ArrayList<>(ranges.size());
            for (int[] r : ranges) {
                long startMs = r[0] * 1000L / SAMPLE_RATE;
                long endMs = Math.max(startMs, r[1] * 1000L / SAMPLE_RATE);
                segments.add(new Segment(startMs, endMs));
            }
            notifyProgress(progress, 1.0);
            if (Thread.interrupted()) {
                throw new InterruptedException("VAD 취소");
            }
            return List.copyOf(segments);
        } finally {
            endUse();
        }
    }

    private void drain(List<int[]> out, int totalSamples) {
        while (!nativeVad.empty()) {
            SpeechSegment seg = nativeVad.front();
            nativeVad.pop();
            if (seg == null) {
                continue;
            }
            int start = (int) Math.max(0L, Math.min(totalSamples, seg.getStart()));
            int length = seg.getSamples() == null ? 0 : seg.getSamples().length;
            int end = (int) Math.max(start, Math.min(totalSamples, seg.getStart() + length));
            if (end > start) {
                out.add(new int[] { start, end });
            }
        }
    }

    /**
     * {@code [start, end)}가 {@code maxSamples}보다 길면 가운데 영역에서 30ms 평균 에너지가 가장 낮은 지점을 잘라 재귀 분할한다.
     * {@code poc/vad.py}의 {@code split}과 동일한 규칙이다.
     */
    static void splitAtLowEnergy(float[] samples, int start, int end, int maxSamples, List<int[]> out) {
        int len = end - start;
        if (len <= maxSamples || len < SPLIT_HOP_SAMPLES * 3) {
            out.add(new int[] { start, end });
            return;
        }
        int margin = Math.min(SAMPLE_RATE, len / 4);
        int from = start + margin;
        int to = end - margin - SPLIT_HOP_SAMPLES;
        if (from > to) {
            int mid = start + len / 2;
            splitAtLowEnergy(samples, start, mid, maxSamples, out);
            splitAtLowEnergy(samples, mid, end, maxSamples, out);
            return;
        }
        int bestCut = from;
        double bestEnergy = Double.POSITIVE_INFINITY;
        for (int f = from; f <= to; f += SPLIT_HOP_SAMPLES) {
            double sumSq = 0.0;
            for (int i = 0; i < SPLIT_HOP_SAMPLES; i++) {
                float v = samples[f + i];
                sumSq += (double) v * v;
            }
            if (sumSq < bestEnergy) {
                bestEnergy = sumSq;
                bestCut = f;
            }
        }
        splitAtLowEnergy(samples, start, bestCut, maxSamples, out);
        splitAtLowEnergy(samples, bestCut, end, maxSamples, out);
    }

    private void ensureNotClosed() {
        synchronized (stateLock) {
            if (closeRequested || released) {
                throw new IllegalStateException("이미 닫힌 VAD 엔진입니다");
            }
        }
    }

    private void beginUse() {
        synchronized (stateLock) {
            if (closeRequested || released) {
                throw new IllegalStateException("이미 닫힌 VAD 엔진입니다");
            }
            if (inUse) {
                throw new IllegalStateException("VAD 엔진은 동시에 한 호출만 실행할 수 있습니다");
            }
            inUse = true;
        }
    }

    private void endUse() {
        boolean shouldRelease = false;
        synchronized (stateLock) {
            inUse = false;
            if (closeRequested && !released) {
                released = true;
                shouldRelease = true;
            }
        }
        if (shouldRelease) {
            nativeVad.release();
        }
    }

    private static void notifyProgress(DoubleConsumer progress, double fraction) {
        if (progress != null) {
            progress.accept(Math.max(0.0, Math.min(1.0, fraction)));
        }
    }

    @Override
    public void close() {
        boolean shouldRelease = false;
        synchronized (stateLock) {
            closeRequested = true;
            if (!inUse && !released) {
                released = true;
                shouldRelease = true;
            }
        }
        if (shouldRelease) {
            nativeVad.release();
        }
    }
}

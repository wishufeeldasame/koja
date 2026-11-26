package koja.engine.whisper;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import koja.core.Project.KoCandidate;
import koja.core.Project.SttSegment;
import koja.core.StageStore;
import koja.core.StageStore.SttResult;
import koja.engine.Transcriber;
import koja.runtime.LocalServer;
import koja.runtime.WhisperServer;

/**
 * 로컬 whisper-server 2회 방식 STT(D-039, D-040). VAD 구간마다 자동 언어로 한 번 전사하고,
 * 한국어가 아닌 구간은 한국어로 고정해 한 번 더 전사해 {@code ko} 후보로 남긴다.
 */
public final class WhisperTranscriber implements Transcriber {
    /** VAD 구간. 시간은 audio.wav 기준 ms. */
    public record Span(long startMs, long endMs) {}

    /** 발화 구간을 찾는다(sherpa-onnx Silero VAD, W2-3). 구간은 시작 시각 오름차순. */
    @FunctionalInterface
    public interface Vad {
        List<Span> segments(Path audio) throws IOException, InterruptedException;
    }

    /** WAV 바이트를 language("auto" 또는 "ko")로 전사해 verbose_json 본문을 돌려준다. */
    @FunctionalInterface
    interface Inference {
        String transcribe(byte[] wav, String language) throws IOException, InterruptedException;
    }

    private static final String KOREAN = "ko";

    private final Inference inference;
    private final Vad vad;
    private final Closeable server;

    WhisperTranscriber(Inference inference, Vad vad, Closeable server) {
        this.inference = inference;
        this.vad = vad;
        this.server = server;
    }

    /** whisper-server를 띄운다. 모델은 {@link WhisperServer#MODEL}을 ModelStore로 미리 받아 둔다. */
    public static WhisperTranscriber start(Path model, Vad vad) throws IOException {
        LocalServer server = WhisperServer.start(model);
        return new WhisperTranscriber(new WhisperClient(server.httpClient(), server.baseUri()), vad, server);
    }

    @Override
    public String name() {
        return "whisper.cpp large-v3-turbo 2pass";
    }

    @Override
    public SttResult transcribe(Path audio, DoubleConsumer progress) throws IOException, InterruptedException {
        progress.accept(-1);
        List<Span> spans = vad.segments(audio);
        List<SttSegment> out = new ArrayList<>();
        try (Wav wav = Wav.open(audio)) {
            long end = wav.durationMs();
            for (int i = 0; i < spans.size(); i++) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                Span span = spans.get(i);
                long s = Math.min(span.startMs(), end);
                long e = Math.min(span.endMs(), end);
                if (e > s) {
                    SttSegment seg = segment(out.size() + 1, s, e, wav.clip(s, e));
                    if (seg != null) {
                        out.add(seg);
                    }
                }
                progress.accept((i + 1) / (double) spans.size());
            }
        }
        return new SttResult(StageStore.CURRENT_VERSION, name(), out);
    }

    /** 1회차가 비면(무음·BGM) 구간을 만들지 않는다. */
    private SttSegment segment(int id, long s, long e, byte[] clip) throws IOException, InterruptedException {
        WhisperJson.Result first = WhisperJson.parse(inference.transcribe(clip, "auto"), s, e);
        if (first.text().isEmpty()) {
            return null;
        }
        KoCandidate ko = null;
        if (!KOREAN.equals(first.lang())) {
            WhisperJson.Result second = WhisperJson.parse(inference.transcribe(clip, KOREAN), s, e);
            if (!second.text().isEmpty()) {
                ko = new KoCandidate(second.text(), second.words());
            }
        }
        return new SttSegment(id, s, e, first.text(), first.lang(), null, first.words(), ko);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}

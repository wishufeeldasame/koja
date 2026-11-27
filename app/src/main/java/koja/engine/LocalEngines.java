package koja.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import koja.engine.whisper.WhisperTranscriber;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.ModelSpec;
import koja.runtime.WhisperServer;

/** M2 로컬 엔진 조립: Silero VAD + whisper-server 2회 방식 STT, CAM++ 화자 구분. 모델은 {@link #missing}을 미리 받아 둔다. */
public final class LocalEngines {
    public static final List<ModelSpec> STT_MODELS = List.of(WhisperServer.MODEL, SherpaModels.SILERO_VAD);
    public static final List<ModelSpec> DIAR_MODELS = List.of(SherpaModels.PYANNOTE_SEGMENTATION,
            SherpaModels.CAMPPLUS_EMBEDDING);

    private LocalEngines() {}

    /** 돌릴 단계에 필요한데 아직 받지 않은 모델(크기만 본다. 해시는 받을 때·sherpa 엔진을 열 때 검사한다). */
    public static List<ModelSpec> missing(ModelStore store, boolean stt, boolean diar) {
        return Stream.concat(stt ? STT_MODELS.stream() : Stream.empty(), diar ? DIAR_MODELS.stream() : Stream.empty())
                .filter(m -> !store.isInstalled(m)).toList();
    }

    /**
     * 처리 전에 ModelStore로 확인할 모델: 빠진 것 + 돌릴 단계의 sherpa 모델(작아서 매번 해시를 보고 틀리면 다시 받는다).
     * whisper 모델(1.6GB)은 실행마다 해시를 보면 수 초 걸려 받을 때만 검사한다.
     */
    public static List<ModelSpec> toEnsure(ModelStore store, boolean stt, boolean diar) {
        return Stream.concat(stt ? STT_MODELS.stream() : Stream.empty(), diar ? DIAR_MODELS.stream() : Stream.empty())
                .filter(m -> m != WhisperServer.MODEL || !store.isInstalled(m)).toList();
    }

    public static Pipeline.Opener<Transcriber> transcriber(ModelStore store) {
        return () -> startWhisper(store, audio -> {
            // VAD는 CPU라 whisper-server(GPU)와 함께 열려 있어도 된다(절대 규칙 5는 GPU 단계 기준).
            try (Vad vad = Vad.open(store.pathOf(SherpaModels.SILERO_VAD))) {
                return vad.detect(audio, f -> {}).stream()
                        .map(s -> new WhisperTranscriber.Span(s.startMs(), s.endMs())).toList();
            }
        });
    }

    /** 해시는 시작에 실패했을 때만 본다. 손상된 모델이면 지워 다음 실행이 다시 받게 한다(같은 실패 반복 방지). */
    private static WhisperTranscriber startWhisper(ModelStore store, WhisperTranscriber.Vad vad) throws IOException {
        Path model = store.pathOf(WhisperServer.MODEL);
        try {
            return WhisperTranscriber.start(model, vad);
        } catch (IOException e) {
            if (Files.isRegularFile(model) && !store.verify(WhisperServer.MODEL)) {
                Files.delete(model);
                IOException corrupt = new IOException("whisper 모델 파일이 손상돼 지웠습니다. 다시 실행하면 새로 받습니다: " + model);
                corrupt.addSuppressed(e);
                throw corrupt;
            }
            throw e;
        }
    }

    public static Pipeline.Opener<Diarizer> diarizer(ModelStore store) {
        return () -> SherpaDiarizer.open(store.pathOf(SherpaModels.PYANNOTE_SEGMENTATION),
                store.pathOf(SherpaModels.CAMPPLUS_EMBEDDING));
    }

    /** 5단계 구조화용 llama-server(Gemma 4 E4B, 8K, D-055·D-066). 모델이 없으면 받는다. */
    public static Pipeline.Opener<LlmClient> llm(ModelStore store) {
        return () -> LlamaServerClient.open(store);
    }
}

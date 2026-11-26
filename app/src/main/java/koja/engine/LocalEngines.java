package koja.engine;

import java.util.List;
import koja.engine.whisper.WhisperTranscriber;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.ModelSpec;
import koja.runtime.WhisperServer;

/** M2 로컬 엔진 조립: Silero VAD + whisper-server 2회 방식 STT, CAM++ 화자 구분. 모델은 {@link #MODELS}를 미리 받아 둔다. */
public final class LocalEngines {
    /** STT·화자 구분에 필요한 모델 전부. 첫 실행 때 받는다. */
    public static final List<ModelSpec> MODELS = List.of(WhisperServer.MODEL, SherpaModels.SILERO_VAD,
            SherpaModels.PYANNOTE_SEGMENTATION, SherpaModels.CAMPPLUS_EMBEDDING);

    private LocalEngines() {}

    /** 아직 받지 않은 모델(크기만 본다. 해시는 엔진을 열 때 검사한다). */
    public static List<ModelSpec> missing(ModelStore store) {
        return MODELS.stream().filter(m -> !store.isInstalled(m)).toList();
    }

    public static Pipeline.Opener<Transcriber> transcriber(ModelStore store) {
        return () -> WhisperTranscriber.start(store.pathOf(WhisperServer.MODEL), audio -> {
            // VAD는 CPU라 whisper-server(GPU)와 함께 열려 있어도 된다(절대 규칙 5는 GPU 단계 기준).
            try (Vad vad = Vad.open(store.pathOf(SherpaModels.SILERO_VAD))) {
                return vad.detect(audio, f -> {}).stream()
                        .map(s -> new WhisperTranscriber.Span(s.startMs(), s.endMs())).toList();
            }
        });
    }

    public static Pipeline.Opener<Diarizer> diarizer(ModelStore store) {
        return () -> SherpaDiarizer.open(store.pathOf(SherpaModels.PYANNOTE_SEGMENTATION),
                store.pathOf(SherpaModels.CAMPPLUS_EMBEDDING));
    }
}

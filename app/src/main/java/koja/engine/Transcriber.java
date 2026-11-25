package koja.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;
import koja.core.StageStore.SttResult;

/** 2단계 STT(+4단계 한국어 재전사, D-040). VAD도 구현 안에서 한다. */
public interface Transcriber extends Engine {
    /** audio는 16kHz 모노 audio.wav. 결과 구간의 speakerId는 비운다. */
    SttResult transcribe(Path audio, DoubleConsumer progress) throws IOException, InterruptedException;
}

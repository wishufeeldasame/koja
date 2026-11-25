package koja.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;
import koja.core.StageStore.DiarResult;

/** 3단계 화자 구분. STT 결과를 보지 않고 오디오만으로 화자 턴을 낸다. */
public interface Diarizer extends Engine {
    DiarResult diarize(Path audio, DoubleConsumer progress) throws IOException, InterruptedException;
}

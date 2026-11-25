package koja.engine;

import java.io.IOException;
import java.nio.file.Path;

/** 6단계 비전. 프레임 크롭 이미지에서 글자(화자 이름 후보)를 읽는다. 못 읽으면 빈 문자열(추측 금지, 절대 규칙 2). */
public interface FrameReader extends Engine {
    String read(Path image, String prompt) throws IOException, InterruptedException;
}

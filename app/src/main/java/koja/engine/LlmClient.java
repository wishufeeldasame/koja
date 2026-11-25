package koja.engine;

import java.io.IOException;

/** 5단계 구조화·7단계 번역. 응답 형식(JSON 스키마 등)은 M3에서 구조화 방식을 정할 때 넣는다. */
public interface LlmClient extends Engine {
    String complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException;
}

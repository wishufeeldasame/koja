package koja.engine.whisper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import koja.core.Project.Word;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** whisper-server {@code /inference} verbose_json 응답 파싱. 시간은 구간 시작을 더해 영상 기준 ms로 바꾼다. */
final class WhisperJson {
    /** lang은 ko, ja, en, zh 코드. 그 밖의 언어는 whisper 이름 그대로(예: french). */
    record Result(String text, String lang, List<Word> words) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // whisper-server는 language에 전체 이름(korean)을 준다(b5907 실측). stt.json은 코드(ko)로 둔다(설계 5장).
    private static final Map<String, String> CODES = Map.of("korean", "ko", "japanese", "ja", "english", "en", "chinese", "zh");

    private WhisperJson() {}

    static Result parse(String body, long startMs, long endMs) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new IOException("whisper-server 응답이 JSON이 아닙니다", e);
        }
        if (root.has("error")) {
            throw new IOException("whisper-server 오류: " + root.path("error").asString(""));
        }
        List<Word> words = new ArrayList<>();
        for (JsonNode seg : root.path("segments")) {
            for (JsonNode w : seg.path("words")) {
                String text = w.path("word").asString("");
                if (text.isBlank()) {
                    continue;
                }
                // 토큰 시각은 10ms 단위 추정값이라 구간 밖으로 조금 나갈 수 있다. stt.json 검사(D-048)를 통과하게 구간 안으로 묶는다.
                long s = clamp(startMs + Math.round(w.path("start").asDouble(0) * 1000), startMs, endMs);
                long e = clamp(startMs + Math.round(w.path("end").asDouble(0) * 1000), s, endMs);
                words.add(new Word(s, e, text));
            }
        }
        String lang = root.path("language").asString("").toLowerCase(Locale.ROOT);
        return new Result(root.path("text").asString("").strip(), CODES.getOrDefault(lang, lang), words);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}

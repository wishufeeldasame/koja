package koja.core;

import java.util.List;
import java.util.Map;

/** project.json 한 개의 내용. 필드 구조는 docs/설계.md 5장. */
public record Project(
        int version,
        Source source,
        Meta meta,
        VideoType videoType,
        List<Speaker> speakers,
        List<SttSegment> sttSegments,
        List<Row> rows,
        List<Integer> ignoredSegIds,
        String glossary,
        Map<String, Stage> stages) {

    /** 선택 항목을 추가할 때는 올리지 않는다. 옛 파일을 못 읽게 되는 변경에서만 올린다(D-048). */
    public static final int CURRENT_VERSION = 1;

    /** ignoredSegIds는 나중에 생긴 선택 항목이라 옛 파일에는 없다(D-048). */
    public Project {
        ignoredSegIds = ignoredSegIds == null ? List.of() : ignoredSegIds;
    }

    public record Source(String path, long durationMs) {}

    public record Meta(String title, String topic, List<String> participants) {}

    public enum VideoType { INTERVIEW_JA, INTERVIEW_KO, FREE, MIXED }

    public record Speaker(String id, String name, boolean confirmed) {}

    /**
     * STT 구간. text·lang·words는 자동 언어 1차 결과, ko는 한국어가 아닌 구간을 한국어로 고정해 다시 전사한 후보다(D-040).
     * speakerId는 stt.json에서는 null이고, 화자 구분 뒤 project.json에서 채운다. words·ko는 옛 파일에 없을 수 있다(D-048).
     */
    public record SttSegment(
            int id, long startMs, long endMs, String text, String lang, String speakerId, List<Word> words, KoCandidate ko) {
        public SttSegment {
            words = words == null ? List.of() : words;
        }

        public SttSegment(int id, long startMs, long endMs, String text, String lang, String speakerId) {
            this(id, startMs, endMs, text, lang, speakerId, List.of(), null);
        }

        public SttSegment withSpeaker(String speakerId) {
            return new SttSegment(id, startMs, endMs, text, lang, speakerId, words, ko);
        }
    }

    /** 단어 시간은 영상 시작 기준 ms. 행 startMs는 행 첫 단어의 startMs다(D-040). */
    public record Word(long startMs, long endMs, String text) {}

    public record KoCandidate(String text, List<Word> words) {
        public KoCandidate {
            words = words == null ? List.of() : words;
        }
    }

    /** startMs·speakerId는 QUESTION_JA 행에서 null이다. */
    public record Row(
            String id,
            RowKind kind,
            Long startMs,
            String speakerId,
            String ko,
            String ja,
            List<Integer> segIds,
            List<Flag> flags) {}

    public enum RowKind { LINE, QUESTION_KO, QUESTION_JA }

    public enum Flag { SPEAKER_UNSURE, TEXT_UNSURE }

    public record Stage(String engine, boolean done) {}
}

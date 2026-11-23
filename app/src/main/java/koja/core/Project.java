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
        String glossary,
        Map<String, Stage> stages) {

    public static final int CURRENT_VERSION = 1;

    public record Source(String path, long durationMs) {}

    public record Meta(String title, String topic, List<String> participants) {}

    public enum VideoType { INTERVIEW_JA, INTERVIEW_KO, FREE, MIXED }

    public record Speaker(String id, String name, boolean confirmed) {}

    public record SttSegment(int id, long startMs, long endMs, String text, String lang, String speakerId) {}

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

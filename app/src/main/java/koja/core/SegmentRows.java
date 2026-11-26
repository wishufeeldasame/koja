package koja.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import koja.core.Project.Flag;
import koja.core.Project.Row;
import koja.core.Project.RowKind;
import koja.core.Project.Speaker;
import koja.core.Project.SttSegment;
import koja.core.Project.Word;

/**
 * M2 행 만들기: STT 구간 1개 = 행 1개(D-071). 시간·원문은 구간에서만 가져온다(절대 규칙 1).
 * 한국어가 아닌 구간은 한국어 후보를 쓰고 TEXT_UNSURE, 화자가 없거나 미확인이면 SPEAKER_UNSURE(절대 규칙 2).
 */
public final class SegmentRows {
    private static final String KOREAN = "ko";
    private static final String JAPANESE = "ja";

    private SegmentRows() {}

    public static List<Row> build(List<SttSegment> segments, List<Speaker> speakers) {
        Set<String> confirmed = speakers.stream().filter(Speaker::confirmed).map(Speaker::id)
                .collect(Collectors.toSet());
        List<Row> rows = new ArrayList<>(segments.size());
        for (SttSegment s : segments) {
            boolean korean = KOREAN.equals(s.lang());
            String ko = korean ? s.text() : s.ko() == null ? "" : s.ko().text();
            List<Word> words = korean || s.ko() == null ? s.words() : s.ko().words();
            List<Flag> flags = new ArrayList<>(2);
            if (s.speakerId() == null || !confirmed.contains(s.speakerId())) {
                flags.add(Flag.SPEAKER_UNSURE);
            }
            if (!korean || ko.isBlank()) {
                flags.add(Flag.TEXT_UNSURE);
            }
            // 일본어 구간의 원문은 번역 전까지 검수자가 들은 말과 맞춰 보도록 일본어 칸에 둔다.
            String ja = JAPANESE.equals(s.lang()) ? s.text() : null;
            long start = words.isEmpty() ? s.startMs() : words.getFirst().startMs();
            rows.add(new Row("r" + s.id(), RowKind.LINE, start, s.speakerId(), ko, ja, List.of(s.id()),
                    List.copyOf(flags)));
        }
        return List.copyOf(rows);
    }
}

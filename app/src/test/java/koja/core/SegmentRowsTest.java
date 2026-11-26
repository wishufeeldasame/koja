package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import koja.core.Project.Flag;
import koja.core.Project.KoCandidate;
import koja.core.Project.Row;
import koja.core.Project.RowKind;
import koja.core.Project.Speaker;
import koja.core.Project.SttSegment;
import koja.core.Project.Word;
import org.junit.jupiter.api.Test;

class SegmentRowsTest {
    static final List<Speaker> SPEAKERS = List.of(new Speaker("S1", "선수A", true),
            new Speaker("S2", "화자 미확인 1", false));

    @Test
    void oneRowPerSegmentWithTimeAndTextFromStt() {
        List<SttSegment> segs = List.of(
                new SttSegment(3, 1_000, 2_000, "안녕하세요", "ko", "S1",
                        List.of(new Word(1_200, 1_600, "안녕하세요")), null),
                new SttSegment(5, 4_000, 6_000, "こんにちは", "ja", "S2",
                        List.of(new Word(4_300, 4_900, "こんにちは")),
                        new KoCandidate("안녕하세요", List.of(new Word(4_100, 4_900, "안녕하세요")))),
                new SttSegment(8, 7_000, 8_000, "hello", "en", null));

        List<Row> rows = SegmentRows.build(segs, SPEAKERS);

        assertEquals(List.of(
                new Row("r3", RowKind.LINE, 1_200L, "S1", "안녕하세요", null, List.of(3), List.of()),
                // 한국어 후보를 쓰면 시간도 그 후보의 첫 단어다(D-040).
                new Row("r5", RowKind.LINE, 4_100L, "S2", "안녕하세요", "こんにちは", List.of(5),
                        List.of(Flag.SPEAKER_UNSURE, Flag.TEXT_UNSURE)),
                // 한국어 후보도 화자도 없으면 비우고 표시한다(절대 규칙 2). 단어가 없으면 구간 시작.
                new Row("r8", RowKind.LINE, 7_000L, null, "", null, List.of(8),
                        List.of(Flag.SPEAKER_UNSURE, Flag.TEXT_UNSURE))), rows);
    }

    @Test
    void noSegmentsNoRows() {
        assertEquals(List.of(), SegmentRows.build(List.of(), SPEAKERS));
    }
}

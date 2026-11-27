package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import koja.core.Project.KoCandidate;
import koja.core.Project.SttSegment;
import koja.core.Project.Word;
import koja.core.StageStore.Turn;
import koja.core.StageStore.Unit;
import org.junit.jupiter.api.Test;

class ClauseSplitterTest {
    @Test
    void splitsKoreanAtSentenceEndAndKeepsSttTimes() {
        SttSegment s = new SttSegment(7, 0, 5_000, "첫 문장입니다. 둘째", "ko", "S1",
                List.of(new Word(100, 500, " 첫"), new Word(500, 1_200, " 문장입니다."), new Word(1_300, 2_000, " 둘째")), null);
        List<Unit> units = ClauseSplitter.split(List.of(s), List.of(new Turn(0, 5_000, "S1")));
        assertEquals(List.of(
                new Unit(1, 7, 100, 1_200, "ko", "S1", "첫 문장입니다.", s.words().subList(0, 2), null),
                new Unit(2, 7, 1_300, 2_000, "ko", "S1", "둘째", s.words().subList(2, 3), null)), units);
    }

    @Test
    void splitsAtSpeakerTurnAndInheritsParentSpeakerWhenNoTurn() {
        SttSegment s = new SttSegment(1, 0, 4_000, "가나다 라마바", "ko", "S2",
                List.of(new Word(0, 1_500, " 가나다"), new Word(2_000, 3_000, " 라마바")), null);
        List<Unit> units = ClauseSplitter.split(List.of(s), List.of(new Turn(0, 1_600, "S1"), new Turn(1_900, 3_100, "S3")));
        assertEquals(List.of("S1", "S3"), units.stream().map(Unit::speakerId).toList());

        List<Unit> none = ClauseSplitter.split(List.of(s), List.of());
        assertEquals(List.of("S2"), none.stream().map(Unit::speakerId).toList());
    }

    @Test
    void doesNotCutJapaneseByKoreanCandidatePunctuationButSplitsLongSentences() {
        KoCandidate ko = new KoCandidate("가. 나. 다.", List.of(new Word(0, 100, "가."), new Word(200, 300, " 나."),
                new Word(9_000, 9_100, " 다.")));
        SttSegment s = new SttSegment(2, 0, 10_000, "あ。い", "ja", null,
                List.of(new Word(0, 8_500, "あ。"), new Word(8_900, 9_500, "い")), ko);
        List<Unit> units = ClauseSplitter.split(List.of(s), List.of());
        assertEquals(2, units.size());
        assertEquals("あ。", units.get(0).text());
        assertEquals(new KoCandidate("가. 나.", ko.words().subList(0, 2)), units.get(0).ko());
        assertEquals(new KoCandidate("다.", ko.words().subList(2, 3)), units.get(1).ko());

        SttSegment shortJa = new SttSegment(3, 0, 3_000, "あ。い", "ja", null,
                List.of(new Word(0, 1_000, "あ。"), new Word(1_100, 2_000, "い")), ko);
        assertEquals(1, ClauseSplitter.split(List.of(shortJa), List.of()).size());
    }

    @Test
    void keepsSegmentWholeWhenOtherSideHasTextButNoWordTimes() {
        SttSegment s = new SttSegment(4, 0, 12_000, "あ。い", "ja", null,
                List.of(new Word(0, 8_500, "あ。"), new Word(8_900, 9_500, "い")), new KoCandidate("후보", List.of()));
        List<Unit> units = ClauseSplitter.split(List.of(s), List.of());
        assertEquals(1, units.size());
        assertEquals(s.ko(), units.getFirst().ko());
    }

    @Test
    void countsHangulOnly() {
        assertEquals(3, ClauseSplitter.hangul("T1 가나 다!"));
    }

    @Test
    void unitEndCoversOutOfOrderWords() {
        SttSegment s = new SttSegment(1, 0, 5_000, "가 나. 다", "ko", null,
                List.of(new Word(1_000, 2_000, " 가"), new Word(500, 900, " 나."), new Word(3_000, 3_500, " 다")), null);
        Unit first = ClauseSplitter.split(List.of(s), List.of()).getFirst();
        assertEquals(1_000, first.startMs());
        assertEquals(2_000, first.endMs());
    }
}

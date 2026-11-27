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

    @Test
    void unsplitSegmentKeepsParentSpeakerFromWordSpans() {
        // 단어는 S2 턴에만 겹치지만 VAD 구간 전체로는 S1 턴이 더 길다. 파이프라인이 붙인 S2를 유지한다.
        SttSegment s = new SttSegment(1, 0, 10_000, "가나", "ko", "S2", List.of(new Word(8_000, 9_000, " 가나")), null);
        List<Unit> units = ClauseSplitter.split(List.of(s), List.of(new Turn(0, 7_000, "S1"), new Turn(7_500, 9_500, "S2")));
        assertEquals(List.of("S2"), units.stream().map(Unit::speakerId).toList());
    }

    @Test
    void splitUnitSpeakerComesFromWordOverlapNotGaps() {
        // 첫 절의 두 단어는 S1 턴과만 겹치고, 단어 사이 공백에는 S2 턴이 더 길게 있다.
        SttSegment s = new SttSegment(1, 0, 9_000, "가 나. 다", "ko", "S3",
                List.of(new Word(0, 300, " 가"), new Word(3_700, 4_000, " 나."), new Word(6_000, 6_500, " 다")), null);
        List<Turn> turns = List.of(new Turn(0, 400, "S1"), new Turn(400, 3_600, "S2"), new Turn(3_600, 4_100, "S1"));
        List<Unit> units = ClauseSplitter.split(List.of(s), turns);
        assertEquals(java.util.Arrays.asList("S1", "S3"), units.stream().map(Unit::speakerId).toList());
    }

    @Test
    void wordSpeakerCountsOverlapOnce() {
        // S1 턴 둘이 같은 200ms를 덮어도 한 번만 센다. 합집합 300ms < S2 450ms.
        List<Word> words = List.of(new Word(0, 300, "가"), new Word(300, 750, "나"));
        List<Turn> turns = List.of(new Turn(0, 300, "S1"), new Turn(100, 300, "S1"), new Turn(300, 750, "S2"));
        assertEquals("S2", ClauseSplitter.wordSpeaker(words, turns));
    }

    @Test
    void koCandidateWordsSplitAtMiddleOfGapBetweenPieces() {
        // 질문(S1) 뒤 답변(S2). 답변의 첫 한국어 후보 단어가 답변 1차 첫 단어보다 먼저 시작해도 답변 쪽에 들어간다.
        KoCandidate ko = new KoCandidate("질문 답변 이어서", List.of(new Word(0, 1_000, "질문"), new Word(2_800, 3_200, " 답변"),
                new Word(3_300, 4_000, " 이어서")));
        SttSegment s = new SttSegment(1, 0, 5_000, "質問 答え", "ja", "S1",
                List.of(new Word(0, 1_800, "質問"), new Word(3_000, 4_000, "答え")), ko);
        List<Unit> units = ClauseSplitter.split(List.of(s), List.of(new Turn(0, 2_000, "S1"), new Turn(2_500, 4_500, "S2")));
        assertEquals(2, units.size());
        assertEquals("질문", units.get(0).ko().text());
        assertEquals("답변 이어서", units.get(1).ko().text());
    }
}

package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import koja.core.Project.Flag;
import koja.core.Project.KoCandidate;
import koja.core.Project.Row;
import koja.core.Project.RowKind;
import koja.core.Project.Speaker;
import koja.core.Project.Word;
import koja.core.StageStore.Unit;
import koja.core.StageStore.UnitRow;
import koja.core.StructuredRows.Chunk;
import koja.core.StructuredRows.Settled;
import koja.core.StructuringValidator.Action;
import koja.core.StructuringValidator.Item;
import org.junit.jupiter.api.Test;

class StructuredRowsTest {
    static Unit unit(int id, int seg, long start, String speaker, String lang, String text, String ko) {
        return new Unit(id, seg, start, start + 1_000, lang, speaker, text, List.of(new Word(start + 100, start + 900, text)),
                ko == null ? null : new KoCandidate(ko, List.of(new Word(start + 200, start + 900, ko))));
    }

    static Map<Integer, Unit> map(Unit... units) {
        return List.of(units).stream().collect(Collectors.toMap(Unit::id, Function.identity()));
    }

    static Item item(int id, Action action, boolean newRow, Flag... flags) {
        return new Item(id, action, newRow, List.of(flags));
    }

    static UnitRow row(RowKind kind, List<Flag> flags, Integer... ids) {
        return new UnitRow(null, kind, List.of(ids), flags);
    }

    @Test
    void bothJaLineMakesQuestionAndKoreanRowFromOneUnit() {
        Map<Integer, Unit> units = map(unit(3, 1, 0, "S1", "ja", "質問", "짧은 조각"),
                unit(4, 1, 1_000, "S1", "ko", "이어지는 말.", null),
                unit(5, 2, 2_000, "S2", "ja", "通訳", "통역"));
        Chunk out = StructuredRows.toRows(List.of(item(3, Action.BOTH_JA_LINE, true),
                item(4, Action.LINE, false, Flag.TEXT_UNSURE), item(5, Action.IGNORE, true)), units);
        assertEquals(List.of(row(RowKind.QUESTION_JA, List.of(), 3), row(RowKind.LINE, List.of(Flag.TEXT_UNSURE), 3, 4)),
                out.rows());
        assertEquals(List.of(5), out.ignored());
    }

    @Test
    void koreanRowsSplitOnSpeakerChangeSentenceEndAndLength() {
        Map<Integer, Unit> diff = map(unit(3, 1, 0, "S1", "ko", "앞 조각", null),
                unit(4, 1, 1_000, "S2", "ko", "다른 화자", null), unit(5, 1, 2_000, "S2", "ko", "이어짐", null));
        assertEquals(List.of(row(RowKind.LINE, List.of(), 3), row(RowKind.LINE, List.of(), 4, 5)),
                StructuredRows.toRows(List.of(item(3, Action.LINE, true), item(4, Action.LINE, false),
                        item(5, Action.LINE, false)), diff).rows());

        Map<Integer, Unit> longer = map(unit(3, 1, 0, "S1", "ko", "이미 충분히 긴 한국어 자막 문장입니다.", null),
                unit(4, 2, 3_000, "S1", "ko", "다음 문장이 바로 이어집니다", null),
                unit(5, 3, 4_000, "S1", "ko", "그리고 또 길게 이어지는 한국어 발언이 계속 나옵니다", null),
                unit(6, 4, 6_000, null, "ja", "abc", "음"));
        assertEquals(List.of(row(RowKind.LINE, List.of(), 3), row(RowKind.LINE, List.of(), 4), row(RowKind.LINE, List.of(), 5),
                row(RowKind.LINE, List.of(Flag.SPEAKER_UNSURE, Flag.TEXT_UNSURE), 6)),
                StructuredRows.toRows(List.of(item(3, Action.LINE, true), item(4, Action.LINE, false),
                        item(5, Action.LINE, false), item(6, Action.LINE, true)), longer).rows());
    }

    @Test
    void japaneseQuestionJoinsUntilNewRowAndIgnoreBreaksRows() {
        Map<Integer, Unit> units = map(unit(1, 1, 0, "S1", "ja", "あの", null), unit(2, 1, 1_000, "S1", "ja", "質問", null),
                unit(3, 2, 2_000, "S1", "ja", "はい", null), unit(4, 3, 3_000, "S1", "ja", "次", null));
        Chunk out = StructuredRows.toRows(List.of(item(1, Action.QUESTION_JA, true), item(2, Action.QUESTION_JA, false),
                item(3, Action.IGNORE, false), item(4, Action.QUESTION_JA, false)), units);
        assertEquals(List.of(row(RowKind.QUESTION_JA, List.of(), 1, 2), row(RowKind.QUESTION_JA, List.of(), 4)), out.rows());
    }

    @Test
    void settleReasksRowsTouchingTheLastRow() {
        Chunk chunk = new Chunk(List.of(row(RowKind.LINE, List.of(), 1, 2), row(RowKind.QUESTION_JA, List.of(), 4, 7),
                row(RowKind.LINE, List.of(), 5, 6)), List.of(3, 8));
        assertEquals(new Settled(List.of(row(RowKind.LINE, List.of(), 1, 2)), List.of(3), 4),
                StructuredRows.settle(chunk, 1, false));
        assertNull(StructuredRows.settle(new Chunk(List.of(row(RowKind.LINE, List.of(), 1, 2, 3)), List.of()), 1, false).cut());
        assertEquals(new Settled(List.of(), List.of(1, 2), null),
                StructuredRows.settle(new Chunk(List.of(), List.of(1, 2)), 1, false));
        assertNull(StructuredRows.settle(chunk, 1, true).cut());
    }

    @Test
    void fallbackKeepsEveryUnitAsLine() {
        Chunk out = StructuredRows.fallback(List.of(unit(1, 1, 0, "S1", "ko", "가", null), unit(2, 1, 1_000, null, "ja", "x", "")));
        assertEquals(List.of(row(RowKind.LINE, List.of(), 1), row(RowKind.LINE, List.of(Flag.SPEAKER_UNSURE, Flag.TEXT_UNSURE), 2)),
                out.rows());
        assertEquals(List.of(), out.ignored());
    }

    @Test
    void projectsUnitRowsToSttSegmentRows() {
        List<Unit> units = List.of(unit(1, 10, 0, "S1", "ja", "質問です", "질문"),
                unit(2, 10, 1_000, "S2", "ja", "答え", "답변입니다"),
                unit(3, 11, 2_000, "S2", "ko", "이어서 말합니다", null),
                unit(4, 12, 3_000, "S3", "ja", "通訳", "통역"),
                unit(5, 13, 4_000, "S3", "ja", "雑談", null), unit(6, 13, 5_000, "S2", "ko", "네", null));
        List<UnitRow> rows = List.of(new UnitRow("r1", RowKind.QUESTION_JA, List.of(1), List.of()),
                new UnitRow("r2", RowKind.LINE, List.of(2, 3), List.of(Flag.TEXT_UNSURE)),
                new UnitRow("r3", RowKind.LINE, List.of(6), List.of()));
        StructuredRows.Projection p = StructuredRows.project(rows, List.of(4, 5), units,
                List.of(new Speaker("S2", "선수A", true)));
        assertEquals(List.of(new Row("r1", RowKind.QUESTION_JA, null, null, null, "質問です", List.of(10), List.of()),
                new Row("r2", RowKind.LINE, 1_200L, "S2", "답변입니다 이어서 말합니다", null, List.of(10, 11),
                        List.of(Flag.TEXT_UNSURE)),
                new Row("r3", RowKind.LINE, 5_100L, "S2", "네", null, List.of(13), List.of())), p.rows());
        // 구간 13은 일부만 무시돼 무시 목록에 넣지 않는다.
        assertEquals(List.of(12), p.ignoredSegIds());

        StructuredRows.Projection unconfirmed = StructuredRows.project(rows, List.of(4, 5), units, List.of());
        assertEquals(List.of(Flag.SPEAKER_UNSURE, Flag.TEXT_UNSURE), unconfirmed.rows().get(1).flags());
        assertEquals(List.of(), unconfirmed.rows().get(0).flags());
    }

    @Test
    void nonKoreanUnitWithoutCandidateLeavesKoreanEmpty() {
        List<Unit> units = List.of(unit(1, 1, 0, "S1", "en", "hello", null));
        StructuredRows.Projection p = StructuredRows.project(
                List.of(new UnitRow("r1", RowKind.LINE, List.of(1), List.of(Flag.TEXT_UNSURE))), List.of(), units, List.of());
        assertEquals("", p.rows().getFirst().ko());
    }

    @Test
    void koreanRowStartsAtFirstShownKoreanWord() {
        List<Unit> units = List.of(unit(1, 1, 0, "S1", "en", "um", null), unit(2, 1, 1_000, "S1", "ko", "답변", null));
        StructuredRows.Projection p = StructuredRows.project(
                List.of(new UnitRow("r1", RowKind.LINE, List.of(1, 2), List.of())), List.of(), units, List.of());
        assertEquals(1_100L, p.rows().getFirst().startMs());
        assertEquals("답변", p.rows().getFirst().ko());
    }

    @Test
    void japaneseQuestionOnKoreanUnitStaysAsLine() {
        Map<Integer, Unit> units = map(unit(1, 1, 0, "S1", "ko", "한국어 질문", null), unit(2, 2, 1_000, "S1", "en", "answer", "답변"));
        Chunk out = StructuredRows.toRows(List.of(item(1, Action.QUESTION_JA, true), item(2, Action.BOTH_JA_LINE, true)), units);
        assertEquals(List.of(row(RowKind.LINE, List.of(), 1), row(RowKind.LINE, List.of(), 2)), out.rows());
    }

    @Test
    void unassignedSpeakersAreNotMerged() {
        Map<Integer, Unit> units = map(unit(1, 1, 0, null, "ko", "앞", null), unit(2, 1, 1_000, null, "ko", "뒤", null));
        assertEquals(2, StructuredRows.toRows(List.of(item(1, Action.LINE, true), item(2, Action.LINE, false)), units)
                .rows().size());
    }

    @Test
    void longPauseStartsNewRow() {
        Map<Integer, Unit> units = map(unit(1, 1, 0, "S1", "ko", "앞", null), unit(2, 2, 10_000, "S1", "ko", "뒤", null));
        assertEquals(2, StructuredRows.toRows(List.of(item(1, Action.LINE, true), item(2, Action.LINE, false)), units)
                .rows().size());
    }
}

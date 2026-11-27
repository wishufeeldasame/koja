package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import koja.core.Project.Flag;
import koja.core.Project.RowKind;
import koja.core.Project.VideoType;
import koja.core.StageStore.UnitRow;
import koja.core.StructuringValidator.Action;
import koja.core.StructuringValidator.Item;
import koja.core.StructuringValidator.Items;
import org.junit.jupiter.api.Test;

class StructuringValidatorTest {
    static final List<Integer> IDS = List.of(3, 4, 5);

    static UnitRow row(RowKind kind, Integer... ids) {
        return new UnitRow(null, kind, List.of(ids), List.of());
    }

    static String check(List<UnitRow> rows, List<Integer> ignored, Set<Integer> dual) {
        return StructuringValidator.checkRows(rows, ignored, IDS, dual, VideoType.INTERVIEW_JA);
    }

    @Test
    void acceptsEveryIdOnceInOrder() {
        assertNull(check(List.of(row(RowKind.LINE, 3, 5)), List.of(4), Set.of()));
    }

    @Test
    void allowsJapaneseQuestionAndKoreanRowOnlyForKoCandidate() {
        List<UnitRow> rows = List.of(row(RowKind.QUESTION_JA, 3), row(RowKind.LINE, 3), row(RowKind.LINE, 4, 5));
        assertNull(check(rows, List.of(), Set.of(3)));
        assertTrue(check(rows, List.of(), Set.of()).contains("함께"));
    }

    @Test
    void rejectsSameIdTwiceInKoreanRowsEvenWithKoCandidate() {
        assertTrue(check(List.of(row(RowKind.LINE, 3), row(RowKind.LINE, 3, 4, 5)), List.of(), Set.of(3))
                .contains("오름차순"));
        assertTrue(check(List.of(row(RowKind.QUESTION_JA, 3), row(RowKind.QUESTION_JA, 3), row(RowKind.LINE, 3, 4, 5)),
                List.of(), Set.of(3)).contains("오름차순"));
    }

    @Test
    void rejectsMissingReversedIgnoredAndExtraIds() {
        assertTrue(check(List.of(row(RowKind.LINE, 3)), List.of(4), Set.of()).contains("빠진 구간ID [5]"));
        assertTrue(check(List.of(row(RowKind.LINE, 4), row(RowKind.LINE, 3)), List.of(5), Set.of()).contains("순서가 아닙니다"));
        assertTrue(check(List.of(row(RowKind.LINE, 3, 4, 5)), List.of(4), Set.of()).contains("무시하면서 행에도 쓴 ID [4]"));
        assertTrue(check(List.of(row(RowKind.LINE, 3, 4, 5, 6)), List.of(), Set.of()).contains("아닌 ID [6]"));
        assertTrue(check(List.of(row(RowKind.LINE, 3, 4, 5)), List.of(2, 1), Set.of()).contains("ignoredUnitIds"));
        assertNotNull(check(List.of(new UnitRow(null, RowKind.LINE, List.of(), List.of())), List.of(3, 4, 5), Set.of()));
    }

    @Test
    void rejectsRowKindsTheVideoTypeCannotHave() {
        List<UnitRow> question = List.of(row(RowKind.QUESTION_JA, 3), row(RowKind.LINE, 4, 5));
        assertTrue(StructuringValidator.checkRows(question, List.of(), IDS, Set.of(), VideoType.FREE).contains("FREE"));
        assertNotNull(StructuringValidator.checkRows(List.of(row(RowKind.QUESTION_KO, 3, 4, 5)), List.of(), IDS, Set.of(),
                VideoType.INTERVIEW_JA));
        assertNull(StructuringValidator.checkRows(List.of(row(RowKind.QUESTION_KO, 3, 4, 5)), List.of(), IDS, Set.of(),
                VideoType.MIXED));
    }

    @Test
    void actionsDependOnVideoType() {
        assertEquals(List.of(Action.LINE, Action.IGNORE), StructuringValidator.actionsFor(VideoType.FREE, true));
        assertFalse(StructuringValidator.actionsFor(VideoType.INTERVIEW_JA, false).contains(Action.BOTH_JA_LINE));
        assertTrue(StructuringValidator.actionsFor(VideoType.INTERVIEW_JA, true).contains(Action.BOTH_JA_LINE));
        assertTrue(StructuringValidator.actionsFor(VideoType.MIXED, false).contains(Action.QUESTION_KO));
    }

    static final String OK = """
            {"items": [
              {"id": 3, "action": "BOTH_JA_LINE", "newRow": true, "flags": []},
              {"id": 4, "action": "LINE", "newRow": false, "flags": ["TEXT_UNSURE", "TEXT_UNSURE"]},
              {"id": 5, "action": "IGNORE", "newRow": true, "flags": []}]}""";

    static Items parse(String json, Set<Integer> dual) {
        return StructuringValidator.parseItems(json, IDS, dual,
                StructuringValidator.actionsFor(VideoType.INTERVIEW_JA, !dual.isEmpty()));
    }

    @Test
    void parsesFlatItems() {
        Items items = parse(OK, Set.of(3));
        assertNull(items.error());
        assertEquals(new Item(4, Action.LINE, false, List.of(Flag.TEXT_UNSURE)), items.items().get(1));
    }

    @Test
    void rejectsBadItems() {
        assertTrue(parse(OK, Set.of()).error().contains("action"));
        assertTrue(StructuringValidator.parseItems(OK, IDS, Set.of(4),
                StructuringValidator.actionsFor(VideoType.INTERVIEW_JA, true)).error().contains("BOTH_JA_LINE"));
        assertTrue(parse(OK.replace("\"id\": 4", "\"id\": 6"), Set.of(3)).error().contains("빠짐 [4], 초과 [6]"));
        assertTrue(parse(OK.replace("\"id\": 3", "\"id\": 9").replace("\"id\": 5", "\"id\": 3")
                .replace("\"id\": 9", "\"id\": 5"), Set.of(3)).error().contains("순서"));
        assertTrue(parse("{\"items\": [", Set.of(3)).error().contains("JSON"));
        assertTrue(parse("{\"items\": [{\"id\": \"x\"}, {}]}", Set.of(3)).error().contains("빠짐 [3, 4, 5]"));
        assertTrue(parse("[]", Set.of(3)).error().contains("items"));
        assertTrue(parse(OK.replace("\"newRow\": false", "\"newRow\": \"no\""), Set.of(3)).error().contains("newRow"));
        assertTrue(parse(OK.replace("TEXT_UNSURE\", \"TEXT", "TEXT_UNSURE\", \"BAD"), Set.of(3)).error().contains("flags"));
    }

    @Test
    void rejectsRowsOutOfOrderAcrossKinds() {
        assertTrue(check(List.of(row(RowKind.QUESTION_JA, 4), row(RowKind.LINE, 3), row(RowKind.LINE, 4, 5)), List.of(),
                Set.of(4)).contains("순서"));
    }
}

package koja.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import koja.core.Project.Flag;
import koja.core.Project.RowKind;
import koja.core.Project.VideoType;
import koja.core.StageStore.Unit;
import koja.core.StageStore.UnitRow;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 5단계 구조화 출력 검증(절대 규칙 1, D-031·D-045·D-053). LLM 출력은 믿지 않는다.
 * 1차: 평탄 items가 요청 단위 id와 같은 순서·개수인지. 2차: 변환된 행·무시 목록이 "모든 단위가 행 또는 무시 목록에
 * 순서대로 한 번씩"인지. 예외는 한국어 후보가 있는 단위만 QUESTION_JA 행과 한국어 행에 한 번씩 함께 쓰는 것(D-045).
 */
public final class StructuringValidator {
    /** 구간별 분류(D-053). BOTH_JA_LINE은 한 단위를 QUESTION_JA 행과 LINE 행에 함께 쓴다(D-045). */
    public enum Action { LINE, QUESTION_KO, QUESTION_JA, BOTH_JA_LINE, IGNORE }

    public record Item(int id, Action action, boolean newRow, List<Flag> flags) {}

    /** items가 맞으면 error가 null이다. */
    public record Items(List<Item> items, String error) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StructuringValidator() {}

    /** 영상 유형별로 쓸 수 있는 분류. 자유 발언형에는 질문 영역을 만들지 않는다(D-053). */
    public static List<Action> actionsFor(VideoType type, boolean hasKoCandidate) {
        return switch (type) {
            case FREE -> List.of(Action.LINE, Action.IGNORE);
            case INTERVIEW_KO -> List.of(Action.LINE, Action.QUESTION_KO, Action.IGNORE);
            case INTERVIEW_JA, MIXED -> {
                List<Action> a = new ArrayList<>(List.of(Action.LINE));
                if (type == VideoType.MIXED) {
                    a.add(Action.QUESTION_KO);
                }
                a.add(Action.QUESTION_JA);
                a.add(Action.IGNORE);
                if (hasKoCandidate) {
                    a.add(Action.BOTH_JA_LINE);
                }
                yield List.copyOf(a);
            }
        };
    }

    /** LLM 응답 본문(JSON)을 읽고 1차 검증한다. */
    public static Items parseItems(String content, List<Integer> ids, Set<Integer> dual, List<Action> allowed) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (JacksonException e) {
            return new Items(List.of(), "JSON으로 읽을 수 없습니다.");
        }
        JsonNode arr = root == null ? null : root.get("items");
        if (arr == null || !arr.isArray()) {
            return new Items(List.of(), "items 배열이 있는 객체가 아닙니다.");
        }
        List<Integer> got = new ArrayList<>();
        for (JsonNode n : arr) {
            got.add(n.isObject() && n.path("id").isInt() ? n.get("id").asInt() : null);
        }
        if (!got.equals(ids)) {
            // id가 없거나 정수가 아닌 항목은 null이라 TreeSet에 넣지 않는다(NPE면 청크 재시도 대신 실행 전체가 멈춘다).
            List<Integer> valid = got.stream().filter(Objects::nonNull).toList();
            Set<Integer> missing = new TreeSet<>(ids);
            missing.removeAll(valid);
            Set<Integer> extra = new TreeSet<>();
            for (Integer i : valid) {
                if (!ids.contains(i)) {
                    extra.add(i);
                }
            }
            return new Items(List.of(), "items의 id가 입력 순서·개수와 다릅니다(빠짐 " + missing + ", 초과 " + extra + ", 길이 "
                    + got.size() + "/" + ids.size() + ").");
        }
        List<Item> items = new ArrayList<>(ids.size());
        List<Integer> badBoth = new ArrayList<>();
        for (JsonNode n : arr) {
            int id = n.get("id").asInt();
            Action action = enumOf(Action.class, n.path("action"));
            if (action == null || !allowed.contains(action)) {
                return new Items(List.of(), "구간 " + id + "에 허용되지 않은 action: " + n.path("action"));
            }
            if (!n.path("newRow").isBoolean()) {
                return new Items(List.of(), "구간 " + id + "의 newRow가 불리언이 아닙니다.");
            }
            JsonNode fl = n.path("flags");
            if (!fl.isArray()) {
                return new Items(List.of(), "구간 " + id + "의 flags가 올바르지 않습니다.");
            }
            List<Flag> flags = new ArrayList<>();
            for (JsonNode f : fl) {
                Flag flag = enumOf(Flag.class, f);
                if (flag == null) {
                    return new Items(List.of(), "구간 " + id + "의 flags가 올바르지 않습니다.");
                }
                if (!flags.contains(flag)) {
                    flags.add(flag);
                }
            }
            if (action == Action.BOTH_JA_LINE && !dual.contains(id)) {
                badBoth.add(id);
            }
            items.add(new Item(id, action, n.get("newRow").asBoolean(), List.copyOf(flags)));
        }
        if (!badBoth.isEmpty()) {
            return new Items(List.of(), "ko후보가 없는 구간에 BOTH_JA_LINE을 썼습니다: " + badBoth);
        }
        return new Items(List.copyOf(items), null);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode n) {
        if (!n.isString()) {
            return null;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(n.asString())) {
                return e;
            }
        }
        return null;
    }

    /** 전체 결과 검증. 맞으면 null, 틀리면 LLM에 그대로 돌려줄 수 있는 오류 문장. */
    public static String checkRows(List<UnitRow> rows, List<Integer> ignored, List<Unit> units, VideoType type) {
        List<Integer> ids = units.stream().map(Unit::id).toList();
        Set<Integer> dual = new HashSet<>();
        for (Unit u : units) {
            if (u.hasKoCandidate()) {
                dual.add(u.id());
            }
        }
        return checkRows(rows, ignored, ids, dual, type);
    }

    /** 청크 검증에도 쓴다. ids는 이번에 처리할 단위 id, dual은 그중 한국어 후보가 있는 id. */
    public static String checkRows(List<UnitRow> rows, List<Integer> ignored, List<Integer> ids, Set<Integer> dual,
            VideoType type) {
        if (rows == null || ignored == null) {
            return "rows와 ignoredUnitIds가 있어야 합니다.";
        }
        List<Action> allowed = actionsFor(type, true);
        List<Integer> ko = new ArrayList<>();
        List<Integer> ja = new ArrayList<>();
        int prevFirst = Integer.MIN_VALUE;
        for (UnitRow r : rows) {
            if (r == null || r.kind() == null || r.unitIds() == null || r.unitIds().isEmpty() || r.flags() == null
                    || r.unitIds().stream().anyMatch(Objects::isNull) || r.flags().stream().anyMatch(Objects::isNull)) {
                return "rows 항목에 kind 또는 비어 있지 않은 단위 id가 없습니다.";
            }
            if (!allowed.contains(Action.valueOf(r.kind().name()))) {
                return type + " 영상에 쓸 수 없는 행 종류: " + r.kind();
            }
            // 투영이 행 순서를 그대로 쓰므로 행끼리도 첫 단위 순서여야 한다. 같은 단위로 시작하는 건 D-045 쌍뿐이다.
            if (r.unitIds().getFirst() < prevFirst) {
                return "행이 첫 구간ID 순서가 아닙니다: " + r.unitIds();
            }
            prevFirst = r.unitIds().getFirst();
            (r.kind() == RowKind.QUESTION_JA ? ja : ko).addAll(r.unitIds());
        }
        // List.of의 contains(null)은 NPE를 던져 stream으로 본다.
        if (ignored.stream().anyMatch(Objects::isNull)) {
            return "ignoredUnitIds에 null이 있습니다.";
        }
        for (List<Integer> xs : List.of(ko, ja, ignored)) {
            for (int i = 1; i < xs.size(); i++) {
                if (xs.get(i) <= xs.get(i - 1)) {
                    String name = xs == ko ? "한국어 행" : xs == ja ? "QUESTION_JA 행" : "ignoredUnitIds";
                    return name + "의 구간ID를 이어 읽으면 중복 없는 오름차순이어야 합니다.";
                }
            }
        }
        Set<Integer> both = new TreeSet<>(ko);
        both.retainAll(new HashSet<>(ja));
        both.removeAll(dual);
        Set<Integer> used = new HashSet<>(ko);
        used.addAll(ja);
        Set<Integer> ignUsed = new TreeSet<>(ignored);
        ignUsed.retainAll(used);
        Set<Integer> all = new HashSet<>(used);
        all.addAll(ignored);
        Set<Integer> missing = new TreeSet<>(ids);
        missing.removeAll(all);
        Set<Integer> extra = new TreeSet<>(all);
        extra.removeAll(new HashSet<>(ids));
        if (!both.isEmpty() || !ignUsed.isEmpty() || !missing.isEmpty() || !extra.isEmpty()) {
            return "빠진 구간ID " + missing + ", 무시하면서 행에도 쓴 ID " + ignUsed + ", ko후보 없이 질문·한국어 행에 함께 쓴 ID "
                    + both + ", 처리할 구간이 아닌 ID " + extra + ".";
        }
        return null;
    }
}

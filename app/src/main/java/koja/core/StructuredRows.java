package koja.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import koja.core.Project.Flag;
import koja.core.Project.Row;
import koja.core.Project.RowKind;
import koja.core.Project.Speaker;
import koja.core.Project.Word;
import koja.core.StageStore.Unit;
import koja.core.StageStore.UnitRow;
import koja.core.StructuringValidator.Action;
import koja.core.StructuringValidator.Item;

/**
 * 5단계 구조화의 결정적 변환(D-053): 검증된 items → 행·무시 목록, 청크 경계 확정(settle, D-055), 대체 청크,
 * 그리고 rows.json 단위 행 → project.json 행 투영(D-073). LLM은 id와 분류만 주고 텍스트·시간은 여기서 단위에서 가져온다.
 */
public final class StructuredRows {
    static final int MAX_MERGE_CUR_HANGUL = 16;
    static final int MAX_MERGE_TOTAL_HANGUL = 32;
    static final long MAX_MERGE_CUR_MS = 4_500;
    private static final String KOREAN = "ko";
    private static final String JAPANESE = "ja";

    /** 청크 하나의 결과. rows는 단위 id 순, 행 id는 아직 없다. */
    public record Chunk(List<UnitRow> rows, List<Integer> ignored) {}

    /** 확정분과 다음 청크 시작 단위 id(cut, 끝이면 null). */
    public record Settled(List<UnitRow> rows, List<Integer> ignored, Integer cut) {}

    private StructuredRows() {}

    /** 구간별 분류를 행으로 묶는다. 한국어 행은 newRow=false여도 화자 변경·문장 끝·길이 상한에서 나눈다(과병합 방지). */
    public static Chunk toRows(List<Item> items, Map<Integer, Unit> units) {
        List<Building> rows = new ArrayList<>();
        List<Integer> ignored = new ArrayList<>();
        Building ko = null;
        Building ja = null;
        for (Item it : items) {
            Unit u = units.get(it.id());
            switch (effective(it.action(), u)) {
                case IGNORE -> {
                    ignored.add(it.id());
                    ko = null;
                    ja = null;
                }
                case LINE, QUESTION_KO -> {
                    ja = null;
                    RowKind kind = RowKind.valueOf(effective(it.action(), u).name());
                    List<Flag> fl = union(it.flags(), autoFlags(u));
                    if (ko != null && !it.newRow() && ko.kind == kind && same(ko.speaker, u.speakerId())
                            && canMerge(ko, u, units)) {
                        ko.add(it.id(), fl);
                    } else {
                        ko = new Building(kind, u.speakerId(), it.id(), fl);
                        rows.add(ko);
                    }
                }
                case QUESTION_JA, BOTH_JA_LINE -> {
                    ko = null;
                    if (ja != null && !it.newRow()) {
                        ja.add(it.id(), it.flags());
                    } else {
                        ja = new Building(RowKind.QUESTION_JA, null, it.id(), it.flags());
                        rows.add(ja);
                    }
                    if (it.action() == Action.BOTH_JA_LINE) {
                        ko = new Building(RowKind.LINE, u.speakerId(), it.id(), union(it.flags(), autoFlags(u)));
                        rows.add(ko);
                    }
                }
            }
        }
        return new Chunk(rows.stream().map(Building::row).toList(), List.copyOf(ignored));
    }

    /**
     * 일본어가 아닌 단위는 질문 영역에 쓰면 일본어가 아닌 원문이 시간 없이 일본어 칸에 들어간다. 거부하면 E4B가 재시도에서
     * 못 고쳐 대체 청크가 늘었다(맥 실측). 빠뜨리지 않도록 LINE으로 남겨 검수에 맡긴다(D-032).
     */
    static Action effective(Action action, Unit u) {
        return (action == Action.QUESTION_JA || action == Action.BOTH_JA_LINE) && !JAPANESE.equals(u.lang()) ? Action.LINE : action;
    }

    /** 검증을 끝내 통과하지 못한 청크는 단위마다 LINE 한 행으로 남긴다(빠뜨리지 않기, D-032). */
    public static Chunk fallback(List<Unit> chunk) {
        return new Chunk(chunk.stream().map(u -> new UnitRow(null, RowKind.LINE, List.of(u.id()), autoFlags(u))).toList(),
                List.of());
    }

    /**
     * 끝 행과, 그 첫 단위 이후 id를 하나라도 가진 행은 다음 청크에서 다시 묻는다(청크 경계 절단 방지, D-055).
     * 확정한 행은 cut 앞 id만, 다시 물을 행은 cut 이후 id만 갖는다. cut이 청크 첫 id 이하로 내려가면 전부 확정한다(진행 보장).
     */
    public static Settled settle(Chunk chunk, int firstId, boolean lastChunk) {
        List<UnitRow> keep = new ArrayList<>(chunk.rows());
        keep.sort((a, b) -> Integer.compare(a.unitIds().getFirst(), b.unitIds().getFirst()));
        if (lastChunk || keep.isEmpty()) {
            return new Settled(List.copyOf(keep), chunk.ignored(), null);
        }
        List<UnitRow> all = List.copyOf(keep);
        int cut = keep.getLast().unitIds().getFirst();
        while (true) {
            int c = cut;
            List<UnitRow> late = keep.stream().filter(r -> r.unitIds().getLast() >= c).toList();
            if (late.isEmpty()) {
                break;
            }
            keep.removeAll(late);
            for (UnitRow r : late) {
                cut = Math.min(cut, r.unitIds().getFirst());
            }
        }
        if (cut <= firstId) {
            return new Settled(all, chunk.ignored(), null);
        }
        int c = cut;
        return new Settled(List.copyOf(keep), chunk.ignored().stream().filter(i -> i < c).toList(), cut);
    }

    /** 절대 규칙 2 보강: 화자를 모르거나 한국어 후보가 거의 없는 비한국어 단위는 표시한다(D-053). */
    static List<Flag> autoFlags(Unit u) {
        List<Flag> out = new ArrayList<>(2);
        if (u.speakerId() == null) {
            out.add(Flag.SPEAKER_UNSURE);
        }
        if (!KOREAN.equals(u.lang()) && ClauseSplitter.hangul(koText(u)) < 2) {
            out.add(Flag.TEXT_UNSURE);
        }
        return List.copyOf(out);
    }

    private static boolean canMerge(Building row, Unit u, Map<Integer, Unit> units) {
        Unit prev = units.get(row.ids.getLast());
        if (ClauseSplitter.END.matcher(koText(prev).strip()).find()) {
            return false;
        }
        int cur = 0;
        for (int id : row.ids) {
            cur += ClauseSplitter.hangul(koText(units.get(id)));
        }
        long ms = prev.endMs() - units.get(row.ids.getFirst()).startMs();
        return cur < MAX_MERGE_CUR_HANGUL && ms < MAX_MERGE_CUR_MS
                && cur + ClauseSplitter.hangul(koText(u)) <= MAX_MERGE_TOTAL_HANGUL;
    }

    /** 한국어 행에 들어가는 텍스트: 한국어 단위는 1차 원문, 아니면 한국어 후보, 후보가 없으면 빈 값(D-040·D-071). */
    static String koText(Unit u) {
        return KOREAN.equals(u.lang()) ? u.text() : u.ko() == null ? "" : u.ko().text();
    }

    private static List<Word> koWords(Unit u) {
        return KOREAN.equals(u.lang()) || u.ko() == null ? u.words() : u.ko().words();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static List<Flag> union(List<Flag> a, List<Flag> b) {
        List<Flag> out = new ArrayList<>(a);
        for (Flag f : b) {
            if (!out.contains(f)) {
                out.add(f);
            }
        }
        return List.copyOf(out);
    }

    /**
     * rows.json → project.json 행·무시 목록(D-073). segIds는 부모 STT 구간 id, 무시 목록은 모든 단위가 무시된 STT 구간 id.
     * 한국어 행 ja는 번역(7단계)이 채운다. QUESTION_JA는 1차 원문을 ja에 두고 시간·화자를 비운다(설계 5장).
     * 화자가 없거나 미확인이면 SPEAKER_UNSURE(절대 규칙 2, D-071과 같은 기준).
     */
    public static Projection project(List<UnitRow> rows, List<Integer> ignoredUnitIds, List<Unit> units,
            List<Speaker> speakers) {
        Map<Integer, Unit> byId = units.stream().collect(Collectors.toMap(Unit::id, Function.identity()));
        Set<String> confirmed = speakers.stream().filter(Speaker::confirmed).map(Speaker::id).collect(Collectors.toSet());
        List<Row> out = new ArrayList<>(rows.size());
        Set<Integer> usedSegs = new HashSet<>();
        for (UnitRow r : rows) {
            List<Unit> us = r.unitIds().stream().map(byId::get).toList();
            List<Integer> segIds = List.copyOf(new TreeSet<>(us.stream().map(Unit::segId).toList()));
            usedSegs.addAll(segIds);
            if (r.kind() == RowKind.QUESTION_JA) {
                out.add(new Row(r.id(), r.kind(), null, null, null, joinText(us, Unit::text), segIds, r.flags()));
                continue;
            }
            Unit first = us.getFirst();
            // 행 시간은 한국어 칸에 실제로 보이는 첫 단어 시각이다. 후보 없는 비한국어 단위가 앞에 붙어도 그 시각을 쓰지 않는다.
            Unit shown = us.stream().filter(u -> !koText(u).isBlank()).findFirst().orElse(first);
            List<Word> words = koWords(shown);
            long start = words.isEmpty() ? shown.startMs() : words.getFirst().startMs();
            List<Flag> flags = r.flags();
            if (first.speakerId() == null || !confirmed.contains(first.speakerId())) {
                flags = union(List.of(Flag.SPEAKER_UNSURE), flags);
            }
            out.add(new Row(r.id(), r.kind(), start, first.speakerId(), joinText(us, StructuredRows::koText), null,
                    segIds, flags));
        }
        Set<Integer> ignoredSegs = new TreeSet<>();
        for (int id : ignoredUnitIds) {
            int seg = byId.get(id).segId();
            if (!usedSegs.contains(seg)) {
                ignoredSegs.add(seg);
            }
        }
        return new Projection(List.copyOf(out), List.copyOf(ignoredSegs));
    }

    public record Projection(List<Row> rows, List<Integer> ignoredSegIds) {}

    private static String joinText(List<Unit> us, Function<Unit, String> text) {
        return us.stream().map(text).map(String::strip).filter(t -> !t.isEmpty()).collect(Collectors.joining(" "));
    }

    /** 행을 만드는 중의 가변 상태. */
    private static final class Building {
        final RowKind kind;
        final String speaker;
        final List<Integer> ids = new ArrayList<>();
        final List<Flag> flags = new ArrayList<>();

        Building(RowKind kind, String speaker, int id, List<Flag> fl) {
            this.kind = kind;
            this.speaker = speaker;
            add(id, fl);
        }

        void add(int id, List<Flag> fl) {
            ids.add(id);
            for (Flag f : fl) {
                if (!flags.contains(f)) {
                    flags.add(f);
                }
            }
        }

        UnitRow row() {
            return new UnitRow(null, kind, List.copyOf(ids), List.copyOf(flags));
        }
    }
}

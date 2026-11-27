package koja.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import koja.core.Project.KoCandidate;
import koja.core.Project.SttSegment;
import koja.core.Project.Word;
import koja.core.StageStore.Turn;
import koja.core.StageStore.Unit;

/**
 * 5단계 구조화 입력 단위(D-054 `clause`): STT 구간을 1차 언어 기준 문장 끝·화자 전환·한국어 절 경계에서 단어 단위로 나눈다.
 * 텍스트와 시간은 STT 단어를 나눈 것뿐이다(절대 규칙 1). 화자는 diar.json 턴을 단위 시각에 직접 맞추고(D-064),
 * 안 맞으면 부모 구간 화자, 그것도 없으면 null.
 */
public final class ClauseSplitter {
    static final Pattern END = Pattern.compile("[.?!。？！]$");
    private static final Pattern END_CLAUSE = Pattern.compile("[,、]$");
    private static final Pattern KO_END = Pattern.compile(
            "(습니다|입니다|세요|어요|아요|해요|예요|에요|거든요|네요|군요|죠|요|다|까|네)[,.?!]?$");
    private static final Pattern KO_CONN = Pattern.compile(
            "(는데|는데요|고요|면서|니까|니까요|지만|지만요|어서|아서|해서|해서요|고|서|면|으면|거나|든지|길래|더라도)[,.?!]?$");
    private static final String KOREAN = "ko";

    private ClauseSplitter() {}

    /** turns의 speaker는 화자표 id(S1…)여야 한다. 단위 id는 1부터 매긴다. */
    public static List<Unit> split(List<SttSegment> segments, List<Turn> turns) {
        List<Unit> units = new ArrayList<>();
        for (SttSegment s : segments) {
            for (Unit u : splitSegment(s, turns)) {
                String speaker = speakerOf(u.startMs(), u.endMs(), turns);
                units.add(new Unit(units.size() + 1, s.id(), u.startMs(), u.endMs(), u.lang(),
                        speaker != null ? speaker : s.speakerId(), u.text(), u.words(), u.ko()));
            }
        }
        return List.copyOf(units);
    }

    private static List<Unit> splitSegment(SttSegment s, List<Turn> turns) {
        Unit whole = new Unit(0, s.id(), s.startMs(), s.endMs(), s.lang(), null, s.text(), s.words(), s.ko());
        boolean korean = KOREAN.equals(s.lang());
        KoCandidate ko = s.ko();
        List<Word> side = korean || !s.words().isEmpty() ? s.words() : ko == null ? List.of() : ko.words();
        // 1차 단어가 없는 비한국어 구간은 한국어 후보 단어 시각으로 자른다.
        boolean useKo = !korean && s.words().isEmpty() && ko != null;
        List<List<Word>> pieces = splitWords(side, turns, korean);
        if (pieces.size() < 2) {
            return List.of(whole);
        }
        List<Word> otherWords = useKo ? s.words() : ko == null ? List.of() : ko.words();
        String otherText = useKo ? s.text() : ko == null ? "" : ko.text();
        // 다른 쪽 원문에 단어 시각이 없으면 나눌 때 그 원문이 사라진다(절대 규칙 1).
        if (ko != null && !otherText.isBlank() && otherWords.isEmpty()) {
            return List.of(whole);
        }
        List<List<Word>> bins = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            bins.add(new ArrayList<>());
        }
        for (Word w : otherWords) {
            int bin = 0;
            for (int i = 1; i < pieces.size(); i++) {
                if (w.startMs() >= pieces.get(i).getFirst().startMs()) {
                    bin = i;
                }
            }
            bins.get(bin).add(w);
        }
        List<Unit> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            List<Word> p = List.copyOf(pieces.get(i));
            List<Word> o = List.copyOf(bins.get(i));
            long start = p.getFirst().startMs();
            long end = p.getLast().endMs();
            Unit u;
            if (ko == null) {
                u = new Unit(0, s.id(), start, end, s.lang(), null, join(p), p, null);
            } else if (useKo) {
                u = new Unit(0, s.id(), start, end, s.lang(), null, join(o), o, new KoCandidate(join(p), p));
            } else {
                u = new Unit(0, s.id(), start, end, s.lang(), null, join(p), p, new KoCandidate(join(o), o));
            }
            out.add(u);
        }
        return out;
    }

    static List<List<Word>> splitWords(List<Word> words, List<Turn> turns, boolean korean) {
        List<List<Word>> out = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        for (Word w : words) {
            if (!cur.isEmpty() && cutBefore(cur, w, turns, korean)) {
                out.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(w);
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static boolean cutBefore(List<Word> cur, Word w, List<Turn> turns, boolean korean) {
        Word prev = cur.getLast();
        long dur = prev.endMs() - cur.getFirst().startMs();
        long gap = w.startMs() - prev.endMs();
        String pt = prev.text().strip();
        String sp1 = speakerOf(prev.startMs(), prev.endMs(), turns);
        String sp2 = speakerOf(w.startMs(), w.endMs(), turns);
        boolean turn = sp1 != null && sp2 != null && !sp1.equals(sp2);
        if (!korean) {
            // 비한국어 구간은 한국어 후보의 환각 부호로 자르지 않는다(D-054).
            return (turn && dur >= 1500) || (END.matcher(pt).find() && dur >= 8000);
        }
        int h = hangul(join(cur));
        return END.matcher(pt).find()
                || (turn && dur >= 1000)
                || (END_CLAUSE.matcher(pt).find() && h >= 8)
                || (gap >= 250 && h >= 8)
                || (h >= 10 && KO_END.matcher(pt).find())
                || (h >= 14 && KO_CONN.matcher(pt).find())
                || h >= 24;
    }

    /** 겹침이 가장 긴 턴 하나의 화자. 없으면 null. */
    static String speakerOf(long startMs, long endMs, List<Turn> turns) {
        long best = 0;
        String who = null;
        for (Turn t : turns) {
            long ov = Math.min(endMs, t.endMs()) - Math.max(startMs, t.startMs());
            if (ov > best) {
                best = ov;
                who = t.speaker();
            }
        }
        return who;
    }

    static String join(List<Word> words) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            sb.append(w.text());
        }
        return sb.toString().strip();
    }

    /** 한글 음절 수. 팀·대회 이름은 시트에 영문, STT에 음차로 적혀 한글만 센다. */
    public static int hangul(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '가' && c <= '힣') {
                n++;
            }
        }
        return n;
    }
}

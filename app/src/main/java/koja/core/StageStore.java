package koja.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import koja.core.Project.Flag;
import koja.core.Project.KoCandidate;
import koja.core.Project.RowKind;
import koja.core.Project.SttSegment;
import koja.core.Project.VideoType;
import koja.core.Project.Word;

/** 단계 결과 파일(stt.json, diar.json, rows.json) 읽기·쓰기. 엔진은 자기 단계 파일만 만든다(AGENTS.md 엔진 추가 규칙). */
public final class StageStore {
    public static final int CURRENT_VERSION = 1;

    /** stt.json. 구간 id는 1부터 오름차순, speakerId는 비운다(D-048). */
    public record SttResult(int version, String engine, List<SttSegment> segments) {}

    /** diar.json. speaker는 엔진이 붙인 임의 라벨이다. 화자표 id(S1…)로 바꾸는 건 파이프라인이 한다. */
    public record DiarResult(int version, String engine, List<Turn> turns) {}

    public record Turn(long startMs, long endMs, String speaker) {}

    /**
     * rows.json(5단계 구조화, D-073). units는 STT 구간을 단어 경계로 나눈 구조화 단위(D-054)이고,
     * rows·ignoredUnitIds는 단위 id만 가리킨다. 시간·원문은 units(STT 단어)에서만 온다(절대 규칙 1).
     */
    public record RowsResult(int version, String engine, VideoType videoType, Map<String, SpeakerRole> speakerRoles,
            List<Unit> units, List<UnitRow> rows, List<Integer> ignoredUnitIds) {}

    /** 구조화 단위. segId는 부모 STT 구간, speakerId는 모르면 null. 텍스트·시간은 부모 구간 단어를 나눈 것이다. */
    public record Unit(int id, int segId, long startMs, long endMs, String lang, String speakerId, String text,
            List<Word> words, KoCandidate ko) {
        public Unit {
            words = words == null ? List.of() : words;
        }

        /** 2회 방식 한국어 후보가 있는 단위(D-045 예외 대상). */
        public boolean hasKoCandidate() {
            return ko != null && !ko.text().isBlank();
        }
    }

    /** 구조화 행. 내용은 단위 id로만 가리키고 텍스트·시간은 투영할 때 단위에서 가져온다. */
    public record UnitRow(String id, RowKind kind, List<Integer> unitIds, List<Flag> flags) {}

    /** 1단계 영상 분석이 추정한 화자 역할(D-053). */
    public enum SpeakerRole { INTERVIEWER_JA, INTERVIEWER_KO, INTERVIEWEE, INTERPRETER, STAFF, UNKNOWN }

    private StageStore() {}

    public static SttResult loadStt(Path file) throws IOException {
        SttResult r = JsonFiles.read(file, SttResult.class, true);
        validate(r, file);
        return r;
    }

    public static void saveStt(SttResult result, Path file) throws IOException {
        validate(result, file);
        JsonFiles.write(result, file);
    }

    public static DiarResult loadDiar(Path file) throws IOException {
        DiarResult r = JsonFiles.read(file, DiarResult.class, true);
        validate(r, file);
        return r;
    }

    public static void saveDiar(DiarResult result, Path file) throws IOException {
        validate(result, file);
        JsonFiles.write(result, file);
    }

    public static RowsResult loadRows(Path file) throws IOException {
        RowsResult r = JsonFiles.read(file, RowsResult.class, true);
        validate(r, file);
        return r;
    }

    public static void saveRows(RowsResult result, Path file) throws IOException {
        validate(result, file);
        JsonFiles.write(result, file);
    }

    // 엔진 출력은 믿지 않는다. 구간 id·시간이 틀리면 뒤 단계(행 검증, D-045)가 엉뚱한 구간을 가리킨다.
    private static void validate(SttResult r, Path file) throws IOException {
        if (r == null || r.version() != CURRENT_VERSION || r.segments() == null) {
            throw new IOException("stt.json 버전이 " + CURRENT_VERSION + "이 아니거나 segments가 없습니다: " + file);
        }
        int prev = 0;
        for (SttSegment s : r.segments()) {
            if (s == null || s.id() <= prev || s.speakerId() != null || s.startMs() < 0 || s.endMs() < s.startMs() || s.text() == null
                    || !validWords(s.words()) || (s.ko() != null && (s.ko().text() == null || !validWords(s.ko().words())))) {
                throw new IOException("stt.json 구간이 잘못됐습니다(id 오름차순, speakerId 없음, 구간·단어 0 ≤ startMs ≤ endMs, text 필수): "
                        + (s == null ? "null" : "id " + s.id()) + ", " + file);
            }
            prev = s.id();
        }
    }

    // 행 startMs는 첫 단어 시간이라(D-040) 단어 시간이 틀리면 타임라인이 틀린다.
    private static boolean validWords(List<Word> words) {
        for (Word w : words) {
            if (w == null || w.startMs() < 0 || w.endMs() < w.startMs() || w.text() == null) {
                return false;
            }
        }
        return true;
    }

    private static void validate(DiarResult r, Path file) throws IOException {
        if (r == null || r.version() != CURRENT_VERSION || r.turns() == null) {
            throw new IOException("diar.json 버전이 " + CURRENT_VERSION + "이 아니거나 turns가 없습니다: " + file);
        }
        for (Turn t : r.turns()) {
            if (t == null || t.startMs() < 0 || t.endMs() < t.startMs() || t.speaker() == null || t.speaker().isBlank()) {
                throw new IOException("diar.json 턴이 잘못됐습니다(0 ≤ startMs ≤ endMs, speaker 필수): " + file);
            }
        }
    }

    // LLM이 만든 결과라 절대 규칙 1(D-031·D-045)을 읽기·쓰기 때마다 다시 확인한다.
    private static void validate(RowsResult r, Path file) throws IOException {
        if (r == null || r.version() != CURRENT_VERSION || r.videoType() == null || r.speakerRoles() == null
                || r.units() == null || r.rows() == null || r.ignoredUnitIds() == null) {
            throw new IOException("rows.json 버전이 " + CURRENT_VERSION + "이 아니거나 필수 항목이 없습니다: " + file);
        }
        int prevId = 0;
        // STT 구간 id는 1부터라 0은 없는 구간을 가리킨다.
        int prevSeg = 1;
        for (Unit u : r.units()) {
            if (u == null || u.id() != prevId + 1 || u.segId() < prevSeg || u.startMs() < 0 || u.endMs() < u.startMs()
                    || u.text() == null || !validWords(u.words())
                    || (u.ko() != null && (u.ko().text() == null || !validWords(u.ko().words())))) {
                throw new IOException("rows.json 단위가 잘못됐습니다(id 1부터 연속, segId 오름차순, 0 ≤ startMs ≤ endMs, text 필수): "
                        + (u == null ? "null" : "id " + u.id()) + ", " + file);
            }
            prevId = u.id();
            prevSeg = u.segId();
        }
        String error = StructuringValidator.checkRows(r.rows(), r.ignoredUnitIds(), r.units(), r.videoType());
        if (error != null) {
            throw new IOException("rows.json이 구간 규칙을 어겼습니다: " + error + ", " + file);
        }
        // 검수 화면이 행 id로 편집을 맞추므로 비거나 겹치면 안 된다.
        if (r.rows().stream().map(UnitRow::id).filter(id -> id != null && !id.isBlank()).distinct().count() != r.rows().size()) {
            throw new IOException("rows.json 행 id가 비었거나 겹칩니다: " + file);
        }
    }
}

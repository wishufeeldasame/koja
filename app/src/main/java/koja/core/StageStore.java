package koja.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import koja.core.Project.SttSegment;

/** 단계 결과 파일(stt.json, diar.json) 읽기·쓰기. 엔진은 자기 단계 파일만 만든다(AGENTS.md 엔진 추가 규칙). */
public final class StageStore {
    public static final int CURRENT_VERSION = 1;

    /** stt.json. 구간 id는 1부터 오름차순, speakerId는 비운다(D-048). */
    public record SttResult(int version, String engine, List<SttSegment> segments) {}

    /** diar.json. speaker는 엔진이 붙인 임의 라벨이다. 화자표 id(S1…)로 바꾸는 건 파이프라인이 한다. */
    public record DiarResult(int version, String engine, List<Turn> turns) {}

    public record Turn(long startMs, long endMs, String speaker) {}

    private StageStore() {}

    public static SttResult loadStt(Path file) throws IOException {
        SttResult r = JsonFiles.read(file, SttResult.class);
        validate(r, file);
        return r;
    }

    public static void saveStt(SttResult result, Path file) throws IOException {
        validate(result, file);
        JsonFiles.write(result, file);
    }

    public static DiarResult loadDiar(Path file) throws IOException {
        DiarResult r = JsonFiles.read(file, DiarResult.class);
        validate(r, file);
        return r;
    }

    public static void saveDiar(DiarResult result, Path file) throws IOException {
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
            if (s == null || s.id() <= prev || s.startMs() < 0 || s.endMs() < s.startMs() || s.text() == null) {
                throw new IOException("stt.json 구간이 잘못됐습니다(id 오름차순, 0 ≤ startMs ≤ endMs, text 필수): "
                        + (s == null ? "null" : "id " + s.id()) + ", " + file);
            }
            prev = s.id();
        }
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
}

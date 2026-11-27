package koja.engine;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleConsumer;
import koja.core.ClauseSplitter;
import koja.core.Project;
import koja.core.Project.Speaker;
import koja.core.Project.SttSegment;
import koja.core.ProjectFiles;
import koja.core.ProjectStore;
import koja.core.SegmentRows;
import koja.core.StructuredRows;
import koja.core.StageStore;
import koja.core.StageStore.DiarResult;
import koja.core.StageStore.RowsResult;
import koja.core.StageStore.SttResult;
import koja.core.StageStore.Turn;

/**
 * 단계를 순서대로 하나씩 돌린다: STT → stt.json, 화자 구분 → diar.json, 그 뒤 화자를 구간에 붙이고 행을 만들어 project.json에 저장.
 * LLM을 주면 행은 5단계 구조화(rows.json, D-053~D-055·D-073)로, 안 주면 M2 방식(구간 1개 = 행 1개, D-071)으로 만든다.
 * stages.<단계>.done이고 결과 파일이 있으면 그 단계는 건너뛴다(중간에 멈춘 뒤 다시 돌리기).
 * 행은 STT나 화자 구분을 새로 돌렸거나 stages.rows가 없을 때만 다시 만든다. 그 밖에는 사람이 고친 행을 그대로 둔다.
 */
public final class Pipeline {
    public static final String STT = "stt";
    public static final String DIAR = "diar";
    public static final String ROWS = "rows";
    /** M2 행 만들기(구간 1개 = 행 1개, D-071). M3 구조화가 이 단계를 대신한다. */
    static final String ROWS_ENGINE = "segment-1to1";
    /** 발언이 없어 LLM 없이 만든 빈 구조화 결과. */
    static final String EMPTY_ROWS_ENGINE = "structure-empty";

    /** 엔진은 단계 직전에 연다. 서버를 띄우는 데 실패할 수 있어 IOException을 던진다. */
    @FunctionalInterface
    public interface Opener<E extends Engine> {
        E open() throws IOException;
    }

    /** 단계 이름(STT, DIAR)과 진행률. */
    @FunctionalInterface
    public interface Listener {
        void progress(String stage, double fraction);
    }

    // 실행 전체(읽기~마지막 저장)를 앱 전체에서 하나로 묶는다. 엔진이 한 번에 하나만 열리고(절대 규칙 5),
    // 같은 프로젝트를 두 실행이 동시에 고쳐 stt.json과 project.json이 서로 다른 실행을 가리키는 일도 막는다.
    private static final ReentrantLock RUN = new ReentrantLock();

    private Pipeline() {}

    /**
     * 단계를 다시 돌릴 때는 먼저 그 단계와 rows의 완료 표시를 지워 저장하므로, 어디서 멈춰도 다음 실행이 어긋난 결과를 완료로 보지 않는다.
     * 취소(스레드 interrupt)는 단계 사이·저장 중에 와도 InterruptedException으로 끝난다. 다른 실행이 돌고 있으면 끝날 때까지 기다린다.
     * 진행률 콜백 안에서 다시 부르면 IllegalStateException(엔진을 연 채 다른 엔진을 열게 된다).
     */
    public static Project run(Path projectFile, Opener<? extends Transcriber> stt, Opener<? extends Diarizer> diar,
            Listener listener) throws IOException, InterruptedException {
        return run(projectFile, stt, diar, null, listener);
    }

    /** llm이 null이면 M2 행 만들기. 아니면 행을 만들 때 5단계 구조화를 돌린다(화자 구분 엔진을 내린 뒤 연다, 절대 규칙 5). */
    public static Project run(Path projectFile, Opener<? extends Transcriber> stt, Opener<? extends Diarizer> diar,
            Opener<? extends LlmClient> llm, Listener listener) throws IOException, InterruptedException {
        if (RUN.isHeldByCurrentThread()) {
            throw new IllegalStateException("파이프라인 실행 중에 다시 실행할 수 없습니다");
        }
        RUN.lockInterruptibly();
        try {
            return runStages(projectFile, stt, diar, llm, listener);
        } catch (ClosedByInterruptException e) {
            InterruptedException ie = new InterruptedException("저장 중 취소");
            ie.initCause(e);
            throw ie;
        } finally {
            RUN.unlock();
        }
    }

    private static Project runStages(Path projectFile, Opener<? extends Transcriber> stt,
            Opener<? extends Diarizer> diar, Opener<? extends LlmClient> llm, Listener listener)
            throws IOException, InterruptedException {
        Path dir = projectFile.toAbsolutePath().getParent();
        Path audio = dir.resolve(ProjectFiles.AUDIO);
        Path sttFile = dir.resolve(ProjectFiles.STT);
        Path diarFile = dir.resolve(ProjectFiles.DIAR);
        Project project = ProjectStore.load(projectFile);

        SttResult sttResult;
        if (done(project, STT, sttFile)) {
            sttResult = StageStore.loadStt(sttFile);
        } else {
            // 결과 파일을 쓰기 전에 옛 완료 표시를 지워 둔다. 새 stt.json 저장 뒤 project.json 저장 전에 멈추면
            // 옛 stages.stt·rows가 새 구간 id를 가리키는 채로 남는다. 옛 행은 옛 구간 id를 가리키므로 rows도 지운다.
            project = invalidate(project, projectFile, STT);
            // 엔진 시작(서버 기동은 최대 몇 분)이 앞 단계 100%로 보이지 않게 먼저 알린다.
            listener.progress(STT, -1);
            try (Transcriber t = open(stt)) {
                sttResult = t.transcribe(audio, progress(listener, STT));
            }
            checkInterrupt();
            StageStore.saveStt(sttResult, sttFile);
            project = withStage(project, STT, sttResult.engine());
            ProjectStore.save(project, projectFile);
        }

        DiarResult diarResult;
        boolean newDiar = !done(project, DIAR, diarFile);
        if (!newDiar) {
            diarResult = StageStore.loadDiar(diarFile);
        } else {
            checkInterrupt();
            project = invalidate(project, projectFile, DIAR);
            listener.progress(DIAR, -1);
            try (Diarizer d = open(diar)) {
                diarResult = d.diarize(audio, progress(listener, DIAR));
            }
            checkInterrupt();
            StageStore.saveDiar(diarResult, diarFile);
            // done 표시와 화자표 초기화는 같은 저장으로 한다. 사이에서 멈추면 다시 돌릴 때 초기화를 건너뛰어 옛 확정 이름이 남는다.
            project = withStage(project, DIAR, diarResult.engine());
        }

        checkInterrupt();
        project = withSpeakers(project, sttResult, diarResult, newDiar);
        if (newDiar || !rowsDone(project, projectFile)) {
            project = llm == null ? withRows(withStage(project, ROWS, ROWS_ENGINE))
                    : structure(project, projectFile, diarResult, llm, dir.resolve(ProjectFiles.ROWS), listener);
        }
        ProjectStore.save(project, projectFile);
        return project;
    }

    /** 5단계: 단위 분할(D-054) → LLM 구조화 → rows.json → project.json 행·무시 목록 투영(D-073). */
    private static Project structure(Project p, Path projectFile, DiarResult diar, Opener<? extends LlmClient> llm,
            Path rowsFile, Listener listener) throws IOException, InterruptedException {
        checkInterrupt();
        // 구조화는 몇 분 걸린다. 여기서 멈춰도 다음 실행이 화자 구분을 다시 하지 않게 화자표·완료 표시를 먼저 저장한다.
        ProjectStore.save(p, projectFile);
        Map<String, String> ids = speakerIds(diar.turns());
        List<Turn> turns = diar.turns().stream().map(t -> new Turn(t.startMs(), t.endMs(), ids.get(t.speaker()))).toList();
        List<StageStore.Unit> units = ClauseSplitter.split(p.sttSegments(), turns);
        listener.progress(ROWS, -1);
        RowsResult rows;
        if (units.isEmpty()) {
            // 발언이 없으면 LLM을 열지 않는다(모델 다운로드·서버 기동 낭비).
            rows = Structurer.empty(EMPTY_ROWS_ENGINE, p.videoType()).rows();
        } else {
            try (LlmClient c = open(llm)) {
                rows = Structurer.run(c, units, p.videoType(), progress(listener, ROWS)).rows();
            }
        }
        checkInterrupt();
        StageStore.saveRows(rows, rowsFile);
        StructuredRows.Projection out = StructuredRows.project(rows.rows(), rows.ignoredUnitIds(), rows.units(), p.speakers());
        Project q = withStage(p, ROWS, rows.engine());
        // 자동 판별 유형은 rows.json에만 둔다. project.json videoType은 사용자가 지정한 값이라 다음 실행에서도 고정한다.
        return new Project(q.version(), q.source(), q.meta(), q.videoType(), q.speakers(), q.sttSegments(), out.rows(),
                out.ignoredSegIds(), q.glossary(), q.stages());
    }

    // LocalServer.start는 시작 중 interrupt를 CancellationException으로 알린다. 취소 계약(D-049)대로 InterruptedException으로 바꾼다.
    private static <E extends Engine> E open(Opener<? extends E> opener) throws IOException, InterruptedException {
        try {
            return opener.open();
        } catch (CancellationException e) {
            Thread.interrupted();
            InterruptedException ie = new InterruptedException("엔진 시작 취소");
            ie.initCause(e);
            throw ie;
        }
    }

    private static void checkInterrupt() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("취소");
        }
    }

    /** 다음 실행에서 이 단계를 돌려야 하는지. UI가 그 단계에 필요한 모델만 받는 데 쓴다. */
    public static boolean pending(Path projectFile, Project p, String stage) {
        Path dir = projectFile.toAbsolutePath().getParent();
        return !done(p, stage, dir.resolve(STT.equals(stage) ? ProjectFiles.STT : ProjectFiles.DIAR));
    }

    /** 다음 실행이 rows를 새로 만드는지(사람이 고친 행이 바뀐다). runStages의 조건과 같다. */
    public static boolean rebuildsRows(Path projectFile, Project p) {
        return pending(projectFile, p, STT) || pending(projectFile, p, DIAR) || !rowsDone(p, projectFile);
    }

    /**
     * 구조화 행은 rows.json이 있어야 완료다. 일부만 무시된 구간은 rows.json에만 남아 project.json으로 되살릴 수 없다(D-073).
     * 파일이 없으면 다시 만들 대상이 되고, 처리 창은 rebuildsRows로 사람이 고친 행을 덮기 전에 묻는다.
     */
    private static boolean rowsDone(Project p, Path projectFile) {
        Project.Stage s = p.stages() == null ? null : p.stages().get(ROWS);
        Path file = s == null || ROWS_ENGINE.equals(s.engine()) ? projectFile
                : projectFile.toAbsolutePath().resolveSibling(ProjectFiles.ROWS);
        return done(p, ROWS, file);
    }

    private static boolean done(Project p, String stage, Path file) {
        Project.Stage s = p.stages() == null ? null : p.stages().get(stage);
        return s != null && s.done() && Files.isRegularFile(file);
    }

    private static DoubleConsumer progress(Listener listener, String stage) {
        return f -> listener.progress(stage, f);
    }

    /** 단계를 다시 돌리기 전에 그 단계와 rows 완료 표시를 지워 저장한다. 지울 것이 없으면 저장하지 않는다. */
    private static Project invalidate(Project p, Path projectFile, String stage) throws IOException {
        Map<String, Project.Stage> stages = p.stages() == null ? Map.of() : p.stages();
        if (!stages.containsKey(stage) && !stages.containsKey(ROWS)) {
            return p;
        }
        Project cleared = withoutStages(p, stage, ROWS);
        ProjectStore.save(cleared, projectFile);
        return cleared;
    }

    private static Project withoutStages(Project p, String... remove) {
        Map<String, Project.Stage> stages = new LinkedHashMap<>(p.stages() == null ? Map.of() : p.stages());
        for (String stage : remove) {
            stages.remove(stage);
        }
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), p.speakers(), p.sttSegments(), p.rows(),
                p.ignoredSegIds(), p.glossary(), Map.copyOf(stages));
    }

    /** 모든 구간을 행으로 쓰므로 무시 목록은 비운다(절대 규칙 1: 구간마다 행 또는 무시 목록에 한 번씩). */
    private static Project withRows(Project p) {
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), p.speakers(), p.sttSegments(),
                SegmentRows.build(p.sttSegments(), p.speakers()), List.of(), p.glossary(), p.stages());
    }

    private static Project withStage(Project p, String stage, String engine) {
        Map<String, Project.Stage> stages = new LinkedHashMap<>(p.stages() == null ? Map.of() : p.stages());
        stages.put(stage, new Project.Stage(engine, true));
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), p.speakers(), p.sttSegments(), p.rows(),
                p.ignoredSegIds(), p.glossary(), Map.copyOf(stages));
    }

    /**
     * 구간마다 겹치는 시간이 가장 긴 화자를 붙인다(설계 4장 3단계, D-064).
     * 단어 시간({@code words})이 있으면 단어 구간 합집합과 화자 턴의 겹침을 먼저 보고, 단어가 없거나 겹침이 0이면
     * 전체 구간 {@code [startMs, endMs]}로 폴백한다. 같은 화자의 턴끼리 겹친 시간은 한 번만 센다.
     * 겹침이 없으면 null(모르면 비움, 절대 규칙 2). 화자 라벨은 처음 나온 순서로 S1, S2 …가 된다.
     * 화자 구분을 새로 돌렸으면(newDiar) 라벨과 사람이 같다는 보장이 없어 그 id들의 화자표 항목을 미확인으로 다시 만든다.
     * 같은 diar.json이면 매핑도 같으므로 사람이 고친 이름을 그대로 둔다.
     */
    static Project withSpeakers(Project p, SttResult stt, DiarResult diar, boolean newDiar) {
        List<Turn> turns = new ArrayList<>(diar.turns());
        turns.sort(Comparator.comparingLong(Turn::startMs));
        Map<String, String> ids = speakerIds(turns);

        List<SttSegment> segments = new ArrayList<>();
        for (SttSegment s : stt.segments()) {
            String best = bestSpeaker(s, turns, ids);
            segments.add(s.withSpeaker(best == null ? null : ids.get(best)));
        }

        Map<String, Speaker> speakers = new LinkedHashMap<>();
        for (Speaker s : p.speakers()) {
            speakers.put(s.id(), s);
        }
        int n = 0;
        for (String id : ids.values()) {
            Speaker unknown = new Speaker(id, "화자 미확인 " + ++n, false);
            if (newDiar) {
                speakers.put(id, unknown);
            } else {
                speakers.putIfAbsent(id, unknown);
            }
        }
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), List.copyOf(speakers.values()),
                List.copyOf(segments), p.rows(), p.ignoredSegIds(), p.glossary(), p.stages());
    }

    /** 화자 라벨 → 화자표 id. 시작 시각 순으로 처음 나온 라벨부터 S1, S2 …. */
    private static Map<String, String> speakerIds(List<Turn> turns) {
        List<Turn> sorted = new ArrayList<>(turns);
        sorted.sort(Comparator.comparingLong(Turn::startMs));
        Map<String, String> ids = new LinkedHashMap<>();
        for (Turn t : sorted) {
            ids.computeIfAbsent(t.speaker(), k -> "S" + (ids.size() + 1));
        }
        return ids;
    }

    private static String bestSpeaker(SttSegment s, List<Turn> turns, Map<String, String> ids) {
        List<long[]> wordSpans = mergedWordSpans(s);
        if (!wordSpans.isEmpty()) {
            String best = bestSpeakerForSpans(wordSpans, turns, ids);
            if (best != null) {
                return best;
            }
        }
        return bestSpeakerForSpans(List.of(new long[] { s.startMs(), s.endMs() }), turns, ids);
    }

    private static String bestSpeakerForSpans(List<long[]> spans, List<Turn> turns, Map<String, String> ids) {
        String best = null;
        long bestMs = 0;
        for (String speaker : ids.keySet()) {
            long ms = overlapSpans(spans, turns, speaker);
            if (ms > bestMs) {
                best = speaker;
                bestMs = ms;
            }
        }
        return best;
    }

    /** 단어 시각을 부모 STT 구간 안으로 자른 뒤 정렬·병합해 겹치지 않는 [startMs, endMs] 목록으로 만든다. */
    private static List<long[]> mergedWordSpans(SttSegment segment) {
        List<Project.Word> words = segment.words();
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<long[]> raw = new ArrayList<>(words.size());
        for (Project.Word w : words) {
            if (w == null) {
                continue;
            }
            long start = Math.max(segment.startMs(), w.startMs());
            long end = Math.min(segment.endMs(), w.endMs());
            if (end > start) {
                raw.add(new long[] { start, end });
            }
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        raw.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>(raw.size());
        long[] cur = raw.get(0);
        for (int i = 1; i < raw.size(); i++) {
            long[] next = raw.get(i);
            if (next[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        return merged;
    }

    /** turns와 spans 모두 startMs 순(비겹침 spans). 각 span 안으로 자른 그 화자 턴들의 합집합 길이 합. */
    private static long overlapSpans(List<long[]> spans, List<Turn> turns, String speaker) {
        long total = 0;
        for (long[] span : spans) {
            long covered = span[0];
            for (Turn t : turns) {
                if (!t.speaker().equals(speaker)) {
                    continue;
                }
                if (t.startMs() >= span[1]) {
                    break;
                }
                long from = Math.max(covered, t.startMs());
                long to = Math.min(span[1], t.endMs());
                if (to > from) {
                    total += to - from;
                    covered = to;
                }
            }
        }
        return total;
    }
}

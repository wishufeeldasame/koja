package koja.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import koja.core.Project;
import koja.core.Project.Speaker;
import koja.core.Project.SttSegment;
import koja.core.ProjectFiles;
import koja.core.ProjectStore;
import koja.core.StageStore;
import koja.core.StageStore.DiarResult;
import koja.core.StageStore.SttResult;
import koja.core.StageStore.Turn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineTest {
    @TempDir
    Path tmp;

    /** 엔진이 열리고 닫힌 순서. 동시에 두 엔진이 열려 있으면 안 된다(절대 규칙 5). */
    final List<String> log = new ArrayList<>();

    static final SttResult STT = new SttResult(1, "fake-stt", List.of(
            new SttSegment(1, 0, 1_000, "가", "ko", null),
            new SttSegment(2, 1_000, 3_000, "나", "ko", null),
            new SttSegment(3, 9_000, 9_500, "다", "ko", null)));

    // 라벨 "7"이 먼저 나오므로 S1, "3"은 S2. 구간 2는 "3"과 1.5초, "7"과 0.5초 겹친다. 구간 3은 아무 턴과도 안 겹친다.
    static final DiarResult DIAR = new DiarResult(1, "fake-diar", List.of(
            new Turn(1_500, 4_000, "3"), new Turn(0, 1_500, "7")));

    class FakeStt implements Transcriber {
        FakeStt() { log.add("open stt"); }
        public String name() { return "fake-stt"; }
        public SttResult transcribe(Path audio, DoubleConsumer progress) throws InterruptedException { progress.accept(1); return STT; }
        public void close() { log.add("close stt"); }
    }

    class FakeDiar implements Diarizer {
        FakeDiar() { log.add("open diar"); }
        public String name() { return "fake-diar"; }
        public DiarResult diarize(Path audio, DoubleConsumer progress) throws IOException { return DIAR; }
        public void close() { log.add("close diar"); }
    }

    Path newProject(List<Speaker> speakers) throws IOException {
        Path file = tmp.resolve("프로젝트 1").resolve("project.json");
        ProjectStore.save(new Project(Project.CURRENT_VERSION, new Project.Source("a.mp4", 10_000), null, null,
                speakers, List.of(), List.of(), List.of(), null, Map.of("import", new Project.Stage("ffmpeg", true))), file);
        return file;
    }

    @Test
    void runsStagesOneAtATimeAndAssignsSpeakersByOverlap() throws Exception {
        Path file = newProject(List.of());
        List<String> progress = new ArrayList<>();

        Project p = Pipeline.run(file, FakeStt::new, FakeDiar::new, (stage, f) -> progress.add(stage + " " + f));

        assertEquals(List.of("open stt", "close stt", "open diar", "close diar"), log);
        assertEquals(List.of("stt 1.0"), progress);
        assertEquals(List.of("S1", "S2"), p.sttSegments().stream().limit(2).map(SttSegment::speakerId).toList());
        assertEquals(null, p.sttSegments().get(2).speakerId());
        assertEquals(List.of(new Speaker("S1", "화자 미확인 1", false), new Speaker("S2", "화자 미확인 2", false)),
                p.speakers());
        assertTrue(p.stages().get(Pipeline.STT).done() && p.stages().get(Pipeline.DIAR).done());
        assertEquals("fake-stt", p.stages().get(Pipeline.STT).engine());
        assertEquals(p, ProjectStore.load(file));
        assertEquals(STT, StageStore.loadStt(file.resolveSibling(ProjectFiles.STT)));
    }

    @Test
    void skipsDoneStagesAndKeepsEditedSpeakerNames() throws Exception {
        Path file = newProject(List.of());
        Project first = Pipeline.run(file, FakeStt::new, FakeDiar::new, (s, f) -> {});
        ProjectStore.save(withSpeakers(first, List.of(new Speaker("S1", "선수A", true),
                new Speaker("S2", "화자 미확인 2", false))), file);
        log.clear();

        Project p = Pipeline.run(file, () -> { throw new AssertionError("STT를 다시 돌리면 안 된다"); },
                () -> { throw new AssertionError("화자 구분을 다시 돌리면 안 된다"); }, (s, f) -> {});

        assertEquals(List.of(), log);
        assertEquals(new Speaker("S1", "선수A", true), p.speakers().get(0));
    }

    /** 화자 구분을 새로 돌리면 S1이 다른 사람일 수 있다. 확정 이름을 남기면 남의 발언에 붙는다. */
    @Test
    void newDiarizationResetsSpeakerNamesForItsIds() throws Exception {
        Path file = newProject(List.of(new Speaker("S1", "선수A", true), new Speaker("S9", "선수B", true)));

        Project p = Pipeline.run(file, FakeStt::new, FakeDiar::new, (s, f) -> {});

        assertEquals(List.of(new Speaker("S1", "화자 미확인 1", false), new Speaker("S9", "선수B", true),
                new Speaker("S2", "화자 미확인 2", false)), p.speakers());
    }

    @Test
    void countsOverlappingTurnsOfOneSpeakerOnce() {
        // A: 0–3초와 1–3.5초 → 3.5초, B: 5–10초 → 5초. 겹침을 두 번 세면 A(5.5초)가 된다.
        SttResult stt = new SttResult(1, "x", List.of(new SttSegment(1, 0, 10_000, "가", "ko", null)));
        DiarResult diar = new DiarResult(1, "x", List.of(
                new Turn(0, 3_000, "A"), new Turn(1_000, 3_500, "A"), new Turn(5_000, 10_000, "B")));
        Project p = new Project(Project.CURRENT_VERSION, new Project.Source("a.mp4", 0), null, null,
                List.of(), List.of(), List.of(), List.of(), null, Map.of());

        assertEquals("S2", Pipeline.withSpeakers(p, stt, diar, true).sttSegments().get(0).speakerId());
    }

    @Test
    void prefersWordSpansOverFullSegmentWhenWordsExist() {
        // 구간 전체(0~10초)에서는 A(0~6초)가 길지만, 실제 단어(7~9.5초)는 B(6~10초) 구간에 있다.
        List<Project.Word> words = List.of(
                new Project.Word(7_000, 8_200, "안녕"),
                new Project.Word(8_000, 9_500, "하세요"));
        SttResult stt = new SttResult(1, "x", List.of(
                new SttSegment(1, 0, 10_000, "안녕하세요", "ko", null, words, null),
                // 단어가 턴과 안 겹치면 전체 구간으로 폴백한다.
                new SttSegment(2, 0, 10_000, "폴백", "ko", null, List.of(new Project.Word(11_000, 12_000, "폴백")), null)));
        DiarResult diar = new DiarResult(1, "x", List.of(
                new Turn(0, 6_000, "A"), new Turn(6_000, 10_000, "B")));
        Project p = new Project(Project.CURRENT_VERSION, new Project.Source("a.mp4", 0), null, null,
                List.of(), List.of(), List.of(), List.of(), null, Map.of());

        List<SttSegment> assigned = Pipeline.withSpeakers(p, stt, diar, true).sttSegments();
        assertEquals("S2", assigned.get(0).speakerId());
        assertEquals("S1", assigned.get(1).speakerId());
    }

    @Test
    void clampsWordSpansToParentSegmentBounds() {
        // 구간은 0~1000ms인데 마지막 단어가 950~1200ms로 구간 밖까지 뻗어도 부모 구간 안(950~1000ms, A)만 센다.
        SttResult stt = new SttResult(1, "x", List.of(
                new SttSegment(1, 0, 1_000, "끝", "ko", null,
                        List.of(new Project.Word(950, 1_200, "끝")), null)));
        DiarResult diar = new DiarResult(1, "x", List.of(
                new Turn(0, 1_000, "A"), new Turn(1_000, 2_000, "B")));
        Project p = new Project(Project.CURRENT_VERSION, new Project.Source("a.mp4", 0), null, null,
                List.of(), List.of(), List.of(), List.of(), null, Map.of());

        assertEquals("S1", Pipeline.withSpeakers(p, stt, diar, true).sttSegments().get(0).speakerId());
    }

    @Test
    void interruptBetweenStagesCancelsBeforeSaving() throws Exception {
        Path file = newProject(List.of());
        Pipeline.Opener<Transcriber> interrupting = () -> new FakeStt() {
            @Override
            public SttResult transcribe(Path audio, DoubleConsumer progress) {
                Thread.currentThread().interrupt();
                return STT;
            }
        };

        assertThrows(InterruptedException.class, () -> Pipeline.run(file, interrupting, FakeDiar::new, (s, f) -> {}));

        assertFalse(Thread.interrupted(), "interrupt 상태는 예외로 바뀌어야 한다");
        assertFalse(Files.exists(file.resolveSibling(ProjectFiles.STT)));
        assertEquals(List.of("open stt", "close stt"), log);
    }

    @Test
    void cancelWhileOpeningIsInterrupt() throws Exception {
        Path file = newProject(List.of());
        Pipeline.Opener<Transcriber> cancelled = () -> {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("시작 취소");
        };

        assertThrows(InterruptedException.class, () -> Pipeline.run(file, cancelled, FakeDiar::new, (s, f) -> {}));

        assertFalse(Thread.interrupted());
        assertFalse(Files.exists(file.resolveSibling(ProjectFiles.STT)));
    }

    @Test
    void rejectsNestedRunFromProgressCallback() throws Exception {
        Path file = newProject(List.of());

        assertThrows(IllegalStateException.class, () -> Pipeline.run(file, FakeStt::new, FakeDiar::new,
                (s, f) -> {
                    try {
                        Pipeline.run(file, FakeStt::new, FakeDiar::new, (s2, f2) -> {});
                    } catch (IOException | InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }));
        assertEquals(List.of("open stt", "close stt"), log);
    }

    /** 화자 구분 done은 화자표 초기화와 같이 저장된다. 그 전에 멈추면 다음 실행이 화자 구분부터 다시 한다. */
    @Test
    void cancelledDiarizationIsNotMarkedDone() throws Exception {
        Path file = newProject(List.of(new Speaker("S1", "선수A", true)));
        Pipeline.Opener<Diarizer> interrupting = () -> new FakeDiar() {
            @Override
            public DiarResult diarize(Path audio, DoubleConsumer progress) {
                Thread.currentThread().interrupt();
                return DIAR;
            }
        };

        assertThrows(InterruptedException.class, () -> Pipeline.run(file, FakeStt::new, interrupting, (s, f) -> {}));

        Project saved = ProjectStore.load(file);
        assertTrue(saved.stages().get(Pipeline.STT).done());
        assertFalse(saved.stages().containsKey(Pipeline.DIAR));
        assertEquals(List.of(new Speaker("S1", "선수A", true)), saved.speakers());
    }

    @Test
    void buildsOneRowPerSegmentAndMarksUnknownSpeakers() throws Exception {
        Path file = newProject(List.of());

        Project p = Pipeline.run(file, FakeStt::new, FakeDiar::new, (s, f) -> {});

        assertEquals(List.of(List.of(1), List.of(2), List.of(3)), p.rows().stream().map(Project.Row::segIds).toList());
        assertEquals(List.of(0L, 1_000L, 9_000L), p.rows().stream().map(Project.Row::startMs).toList());
        assertEquals(List.of("가", "나", "다"), p.rows().stream().map(Project.Row::ko).toList());
        assertEquals(java.util.Arrays.asList("S1", "S2", null), p.rows().stream().map(Project.Row::speakerId).toList());
        // 화자 구분 직후 화자는 모두 미확인이다.
        assertTrue(p.rows().stream().allMatch(r -> r.flags().equals(List.of(Project.Flag.SPEAKER_UNSURE))));
        assertEquals(List.of(), p.ignoredSegIds());
        assertTrue(p.stages().get(Pipeline.ROWS).done());
    }

    @Test
    void keepsEditedRowsWhenNothingReran() throws Exception {
        Path file = newProject(List.of());
        Project first = Pipeline.run(file, FakeStt::new, FakeDiar::new, (s, f) -> {});
        Project.Row r = first.rows().getFirst();
        List<Project.Row> edited = new ArrayList<>(first.rows());
        edited.set(0, new Project.Row(r.id(), r.kind(), r.startMs(), r.speakerId(), "고친 말", "直した", r.segIds(), List.of()));
        ProjectStore.save(withRows(first, edited), file);

        Project p = Pipeline.run(file, () -> { throw new AssertionError("STT"); },
                () -> { throw new AssertionError("화자 구분"); }, (s, f) -> {});

        assertEquals(edited, p.rows());
    }

    /** 새 STT를 저장한 뒤 멈추면 옛 행이 새 구간 id와 어긋난다. 다음 실행이 STT는 건너뛰고 행은 다시 만들어야 한다. */
    @Test
    void cancelAfterNewSttForcesRowRebuildOnResume() throws Exception {
        Path file = newProject(List.of());
        Project first = Pipeline.run(file, FakeStt::new, FakeDiar::new, (s, f) -> {});
        ProjectStore.save(withRows(first, List.of()), file);
        Files.delete(file.resolveSibling(ProjectFiles.STT));
        Files.delete(file.resolveSibling(ProjectFiles.DIAR));
        Pipeline.Opener<Diarizer> interrupting = () -> new FakeDiar() {
            @Override
            public DiarResult diarize(Path audio, DoubleConsumer progress) {
                Thread.currentThread().interrupt();
                return DIAR;
            }
        };

        assertThrows(InterruptedException.class, () -> Pipeline.run(file, FakeStt::new, interrupting, (s, f) -> {}));
        assertFalse(ProjectStore.load(file).stages().containsKey(Pipeline.ROWS));
        log.clear();

        Project p = Pipeline.run(file, () -> { throw new AssertionError("STT를 다시 돌리면 안 된다"); },
                FakeDiar::new, (s, f) -> {});

        assertEquals(List.of("open diar", "close diar"), log);
        assertEquals(3, p.rows().size());
        assertTrue(p.stages().get(Pipeline.ROWS).done());
    }

    /** 화자 구분이 실패해도 STT 결과는 남고, 다시 돌리면 STT를 건너뛴다. */
    @Test
    void failedDiarizationKeepsSttForResume() throws Exception {
        Path file = newProject(List.of());
        Pipeline.Opener<Diarizer> failing = () -> new FakeDiar() {
            @Override
            public DiarResult diarize(Path audio, DoubleConsumer progress) throws IOException {
                throw new IOException("실패");
            }
        };

        assertThrows(IOException.class, () -> Pipeline.run(file, FakeStt::new, failing, (s, f) -> {}));
        assertEquals(List.of("open stt", "close stt", "open diar", "close diar"), log);
        assertEquals(STT, StageStore.loadStt(file.resolveSibling(ProjectFiles.STT)));
        log.clear();

        Project p = Pipeline.run(file, () -> { throw new AssertionError("STT를 다시 돌리면 안 된다"); },
                FakeDiar::new, (s, f) -> {});

        assertEquals(List.of("open diar", "close diar"), log);
        assertEquals(3, p.rows().size());
    }

    static Project withRows(Project p, List<Project.Row> rows) {
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), p.speakers(), p.sttSegments(), rows,
                p.ignoredSegIds(), p.glossary(), p.stages());
    }

    static Project withSpeakers(Project p, List<Speaker> speakers) {
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), speakers, p.sttSegments(), p.rows(),
                p.ignoredSegIds(), p.glossary(), p.stages());
    }

    @Test
    void failedStageClosesEngineAndLeavesNoResult() throws Exception {
        Path file = newProject(List.of());
        Pipeline.Opener<Transcriber> failing = () -> new FakeStt() {
            @Override
            public SttResult transcribe(Path audio, DoubleConsumer progress) throws InterruptedException {
                throw new InterruptedException("취소");
            }
        };

        assertThrows(InterruptedException.class, () -> Pipeline.run(file, failing, FakeDiar::new, (s, f) -> {}));

        assertEquals(List.of("open stt", "close stt"), log);
        assertFalse(Files.exists(file.resolveSibling(ProjectFiles.STT)));
        assertFalse(ProjectStore.load(file).stages().containsKey(Pipeline.STT));
    }
}

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
        public DiarResult diarize(Path audio, DoubleConsumer progress) { return DIAR; }
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
        Path file = newProject(List.of(new Speaker("S1", "선수A", true)));
        Pipeline.run(file, FakeStt::new, FakeDiar::new, (s, f) -> {});
        log.clear();

        Project p = Pipeline.run(file, () -> { throw new AssertionError("STT를 다시 돌리면 안 된다"); },
                () -> { throw new AssertionError("화자 구분을 다시 돌리면 안 된다"); }, (s, f) -> {});

        assertEquals(List.of(), log);
        assertEquals(List.of(new Speaker("S1", "선수A", true), new Speaker("S2", "화자 미확인 2", false)), p.speakers());
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

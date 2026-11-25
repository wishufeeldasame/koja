package koja.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

/**
 * 단계를 순서대로 하나씩 돌린다: STT → stt.json, 화자 구분 → diar.json, 그 뒤 화자를 구간에 붙여 project.json에 저장.
 * stages.<단계>.done이고 결과 파일이 있으면 그 단계는 건너뛴다(중간에 멈춘 뒤 다시 돌리기). 행 만들기는 아직 없다(M2 W2-4).
 */
public final class Pipeline {
    public static final String STT = "stt";
    public static final String DIAR = "diar";

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

    private Pipeline() {}

    public static Project run(Path projectFile, Opener<? extends Transcriber> stt, Opener<? extends Diarizer> diar,
            Listener listener) throws IOException, InterruptedException {
        Path dir = projectFile.toAbsolutePath().getParent();
        Path audio = dir.resolve(ProjectFiles.AUDIO);
        Path sttFile = dir.resolve(ProjectFiles.STT);
        Path diarFile = dir.resolve(ProjectFiles.DIAR);
        Project project = ProjectStore.load(projectFile);

        SttResult sttResult;
        if (done(project, STT, sttFile)) {
            sttResult = StageStore.loadStt(sttFile);
        } else {
            try (Transcriber t = stt.open()) {
                sttResult = t.transcribe(audio, progress(listener, STT));
            }
            StageStore.saveStt(sttResult, sttFile);
            project = withStage(project, STT, sttResult.engine());
            ProjectStore.save(project, projectFile);
        }

        DiarResult diarResult;
        if (done(project, DIAR, diarFile)) {
            diarResult = StageStore.loadDiar(diarFile);
        } else {
            try (Diarizer d = diar.open()) {
                diarResult = d.diarize(audio, progress(listener, DIAR));
            }
            StageStore.saveDiar(diarResult, diarFile);
            project = withStage(project, DIAR, diarResult.engine());
            ProjectStore.save(project, projectFile);
        }

        project = withSpeakers(project, sttResult, diarResult);
        ProjectStore.save(project, projectFile);
        return project;
    }

    private static boolean done(Project p, String stage, Path file) {
        Project.Stage s = p.stages() == null ? null : p.stages().get(stage);
        return s != null && s.done() && Files.isRegularFile(file);
    }

    private static DoubleConsumer progress(Listener listener, String stage) {
        return f -> listener.progress(stage, f);
    }

    private static Project withStage(Project p, String stage, String engine) {
        Map<String, Project.Stage> stages = new LinkedHashMap<>(p.stages() == null ? Map.of() : p.stages());
        stages.put(stage, new Project.Stage(engine, true));
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), p.speakers(), p.sttSegments(), p.rows(),
                p.ignoredSegIds(), p.glossary(), Map.copyOf(stages));
    }

    /**
     * 구간마다 겹치는 시간이 가장 긴 화자를 붙인다(설계 4장 3단계). 겹침이 없으면 null(모르면 비움, 절대 규칙 2).
     * 화자 라벨은 처음 나온 순서로 S1, S2 …가 된다. 이미 있는 화자표 항목(사람이 고친 이름)은 그대로 둔다.
     */
    static Project withSpeakers(Project p, SttResult stt, DiarResult diar) {
        List<Turn> turns = new ArrayList<>(diar.turns());
        turns.sort(Comparator.comparingLong(Turn::startMs));
        Map<String, String> ids = new LinkedHashMap<>();
        for (Turn t : turns) {
            ids.computeIfAbsent(t.speaker(), k -> "S" + (ids.size() + 1));
        }

        List<SttSegment> segments = new ArrayList<>();
        for (SttSegment s : stt.segments()) {
            Map<String, Long> overlap = new LinkedHashMap<>();
            for (Turn t : turns) {
                long ms = Math.min(s.endMs(), t.endMs()) - Math.max(s.startMs(), t.startMs());
                if (ms > 0) {
                    overlap.merge(t.speaker(), ms, Long::sum);
                }
            }
            String best = null;
            long bestMs = 0;
            for (var e : overlap.entrySet()) {
                if (e.getValue() > bestMs) {
                    best = e.getKey();
                    bestMs = e.getValue();
                }
            }
            segments.add(s.withSpeaker(best == null ? null : ids.get(best)));
        }

        Map<String, Speaker> speakers = new LinkedHashMap<>();
        for (Speaker s : p.speakers()) {
            speakers.put(s.id(), s);
        }
        int n = 0;
        for (String id : ids.values()) {
            speakers.putIfAbsent(id, new Speaker(id, "화자 미확인 " + ++n, false));
        }
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), List.copyOf(speakers.values()),
                List.copyOf(segments), p.rows(), p.ignoredSegIds(), p.glossary(), p.stages());
    }
}

package koja.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import koja.core.Project;
import koja.core.Project.Flag;
import koja.core.Project.Row;
import koja.core.Project.RowKind;
import koja.core.Project.SttSegment;

/** 파이프라인(M2~)이 생기기 전 검수 화면을 확인하기 위한 가짜 프로젝트. */
final class DummyProject {
    private static final int SEGMENTS = 10;
    private static final long STEP_MS = 6_000;

    private DummyProject() {}

    static Project create(Path video) {
        List<SttSegment> segments = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("r0", RowKind.QUESTION_JA, null, null, "더미 질문", "ダミー質問", List.of(), List.of()));
        for (int i = 1; i <= SEGMENTS; i++) {
            long start = (i - 1) * STEP_MS;
            String speaker = i % 3 == 0 ? "S2" : "S1";
            segments.add(new SttSegment(i, start, start + STEP_MS - 1_000, "더미 대사 " + i, "ko", speaker));
            List<Flag> flags = speaker.equals("S2") ? List.of(Flag.SPEAKER_UNSURE)
                    : i % 4 == 0 ? List.of(Flag.TEXT_UNSURE) : List.of();
            RowKind kind = i == 1 ? RowKind.QUESTION_KO : RowKind.LINE;
            rows.add(new Row("r" + i, kind, start, speaker, "더미 대사 " + i, "ダミー台詞 " + i, List.of(i), flags));
        }
        return new Project(
                Project.CURRENT_VERSION,
                new Project.Source(video.toAbsolutePath().toString(), SEGMENTS * STEP_MS),
                new Project.Meta("더미 프로젝트", "", List.of("선수A")),
                Project.VideoType.INTERVIEW_JA,
                List.of(new Project.Speaker("S1", "선수A", true),
                        new Project.Speaker("S2", "화자 미확인 1", false)),
                segments,
                rows,
                List.of(),
                "valorant",
                Map.of("stt", new Project.Stage("dummy", true)));
    }
}

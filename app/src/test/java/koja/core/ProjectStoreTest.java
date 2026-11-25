package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import koja.core.Project.Flag;
import koja.core.Project.Row;
import koja.core.Project.RowKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStoreTest {
    @TempDir
    Path tmp;

    static Project sample() {
        return new Project(
                Project.CURRENT_VERSION,
                new Project.Source("영상 폴더/인터뷰 1.mp4", 3_600_000),
                new Project.Meta("제목", "주제", List.of("선수A")),
                Project.VideoType.INTERVIEW_JA,
                List.of(new Project.Speaker("S1", "선수A", true),
                        new Project.Speaker("S2", "화자 미확인 1", false)),
                List.of(new Project.SttSegment(1, 398_000, 401_200, "안녕하세요", "ko", "S1"),
                        new Project.SttSegment(2, 402_000, 405_000, "こんにちは", "ja", "S2",
                                List.of(new Project.Word(402_100, 402_900, "こんにちは")),
                                new Project.KoCandidate("안녕하세요", List.of(new Project.Word(402_100, 402_800, "안녕하세요"))))),
                List.of(new Row("r1", RowKind.QUESTION_JA, null, null, "질문", "質問", List.of(), List.of()),
                        new Row("r2", RowKind.LINE, 398_000L, "S1", "안녕하세요", "こんにちは",
                                List.of(1), List.of(Flag.SPEAKER_UNSURE, Flag.TEXT_UNSURE))),
                List.of(2),
                "valorant",
                Map.of("stt", new Project.Stage("dummy", true)));
    }

    @Test
    void roundTripInKoreanPathWithSpaces() throws IOException {
        Path file = tmp.resolve("한글 폴더").resolve("project.json");
        Project original = sample();

        ProjectStore.save(original, file);

        assertEquals(original, ProjectStore.load(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList(), "임시 파일이 남으면 안 된다");
        }
    }

    @Test
    void saveOverwritesExistingFile() throws IOException {
        Path file = tmp.resolve("project.json");
        Files.writeString(file, "이전 내용");

        ProjectStore.save(sample(), file);

        assertEquals(sample(), ProjectStore.load(file));
    }

    @Test
    void writesUtf8() throws IOException {
        Path file = tmp.resolve("project.json");
        ProjectStore.save(sample(), file);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("화자 미확인 1"), json);
    }

    /** 단어 시간·한국어 후보·무시 목록이 생기기 전에 저장한 파일(D-048). */
    @Test
    void loadsFileWithoutOptionalFields() throws IOException {
        Path file = tmp.resolve("project.json");
        Files.writeString(file, """
                {"version": 1, "source": {"path": "a.mp4", "durationMs": 1000}, "speakers": [],
                 "sttSegments": [{"id": 1, "startMs": 0, "endMs": 500, "text": "가", "lang": "ko", "speakerId": null}],
                 "rows": [], "stages": {"import": {"engine": "ffmpeg", "done": true}}}
                """, StandardCharsets.UTF_8);

        Project p = ProjectStore.load(file);

        assertEquals(List.of(), p.ignoredSegIds());
        assertEquals(List.of(), p.sttSegments().get(0).words());
        assertEquals(null, p.sttSegments().get(0).ko());
        ProjectStore.save(p, file);
        assertEquals(p, ProjectStore.load(file));
    }

    @Test
    void rejectsOtherVersion() throws IOException {
        Path file = tmp.resolve("project.json");
        Files.writeString(file, "{\"version\": 2}");

        assertThrows(IOException.class, () -> ProjectStore.load(file));
    }

    @Test
    void rejectsMissingRequiredFields() throws IOException {
        Path file = tmp.resolve("project.json");
        Files.writeString(file, "{\"version\": 1}");

        assertThrows(IOException.class, () -> ProjectStore.load(file));
    }

    @Test
    void rejectsNullJsonBlankSourcePathAndFractionalVersion() throws IOException {
        Path file = tmp.resolve("project.json");
        for (String json : List.of(
                "null",
                "{\"version\": 1, \"source\": {}, \"speakers\": [], \"sttSegments\": [], \"rows\": []}",
                "{\"version\": 1.9, \"source\": {\"path\": \"a\"}, \"speakers\": [], \"sttSegments\": [], \"rows\": []}")) {
            Files.writeString(file, json);
            assertThrows(IOException.class, () -> ProjectStore.load(file), json);
        }
    }

    @Test
    void saveRejectsInvalidProjectAndKeepsExistingFile() throws IOException {
        Path file = tmp.resolve("project.json");
        ProjectStore.save(sample(), file);
        Project broken = new Project(Project.CURRENT_VERSION, sample().source(), null, null,
                List.of(), List.of(), null, List.of(), null, Map.of());

        assertThrows(IOException.class, () -> ProjectStore.save(broken, file));
        assertEquals(sample(), ProjectStore.load(file));
    }

    @Test
    void rejectsBrokenJson() throws IOException {
        Path file = tmp.resolve("project.json");
        Files.writeString(file, "{\"version\": 1,");

        assertThrows(IOException.class, () -> ProjectStore.load(file));
    }
}

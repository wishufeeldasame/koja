package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import koja.core.Project.KoCandidate;
import koja.core.Project.SttSegment;
import koja.core.Project.Word;
import koja.core.StageStore.DiarResult;
import koja.core.StageStore.SttResult;
import koja.core.StageStore.Turn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageStoreTest {
    @TempDir
    Path tmp;

    static SttResult stt() {
        return new SttResult(StageStore.CURRENT_VERSION, "whisper-test", List.of(
                new SttSegment(1, 0, 2_000, "가나", "ko", null, List.of(new Word(100, 900, "가나")), null),
                new SttSegment(2, 2_500, 6_000, "質問です", "ja", null, List.of(new Word(2_600, 3_400, "質問です")),
                        new KoCandidate("질문입니다", List.of(new Word(2_600, 3_300, "질문입니다"))))));
    }

    @Test
    void sttRoundTrip() throws IOException {
        Path file = tmp.resolve("한글 폴더").resolve(ProjectFiles.STT);
        StageStore.saveStt(stt(), file);
        assertEquals(stt(), StageStore.loadStt(file));
    }

    @Test
    void diarRoundTrip() throws IOException {
        Path file = tmp.resolve(ProjectFiles.DIAR);
        DiarResult diar = new DiarResult(StageStore.CURRENT_VERSION, "sherpa-test",
                List.of(new Turn(0, 2_000, "0"), new Turn(2_000, 6_000, "1")));
        StageStore.saveDiar(diar, file);
        assertEquals(diar, StageStore.loadDiar(file));
    }

    @Test
    void rejectsBadSegmentsAndKeepsExistingFile() throws IOException {
        Path file = tmp.resolve(ProjectFiles.STT);
        StageStore.saveStt(stt(), file);
        for (List<SttSegment> bad : List.of(
                List.of(new SttSegment(2, 0, 1, "a", "ko", null), new SttSegment(1, 2, 3, "b", "ko", null)),
                List.of(new SttSegment(1, 0, 1, "a", "ko", null), new SttSegment(1, 2, 3, "b", "ko", null)),
                List.of(new SttSegment(0, 0, 1, "a", "ko", null)),
                List.of(new SttSegment(1, 5, 1, "a", "ko", null)),
                List.of(new SttSegment(1, 0, 1, null, "ko", null)),
                List.of(new SttSegment(1, 0, 1, "a", "ko", "S1")),
                List.of(new SttSegment(1, 0, 9, "a", "ja", null, List.of(new Word(-1, 2, "a")), null)),
                List.of(new SttSegment(1, 0, 9, "a", "ja", null, List.of(),
                        new KoCandidate("가", List.of(new Word(5, 2, "가"))))))) {
            SttResult r = new SttResult(StageStore.CURRENT_VERSION, "x", bad);
            assertThrows(IOException.class, () -> StageStore.saveStt(r, file), bad.toString());
        }
        assertEquals(stt(), StageStore.loadStt(file));
    }

    @Test
    void rejectsOtherVersionAndBadTurns() throws IOException {
        Path file = tmp.resolve(ProjectFiles.DIAR);
        for (String json : List.of("{\"version\": 2, \"turns\": []}", "{\"version\": 1}",
                "{\"version\": 1, \"turns\": [{\"startMs\": 0, \"endMs\": 1, \"speaker\": \"\"}]}",
                // startMs가 빠지면 0으로 읽혀 범위 검사를 통과한다.
                "{\"version\": 1, \"engine\": \"x\", \"turns\": [{\"endMs\": 1000, \"speaker\": \"0\"}]}",
                "{\"version\": 1, \"engine\": \"x\", \"turns\": [{\"startMs\": null, \"endMs\": 1000, \"speaker\": \"0\"}]}")) {
            Files.writeString(file, json);
            assertThrows(IOException.class, () -> StageStore.loadDiar(file), json);
        }
    }
}

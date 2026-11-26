package koja.engine.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import koja.core.Project.SttSegment;
import koja.core.Project.Word;
import koja.core.StageStore;
import koja.core.StageStore.SttResult;
import koja.engine.whisper.WhisperTranscriber.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperTranscriberTest {
    static String json(String lang, String text, double start, double end) {
        return """
                {"task":"transcribe","language":"%s","duration":1.0,"text":" %s","segments":[
                  {"id":0,"text":" %s","start":0.0,"end":1.0,"words":[
                    {"word":" ","start":0.0,"end":0.0,"probability":0.1},
                    {"word":" %s","start":%s,"end":%s,"probability":0.9}]}]}
                """.formatted(lang, text, text, text, start, end);
    }

    /** 16kHz 모노 16비트 WAV. ffmpeg처럼 data 앞에 LIST 청크를 넣는다. */
    static Path wav(Path dir, long ms) throws IOException {
        int len = (int) (ms * 32);
        byte[] list = {'I', 'N', 'F', 'O', 0};
        ByteBuffer b = ByteBuffer.allocate(12 + 24 + 8 + list.length + 1 + 8 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity() - 8).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("LIST".getBytes()).putInt(list.length).put(list).put((byte) 0)
                .put("data".getBytes()).putInt(len);
        Path f = dir.resolve("오디오 폴더").resolve("audio.wav");
        Files.createDirectories(f.getParent());
        Files.write(f, b.array());
        return f;
    }

    @Test
    void secondPassOnlyForNonKoreanAndDropsEmpty(@TempDir Path dir) throws Exception {
        Path audio = wav(dir, 10_000);
        List<String> calls = new ArrayList<>();
        WhisperTranscriber.Inference fake = (clip, lang) -> {
            int ms = (clip.length - 44) / 32;
            calls.add(ms + ":" + lang);
            return switch (ms) {
                case 1000 -> json("korean", "안녕하세요", 0.2, 0.6);
                case 2000 -> lang.equals("ko") ? json("korean", "한국어 후보", 0.1, 0.5) : json("japanese", "こんにちは", 0.3, 0.9);
                default -> json("korean", "", 0, 0);
            };
        };
        List<Double> progress = new ArrayList<>();
        SttResult r;
        try (WhisperTranscriber t = new WhisperTranscriber(fake,
                a -> List.of(new Span(1000, 2000), new Span(3000, 5000), new Span(6000, 6500)), () -> {})) {
            r = t.transcribe(audio, progress::add);
        }
        assertEquals(List.of("1000:auto", "2000:auto", "2000:ko", "500:auto"), calls);
        assertEquals(List.of(-1.0, 1 / 3.0, 2 / 3.0, 1.0), progress);
        assertEquals(2, r.segments().size());

        SttSegment ko = r.segments().get(0);
        assertEquals(1, ko.id());
        assertEquals("안녕하세요", ko.text());
        assertEquals("ko", ko.lang());
        assertEquals(List.of(new Word(1200, 1600, " 안녕하세요")), ko.words());
        assertNull(ko.ko());

        SttSegment ja = r.segments().get(1);
        assertEquals(2, ja.id());
        assertEquals(3000, ja.startMs());
        assertEquals(5000, ja.endMs());
        assertEquals("ja", ja.lang());
        assertEquals(List.of(new Word(3300, 3900, " こんにちは")), ja.words());
        assertEquals("한국어 후보", ja.ko().text());
        assertEquals(List.of(new Word(3100, 3500, " 한국어 후보")), ja.ko().words());

        // 엔진 출력이 stt.json 검사(D-048)를 통과해야 한다.
        StageStore.saveStt(r, dir.resolve("stt.json"));
        assertEquals(r, StageStore.loadStt(dir.resolve("stt.json")));
    }

    @Test
    void spansPastEndAreClipped(@TempDir Path dir) throws Exception {
        Path audio = wav(dir, 1_500);
        List<Integer> lengths = new ArrayList<>();
        WhisperTranscriber.Inference fake = (clip, lang) -> {
            lengths.add((clip.length - 44) / 32);
            return json("korean", "끝", 0.0, 9.0);
        };
        try (WhisperTranscriber t = new WhisperTranscriber(fake,
                a -> List.of(new Span(1000, 3000), new Span(2000, 2500)), () -> {})) {
            SttResult r = t.transcribe(audio, p -> {});
            assertEquals(List.of(500), lengths);
            assertEquals(1500, r.segments().get(0).endMs());
            assertEquals(1500, r.segments().get(0).words().get(0).endMs());
        }
    }

    @Test
    void interruptStops(@TempDir Path dir) throws Exception {
        Path audio = wav(dir, 2_000);
        try (WhisperTranscriber t = new WhisperTranscriber((clip, lang) -> json("korean", "가", 0, 0),
                a -> List.of(new Span(0, 1000)), () -> {})) {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> t.transcribe(audio, p -> {}));
        }
    }

    @Test
    void closeClosesServer() throws IOException {
        boolean[] closed = {false};
        new WhisperTranscriber((c, l) -> "", a -> List.of(), () -> closed[0] = true).close();
        assertTrue(closed[0]);
    }

    @Test
    void serverErrorFails() {
        IOException e = assertThrows(IOException.class, () -> WhisperJson.parse("{\"error\":\"failed to read audio\"}", 0, 1));
        assertTrue(e.getMessage().contains("failed to read audio"));
        assertThrows(IOException.class, () -> WhisperJson.parse("<html>", 0, 1));
    }

    @Test
    void wavRejectsStereo(@TempDir Path dir) throws IOException {
        Path f = wav(dir, 100);
        byte[] b = Files.readAllBytes(f);
        b[22] = 2;
        Files.write(f, b);
        assertThrows(IOException.class, () -> Wav.open(f).close());
    }
}

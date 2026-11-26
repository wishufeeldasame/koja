package koja.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavAudioTest {
    @TempDir
    Path tmp;

    @Test
    void roundTripWithKoreanAndSpacePath() throws Exception {
        Path wav = tmp.resolve("프로젝트 샘플 1").resolve("오디오 파일.wav");
        float[] expected = new float[] { 0.0f, 0.5f, -0.5f, 0.25f, -0.75f };
        WavAudio.writeMonoPcm16(wav, expected, WavAudio.SAMPLE_RATE);

        float[] actual = WavAudio.readMono16k(wav);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 0.001f);
        }
    }

    @Test
    void readsEmptyWav() throws Exception {
        Path wav = tmp.resolve("empty.wav");
        WavAudio.writeMonoPcm16(wav, new float[0], WavAudio.SAMPLE_RATE);

        float[] actual = WavAudio.readMono16k(wav);
        assertEquals(0, actual.length);
    }

    @Test
    void skipsUnknownListChunkBeforeData() throws Exception {
        Path wav = tmp.resolve("with-list.wav");
        // ffmpeg는 fmt와 data 사이에 LIST(INFO) 청크를 넣는다.
        byte[] listPayload = "INFOISFT\u0004\u0000\u0000\u0000lavf".getBytes(StandardCharsets.US_ASCII);
        byte[] pcm = new byte[] { 0, 64, 0, -64 }; // +16384 (~0.5), -16384 (~-0.5)
        int riffSize = 4 + (8 + 16) + (8 + listPayload.length) + (8 + pcm.length);
        ByteBuffer buf = ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(riffSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(16_000);
        buf.putInt(32_000);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put("LIST".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(listPayload.length);
        buf.put(listPayload);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(pcm.length);
        buf.put(pcm);
        Files.write(wav, buf.array());

        float[] actual = WavAudio.readMono16k(wav);
        assertArrayEquals(new float[] { 0.5f, -0.5f }, actual, 0.0001f);
    }

    @Test
    void rejectsWrongSampleRateOrInvalidHeader() throws Exception {
        Path wrongSr = tmp.resolve("44k.wav");
        WavAudio.writeMonoPcm16(wrongSr, new float[] { 0.0f }, 44_100);
        assertThrows(IOException.class, () -> WavAudio.readMono16k(wrongSr));

        Path notWav = tmp.resolve("not.wav");
        Files.writeString(notWav, "not a wav file", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> WavAudio.readMono16k(notWav));

        assertThrows(IOException.class, () -> WavAudio.readMono16k(tmp.resolve("missing.wav")));
    }

    @Test
    void rejectsTruncatedDataChunk() throws Exception {
        Path wav = tmp.resolve("truncated.wav");
        WavAudio.writeMonoPcm16(wav, new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, WavAudio.SAMPLE_RATE);
        byte[] all = Files.readAllBytes(wav);
        try (OutputStream out = Files.newOutputStream(wav)) {
            out.write(all, 0, all.length - 3);
        }
        assertThrows(IOException.class, () -> WavAudio.readMono16k(wav));
    }

    @Test
    void rejectsDeclaredDataLargerThanFileWithoutOom() throws Exception {
        Path wav = tmp.resolve("huge-header.wav");
        ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + 1_000_000_000);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(16_000);
        buf.putInt(32_000);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(1_000_000_000);
        Files.write(wav, buf.array());

        assertThrows(IOException.class, () -> WavAudio.readMono16k(wav));
    }

    @Test
    void respectsThreadInterrupt() throws Exception {
        Path wav = tmp.resolve("interrupt.wav");
        WavAudio.writeMonoPcm16(wav, new float[16_000], WavAudio.SAMPLE_RATE);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> WavAudio.readMono16k(wav));
            assertTrue(!Thread.interrupted());
        } finally {
            Thread.interrupted();
        }
    }
}

package koja.engine.whisper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * audio.wav(16kHz 모노 16비트 PCM, 가져오기 단계 산출물)에서 VAD 구간을 잘라 WAV 바이트로 만든다.
 * FileChannel은 interrupt에 IOException으로 닫혀 취소 계약(D-049)과 어긋나서 RandomAccessFile을 쓴다.
 */
final class Wav implements AutoCloseable {
    static final int RATE = 16_000;
    private static final int BYTES_PER_MS = RATE * 2 / 1000;

    private final RandomAccessFile file;
    private final long dataOffset;
    private final long dataSize;

    private Wav(RandomAccessFile file, long dataOffset, long dataSize) {
        this.file = file;
        this.dataOffset = dataOffset;
        this.dataSize = dataSize;
    }

    static Wav open(Path path) throws IOException {
        RandomAccessFile f = new RandomAccessFile(path.toFile(), "r");
        try {
            ByteBuffer head = read(f, 0, 12);
            if (head.getInt(0) != 0x46464952 || head.getInt(8) != 0x45564157) { // "RIFF", "WAVE"
                throw new IOException("WAV 파일이 아닙니다: " + path);
            }
            boolean fmtOk = false;
            long pos = 12;
            while (pos + 8 <= f.length()) {
                ByteBuffer chunk = read(f, pos, 8);
                int id = chunk.getInt(0);
                long size = Integer.toUnsignedLong(chunk.getInt(4));
                if (id == 0x20746d66) { // "fmt "
                    ByteBuffer fmt = read(f, pos + 8, 16);
                    fmtOk = fmt.getShort(0) == 1 && fmt.getShort(2) == 1 && fmt.getInt(4) == RATE && fmt.getShort(14) == 16;
                } else if (id == 0x61746164) { // "data"
                    if (!fmtOk) {
                        throw new IOException("16kHz 모노 16비트 PCM WAV가 아닙니다: " + path);
                    }
                    long start = pos + 8;
                    return new Wav(f, start, Math.min(size, f.length() - start));
                }
                pos += 8 + size + (size & 1);
            }
            throw new IOException("WAV data 청크가 없습니다: " + path);
        } catch (IOException | RuntimeException e) {
            f.close();
            throw e;
        }
    }

    long durationMs() {
        return dataSize / BYTES_PER_MS;
    }

    /** [startMs, endMs) 구간을 헤더가 붙은 WAV 바이트로. 파일 끝을 넘으면 잘린다. */
    byte[] clip(long startMs, long endMs) throws IOException {
        long from = Math.min(startMs * BYTES_PER_MS, dataSize);
        long to = Math.min(Math.max(endMs * BYTES_PER_MS, from), dataSize);
        int len = Math.toIntExact(to - from);
        byte[] out = new byte[44 + len];
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(36 + len).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .putInt(0x61746164).putInt(len);
        file.seek(dataOffset + from);
        file.readFully(out, 44, len);
        return out;
    }

    private static ByteBuffer read(RandomAccessFile f, long pos, int len) throws IOException {
        byte[] b = new byte[len];
        f.seek(pos);
        f.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}

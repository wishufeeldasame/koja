package koja.engine;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 16kHz 모노 16비트 PCM WAV 읽기·쓰기.
 * C++ JNI 경로 대신 Java {@link Files}로 읽어 윈도우 한글·공백 경로에서도 안전하고,
 * ffmpeg가 넣는 {@code LIST} 등 부가 청크를 건너뛰며 취소(interrupt)에 즉시 반응한다.
 */
final class WavAudio {
    static final int SAMPLE_RATE = 16_000;
    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;
    private static final int READ_CHUNK_BYTES = 64 * 1024;

    private WavAudio() {}

    /** 16kHz 모노 16비트 PCM WAV를 [-1.0, 1.0] 범위 float 배열로 읽는다. */
    static float[] readMono16k(Path wavFile) throws IOException, InterruptedException {
        Objects.requireNonNull(wavFile, "wavFile");
        if (!Files.isRegularFile(wavFile)) {
            throw new IOException("오디오 파일이 없습니다: " + wavFile);
        }
        long fileSize = Files.size(wavFile);
        try (InputStream raw = Files.newInputStream(wavFile);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, READ_CHUNK_BYTES))) {
            String riff = readTag(in);
            if (!"RIFF".equals(riff)) {
                throw new IOException("WAV(RIFF) 파일이 아닙니다: " + wavFile);
            }
            readIntLE(in); // 전체 청크 크기
            String wave = readTag(in);
            if (!"WAVE".equals(wave)) {
                throw new IOException("WAV(WAVE) 헤더가 아닙니다: " + wavFile);
            }
            long pos = 12L;

            boolean foundFmt = false;
            int audioFormat = 0;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;

            while (true) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("오디오 읽기 취소");
                }
                String chunkId = readTagOrEof(in);
                if (chunkId == null) {
                    throw new IOException("WAV data 청크가 없습니다: " + wavFile);
                }
                long chunkSize = Integer.toUnsignedLong(readIntLE(in));
                pos += 8L;
                long remainingFileBytes = fileSize - pos;
                if (chunkSize > remainingFileBytes) {
                    throw new IOException("WAV " + chunkId + " 청크 크기(" + chunkSize
                            + ")가 실제 파일 남은 크기(" + remainingFileBytes + ")를 넘습니다: " + wavFile);
                }
                if ("fmt ".equals(chunkId)) {
                    if (chunkSize < 16) {
                        throw new IOException("WAV fmt 청크가 손상됐습니다: " + wavFile);
                    }
                    audioFormat = readUnsignedShortLE(in);
                    channels = readUnsignedShortLE(in);
                    sampleRate = readIntLE(in);
                    readIntLE(in); // byteRate
                    readUnsignedShortLE(in); // blockAlign
                    bitsPerSample = readUnsignedShortLE(in);
                    long remain = chunkSize - 16;
                    if (audioFormat == FORMAT_EXTENSIBLE && remain >= 24) {
                        // WAVEFORMATEXTENSIBLE의 SubFormat 첫 2바이트가 실제 포맷 태그(PCM=1)다.
                        skipFully(in, 8);
                        audioFormat = readUnsignedShortLE(in);
                        remain -= 10;
                    }
                    long pad = (chunkSize & 1L) != 0L && chunkSize < remainingFileBytes ? 1L : 0L;
                    skipFully(in, remain + pad);
                    pos += chunkSize + pad;
                    foundFmt = true;
                } else if ("data".equals(chunkId)) {
                    if (!foundFmt) {
                        throw new IOException("WAV data 청크 앞에 fmt 청크가 없습니다: " + wavFile);
                    }
                    validateFormat(wavFile, audioFormat, channels, sampleRate, bitsPerSample, chunkSize);
                    return readPcm16Samples(in, chunkSize, wavFile);
                } else {
                    long pad = (chunkSize & 1L) != 0L && chunkSize < remainingFileBytes ? 1L : 0L;
                    skipFully(in, chunkSize + pad);
                    pos += chunkSize + pad;
                }
            }
        }
    }

    /** 테스트·도구용 16비트 모노 PCM WAV 쓰기. */
    static void writeMonoPcm16(Path wavFile, float[] samples, int sampleRate) throws IOException {
        Objects.requireNonNull(wavFile, "wavFile");
        Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate는 양수여야 합니다: " + sampleRate);
        }
        int dataBytes = Math.multiplyExact(samples.length, 2);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) FORMAT_PCM);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataBytes);

        if (wavFile.getParent() != null) {
            Files.createDirectories(wavFile.getParent());
        }
        try (OutputStream out = Files.newOutputStream(wavFile)) {
            out.write(header.array());
            byte[] buf = new byte[Math.min(READ_CHUNK_BYTES, Math.max(2, dataBytes))];
            int pos = 0;
            while (pos < samples.length) {
                int count = Math.min(samples.length - pos, buf.length / 2);
                for (int i = 0; i < count; i++) {
                    float clamped = Math.max(-1.0f, Math.min(1.0f, samples[pos + i]));
                    int pcm = Math.round(clamped * 32767.0f);
                    buf[i * 2] = (byte) (pcm & 0xFF);
                    buf[i * 2 + 1] = (byte) ((pcm >> 8) & 0xFF);
                }
                out.write(buf, 0, count * 2);
                pos += count;
            }
        }
    }

    private static void validateFormat(
            Path wavFile, int audioFormat, int channels, int sampleRate, int bitsPerSample, long dataBytes)
            throws IOException {
        if (audioFormat != FORMAT_PCM || channels != 1 || sampleRate != SAMPLE_RATE || bitsPerSample != 16) {
            throw new IOException(
                    "16kHz 모노 16비트 PCM WAV만 지원합니다(format=" + audioFormat
                            + ", ch=" + channels + ", sr=" + sampleRate + ", bits=" + bitsPerSample + "): " + wavFile);
        }
        if ((dataBytes & 1L) != 0L) {
            throw new IOException("WAV data 크기가 16비트 샘플 경계와 맞지 않습니다: " + wavFile);
        }
        long sampleCount = dataBytes / 2L;
        if (sampleCount > Integer.MAX_VALUE) {
            throw new IOException("WAV 파일이 너무 큽니다(" + sampleCount + " 샘플): " + wavFile);
        }
    }

    private static float[] readPcm16Samples(DataInputStream in, long dataBytes, Path wavFile)
            throws IOException, InterruptedException {
        int totalSamples = (int) (dataBytes / 2L);
        float[] samples = new float[totalSamples];
        byte[] buf = new byte[READ_CHUNK_BYTES];
        int offset = 0;
        long remaining = dataBytes;
        while (remaining > 0) {
            if (Thread.interrupted()) {
                throw new InterruptedException("오디오 읽기 취소");
            }
            int toRead = (int) Math.min(buf.length, remaining);
            // 2바이트(16비트) 샘플 경계를 유지한다.
            toRead &= ~1;
            int got = readAtLeastTwo(in, buf, toRead, wavFile);
            int sampleCount = got / 2;
            for (int i = 0; i < sampleCount; i++) {
                int lo = buf[i * 2] & 0xFF;
                int hi = buf[i * 2 + 1];
                short pcm = (short) ((hi << 8) | lo);
                samples[offset + i] = pcm / 32768.0f;
            }
            offset += sampleCount;
            remaining -= got;
        }
        return samples;
    }

    private static int readAtLeastTwo(DataInputStream in, byte[] buf, int maxEvenBytes, Path wavFile)
            throws IOException {
        int n = in.read(buf, 0, maxEvenBytes);
        if (n < 0) {
            throw new EOFException("WAV data 청크가 예상보다 빨리 끝났습니다: " + wavFile);
        }
        if ((n & 1) != 0) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("WAV data 청크가 샘플 중간에서 끝났습니다: " + wavFile);
            }
            buf[n++] = (byte) next;
        }
        return n;
    }

    private static String readTag(DataInputStream in) throws IOException {
        byte[] b = new byte[4];
        in.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static String readTagOrEof(DataInputStream in) throws IOException {
        byte[] b = new byte[4];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        b[0] = (byte) first;
        in.readFully(b, 1, 3);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static int readUnsignedShortLE(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remain = bytes;
        while (remain > 0) {
            long skipped = in.skip(remain);
            if (skipped > 0) {
                remain -= skipped;
            } else {
                if (in.read() < 0) {
                    throw new EOFException("WAV 파일이 청크 중간에서 끝났습니다");
                }
                remain--;
            }
        }
    }
}

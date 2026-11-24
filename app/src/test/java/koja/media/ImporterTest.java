package koja.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import koja.core.ProjectFiles;
import koja.runtime.Ffmpeg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ffmpeg가 있을 때만 돈다. 없으면 건너뛴다. */
class ImporterTest {
    @TempDir
    static Path tmp;
    static Path video;

    @BeforeAll
    static void makeVideo() throws Exception {
        boolean ok;
        Path dir = Files.createDirectories(tmp.resolve("원본 폴더"));
        video = dir.resolve("테스트 영상.mkv");
        try {
            Process p = new ProcessBuilder(Ffmpeg.executable(), "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=3",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                    "-c:v", "mpeg4", "-c:a", "pcm_s16le", video.toString())
                    .redirectErrorStream(true).start();
            ok = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            ok = false;
        }
        assumeTrue(ok, "ffmpeg 없음");
    }

    @Test
    void makesAudioAndProxy() throws IOException {
        // 인자·파일 처리 확인용. 어느 ffmpeg에나 있는 소프트웨어 인코더를 쓴다.
        Path dir = Files.createDirectories(tmp.resolve("프로젝트 1"));
        Importer.Result r = new Importer(Ffmpeg.executable(), List.of("mpeg4")).run(video, dir, (s, f) -> {});
        assertEquals(3_000, r.durationMs(), 100);
        assertEquals("mpeg4", r.proxyEncoder(), r.proxyError());
        assertTrue(Files.size(dir.resolve(ProjectFiles.AUDIO)) > 90_000); // 16kHz·16비트·3초 ≈ 96KB
        assertTrue(Files.size(dir.resolve(ProjectFiles.PROXY)) > 0);
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(f -> f.toString().endsWith(".part")));
        }
        // 다시 돌려도 기존 결과를 덮지 않는다.
        assertThrows(IOException.class, () -> new Importer(Ffmpeg.executable(), List.of("mpeg4")).run(video, dir, (s, f) -> {}));
    }

    @Test
    void osEncoderWhenAvailable() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("프로젝트 5"));
        Importer.Result r = new Importer(Ffmpeg.executable(), Ffmpeg.h264Encoders()).run(video, dir, (s, f) -> {});
        assumeTrue(r.proxyEncoder() != null, "이 환경에서 쓸 수 있는 OS H.264 인코더 없음: " + r.proxyError());
        assertTrue(Files.size(dir.resolve(ProjectFiles.PROXY)) > 0);
    }

    @Test
    void noEncoderKeepsAudio() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("프로젝트 2"));
        Importer.Result r = new Importer(Ffmpeg.executable(), List.of("no_such_encoder")).run(video, dir, (s, f) -> {});
        assertEquals(null, r.proxyEncoder());
        assertTrue(Files.exists(dir.resolve(ProjectFiles.AUDIO)));
        assertFalse(Files.exists(dir.resolve(ProjectFiles.PROXY)));
        assertFalse(Files.exists(dir.resolve(ProjectFiles.PROXY + ".part")));
    }

    @Test
    void cancelLeavesNoPartialFiles() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("프로젝트 4"));
        Importer importer = new Importer(Ffmpeg.executable(), List.of("mpeg4"));
        assertThrows(CancellationException.class, () -> importer.run(video, dir, (s, f) -> {
            if (f >= 0) {
                importer.cancel();
            }
        }));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void missingFileFails() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("프로젝트 3"));
        assertThrows(IOException.class, () -> new Importer(Ffmpeg.executable(), List.of())
                .run(tmp.resolve("없는 파일.mp4"), dir, (s, f) -> {}));
        assertFalse(Files.exists(dir.resolve(ProjectFiles.AUDIO)));
    }
}

package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegTest {
    @Test
    void encodersPerOs() {
        assertEquals(List.of("h264_videotoolbox"), Ffmpeg.h264Encoders("Mac OS X"));
        assertEquals(List.of("h264_nvenc", "h264_mf"), Ffmpeg.h264Encoders("Windows 11"));
        assertEquals(List.of(), Ffmpeg.h264Encoders("Linux"));
    }

    @Test
    void bundledOnMac(@TempDir Path dir) throws IOException {
        Path contents = dir.resolve("앱 폴더/koja.app/Contents");
        Path launcher = contents.resolve("MacOS/koja");
        Path ffmpeg = touch(contents.resolve("ffmpeg/ffmpeg"));
        assertEquals(ffmpeg.toString(), Ffmpeg.executable(launcher.toString(), "/env/ffmpeg", "Mac OS X"));
    }

    @Test
    void bundledOnWindows(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("앱 폴더/koja");
        Path ffmpeg = touch(root.resolve("ffmpeg").resolve("ffmpeg.exe"));
        assertEquals(ffmpeg.toString(), Ffmpeg.executable(root.resolve("koja.exe").toString(), null, "Windows 11"));
    }

    @Test
    void fallsBackToEnvThenPath(@TempDir Path dir) {
        String launcher = dir.resolve("koja.app/Contents/MacOS/koja").toString();
        assertEquals("/env/ffmpeg", Ffmpeg.executable(launcher, "/env/ffmpeg", "Mac OS X"));
        assertEquals("/env/ffmpeg", Ffmpeg.executable(null, "/env/ffmpeg", "Mac OS X"));
        assertEquals("ffmpeg", Ffmpeg.executable(launcher, " ", "Mac OS X"));
        assertEquals("ffmpeg", Ffmpeg.executable(null, null, "Windows 11"));
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}

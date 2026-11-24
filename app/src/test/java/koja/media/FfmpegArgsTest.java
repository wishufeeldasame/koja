package koja.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class FfmpegArgsTest {
    private final Path src = Path.of("영상 폴더", "인터뷰 1.mov").toAbsolutePath();

    @Test
    void audioArgsKeepPathAsOneArgument() {
        Path out = Path.of("프로젝트 1", "audio.wav.part");
        List<String> args = FfmpegArgs.audio("ffmpeg", src, out);
        assertEquals(src.toString(), args.get(args.indexOf("-i") + 1));
        assertEquals(out.toString(), args.getLast());
        assertEquals("16000", args.get(args.indexOf("-ar") + 1));
        assertEquals("1", args.get(args.indexOf("-ac") + 1));
        assertEquals("wav", args.get(args.indexOf("-f") + 1));
        // 늦게 시작하는 오디오도 0초부터 채운다(STT 시간 = 영상 시간).
        assertEquals("aresample=async=1:first_pts=0", args.get(args.indexOf("-af") + 1));
    }

    @Test
    void proxyArgsUseEncoderAndKeyframes() {
        List<String> vt = FfmpegArgs.proxy("ffmpeg", src, Path.of("p.part"), "h264_videotoolbox");
        assertEquals("h264_videotoolbox", vt.get(vt.indexOf("-c:v") + 1));
        assertEquals("expr:gte(t,n_forced*1)", vt.get(vt.indexOf("-force_key_frames") + 1));
        assertTrue(vt.contains("-allow_sw"));
        assertEquals("mp4", vt.get(vt.indexOf("-f") + 1));

        List<String> nv = FfmpegArgs.proxy("ffmpeg", src, Path.of("p.part"), "h264_nvenc");
        assertTrue(!nv.contains("-allow_sw"));
        assertTrue(!nv.contains("libx264"));
        assertEquals("1", nv.get(nv.indexOf("-forced-idr") + 1));
    }

    @Test
    void parsesDuration() {
        assertEquals(OptionalLong.of(3_723_450), FfmpegArgs.durationMs("  Duration: 01:02:03.45, start: 0.000000"));
        assertEquals(OptionalLong.of(5_007), FfmpegArgs.durationMs("Duration: 00:00:05.007, start"));
        assertEquals(OptionalLong.empty(), FfmpegArgs.durationMs("  Duration: N/A, bitrate: N/A"));
    }

    @Test
    void parsesProgress() {
        assertEquals(1_500, FfmpegArgs.progressMs("out_time_us=1500000"));
        assertEquals(-1, FfmpegArgs.progressMs("out_time_us=N/A"));
        assertEquals(-1, FfmpegArgs.progressMs("progress=continue"));
    }
}

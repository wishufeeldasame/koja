package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegTest {
    @Test
    void encodersPerOs() {
        assertEquals(List.of("h264_videotoolbox"), Ffmpeg.h264Encoders("Mac OS X"));
        assertEquals(List.of("h264_nvenc", "h264_mf"), Ffmpeg.h264Encoders("Windows 11"));
        assertEquals(List.of(), Ffmpeg.h264Encoders("Linux"));
    }
}

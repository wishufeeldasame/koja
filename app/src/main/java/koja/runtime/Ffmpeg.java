package koja.runtime;

import java.util.List;
import java.util.Locale;

/** ffmpeg 실행 파일과 OS별 H.264 인코더 순서(D-037, D-038). */
public final class Ffmpeg {
    private Ffmpeg() {}

    /** shortcut: 번들 경로를 아직 찾지 않는다. jpackage에 ffmpeg를 넣는 PR에서 앱 폴더를 먼저 보게 바꾼다. */
    public static String executable() {
        String env = System.getenv("KOJA_FFMPEG");
        return env != null && !env.isBlank() ? env : "ffmpeg";
    }

    /** 앞에서부터 시도한다. libx264(GPL)는 넣지 않는다. */
    public static List<String> h264Encoders() {
        return h264Encoders(System.getProperty("os.name"));
    }

    static List<String> h264Encoders(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return List.of("h264_videotoolbox");
        }
        if (os.contains("win")) {
            return List.of("h264_nvenc", "h264_mf");
        }
        return List.of();
    }
}

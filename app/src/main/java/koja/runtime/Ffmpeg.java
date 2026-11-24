package koja.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** ffmpeg 실행 파일과 OS별 H.264 인코더 순서(D-037, D-038, D-042). */
public final class Ffmpeg {
    private Ffmpeg() {}

    /** 설치판에 들어 있는 ffmpeg → 환경 변수 {@code KOJA_FFMPEG} → PATH의 {@code ffmpeg} 순서. */
    public static String executable() {
        return executable(System.getProperty("jpackage.app-path"), System.getenv("KOJA_FFMPEG"),
                System.getProperty("os.name"));
    }

    static String executable(String appPath, String env, String osName) {
        if (appPath != null && !appPath.isBlank()) {
            Path bundled = bundled(Path.of(appPath), osName);
            if (Files.isRegularFile(bundled)) {
                return bundled.toString();
            }
        }
        return env != null && !env.isBlank() ? env : "ffmpeg";
    }

    /** {@code launcher}는 jpackage 실행 파일. --app-content는 맥은 Contents/, 윈도우는 앱 폴더 맨 위에 들어간다. */
    static Path bundled(Path launcher, String osName) {
        Path launcherDir = launcher.toAbsolutePath().getParent();
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return launcherDir.resolveSibling("ffmpeg").resolve("ffmpeg");
        }
        return launcherDir.resolve("ffmpeg").resolve(os.contains("win") ? "ffmpeg.exe" : "ffmpeg");
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

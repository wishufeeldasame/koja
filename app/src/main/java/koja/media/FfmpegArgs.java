package koja.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ffmpeg 인자 조립과 출력 해석. 프로세스를 띄우지 않는 순수 로직만 둔다. */
final class FfmpegArgs {
    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d{2}):(\\d{2})\\.(\\d+)");

    private FfmpegArgs() {}

    /** 입력 정보만 출력한다. 출력 파일이 없어 ffmpeg는 오류로 끝나지만 Duration 줄은 나온다. */
    static List<String> probe(String ffmpeg, Path source) {
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-i", source.toString());
    }

    // 오디오가 영상보다 늦게 시작하면 WAV는 시작 무음을 버린다. 0초부터 무음을 채워 STT 시간을 영상 시간에 맞춘다.
    private static final String AUDIO_FROM_ZERO = "aresample=async=1:first_pts=0";

    /** STT 입력: 첫 오디오 트랙을 16kHz 모노 16비트 WAV로. */
    static List<String> audio(String ffmpeg, Path source, Path out) {
        List<String> args = head(ffmpeg, source);
        args.addAll(List.of("-map", "0:a:0", "-vn", "-af", AUDIO_FROM_ZERO, "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                "-f", "wav", out.toString()));
        return args;
    }

    /** 검수 재생용: 720p(작으면 그대로) H.264, 1초마다 키프레임, AAC. 오디오가 없어도 만든다. */
    static List<String> proxy(String ffmpeg, Path source, Path out, String encoder) {
        List<String> args = head(ffmpeg, source);
        args.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?",
                "-vf", "scale=-2:min(720\\,ih)", "-pix_fmt", "yuv420p",
                "-c:v", encoder, "-b:v", "2500k",
                // 키프레임 간격은 강제 키프레임이 정한다. -g는 인코더 기본 간격(VideoToolbox는 1초보다 짧음)을 끄려고 크게 둔다.
                "-force_key_frames", "expr:gte(t,n_forced*1)", "-g", "600"));
        if (encoder.equals("h264_videotoolbox")) {
            args.addAll(List.of("-allow_sw", "1"));
        }
        if (encoder.equals("h264_nvenc")) {
            // NVENC는 강제 키프레임을 IDR이 아닌 I 프레임으로 내서 탐색 지점이 되지 않는다.
            args.addAll(List.of("-forced-idr", "1"));
        }
        args.addAll(List.of("-af", AUDIO_FROM_ZERO, "-c:a", "aac", "-b:a", "128k", "-ac", "2",
                "-movflags", "+faststart", "-f", "mp4", out.toString()));
        return args;
    }

    private static List<String> head(String ffmpeg, Path source) {
        // -progress pipe:1 은 stdout에 key=value 줄을 낸다. 로그는 stderr로 따로 받는다.
        return new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
                "-progress", "pipe:1", "-nostats", "-i", source.toString()));
    }

    /** ffmpeg 로그의 "Duration: 01:02:03.45" → 밀리초. "N/A"면 비어 있다. */
    static OptionalLong durationMs(String log) {
        Matcher m = DURATION.matcher(log);
        if (!m.find()) {
            return OptionalLong.empty();
        }
        String frac = (m.group(4) + "00").substring(0, 3);
        long ms = ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60
                + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(frac);
        return OptionalLong.of(ms);
    }

    /** -progress 의 "out_time_us=123" 줄 → 밀리초. 다른 줄이나 "N/A"는 -1. */
    static long progressMs(String line) {
        if (!line.startsWith("out_time_us=")) {
            return -1;
        }
        try {
            return Long.parseLong(line.substring("out_time_us=".length())) / 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

package koja.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import koja.core.ProjectFiles;

/**
 * 가져오기 1단계: 원본 → audio.wav, proxy.mp4 (설계 4장). 한 번에 하나만 돌린다.
 * cancel()은 다른 스레드에서 불러도 된다.
 */
public final class Importer {
    /** 진행 상황. fraction은 0~1, 길이를 모르면 -1. */
    public interface Listener {
        void progress(String step, double fraction);
    }

    /** proxyEncoder가 null이면 프록시를 못 만들었다(D-038). */
    public record Result(long durationMs, String proxyEncoder, String proxyError) {}

    private static final int LOG_LINES = 20;

    private final String ffmpeg;
    private final List<String> encoders;
    private volatile boolean cancelled;
    private volatile Process current;

    public Importer(String ffmpeg, List<String> encoders) {
        this.ffmpeg = ffmpeg;
        this.encoders = encoders;
    }

    public void cancel() {
        cancelled = true;
        Process p = current;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    /**
     * 실패하면 IOException, 취소하면 CancellationException. 결과는 둘 다 끝난 뒤에만 audio.wav·proxy.mp4로 옮긴다.
     * 이미 있는 파일은 덮지 않고 실패한다. 이번 실행이 만든 임시 파일만 지운다.
     */
    public Result run(Path source, Path projectDir, Listener listener) throws IOException {
        Path src = source.toAbsolutePath();
        Path dir = projectDir.toAbsolutePath();
        Path audio = dir.resolve(ProjectFiles.AUDIO);
        Path proxy = dir.resolve(ProjectFiles.PROXY);
        for (Path out : List.of(audio, proxy)) {
            if (Files.exists(out)) {
                throw new IOException(out.getFileName() + "가 이미 있습니다: " + dir);
            }
        }
        Path audioPart = Files.createTempFile(dir, "audio", ".wav.part");
        Path proxyPart = Files.createTempFile(dir, "proxy", ".mp4.part");
        try {
            Result r = convert(src, audioPart, proxyPart, listener);
            Files.move(audioPart, audio);
            if (r.proxyEncoder() != null) {
                try {
                    Files.move(proxyPart, proxy);
                } catch (IOException e) {
                    Files.deleteIfExists(audio);
                    throw e;
                }
            }
            return r;
        } finally {
            Files.deleteIfExists(audioPart);
            Files.deleteIfExists(proxyPart);
        }
    }

    private Result convert(Path src, Path audioPart, Path proxyPart, Listener listener) throws IOException {
        listener.progress("영상 정보 읽는 중", -1);
        // 0이면 길이를 몰라 진행률 없이 돈다.
        long durationMs = Math.max(0, exec(FfmpegArgs.probe(ffmpeg, src), 0, null, null).durationMs);

        Run a = exec(FfmpegArgs.audio(ffmpeg, src, audioPart), durationMs, listener, "오디오 추출 중");
        if (a.exit != 0) {
            throw new IOException("오디오를 추출할 수 없습니다(오디오 트랙이 없거나 읽을 수 없는 파일).\n" + a.tail());
        }

        List<String> errors = new ArrayList<>();
        for (String encoder : encoders) {
            Run p = exec(FfmpegArgs.proxy(ffmpeg, src, proxyPart, encoder), durationMs, listener,
                    "프록시 만드는 중 (" + encoder + ")");
            if (p.exit == 0) {
                return new Result(durationMs, encoder, null);
            }
            errors.add(encoder + ": " + p.tail());
        }
        return new Result(durationMs, null,
                errors.isEmpty() ? "이 OS에서 쓸 H.264 인코더가 없습니다." : String.join("\n", errors));
    }

    /** log는 stderr 마지막 몇 줄, durationMs는 stderr에서 처음 본 Duration(없으면 -1). */
    private record Run(int exit, List<String> log, long durationMs) {
        String tail() {
            return String.join("\n", log);
        }
    }

    private Run exec(List<String> args, long durationMs, Listener listener, String step) throws IOException {
        if (cancelled) {
            throw new CancellationException();
        }
        Process p;
        try {
            p = new ProcessBuilder(args).start();
        } catch (IOException e) {
            throw new IOException("ffmpeg를 실행할 수 없습니다(" + ffmpeg + "). PATH에 ffmpeg를 두거나 KOJA_FFMPEG를 지정하세요.", e);
        }
        current = p;
        try {
            // start() 도중 cancel()이 불렸으면 current가 비어 있어 종료하지 못했다.
            if (cancelled) {
                throw new CancellationException();
            }
            p.getOutputStream().close();
            ArrayDeque<String> log = new ArrayDeque<>();
            AtomicLong duration = new AtomicLong(-1);
            // stderr를 따로 비우지 않으면 파이프가 차서 ffmpeg가 멈춘다.
            Thread drain = Thread.ofVirtual().start(() -> readLines(p.getErrorStream(), line -> {
                if (duration.get() < 0) {
                    FfmpegArgs.durationMs(line).ifPresent(duration::set);
                }
                synchronized (log) {
                    log.addLast(line);
                    if (log.size() > LOG_LINES) {
                        log.removeFirst();
                    }
                }
            }));
            readLines(p.getInputStream(), line -> {
                long ms = FfmpegArgs.progressMs(line);
                if (listener != null && ms >= 0) {
                    listener.progress(step, durationMs > 0 ? Math.min(1, (double) ms / durationMs) : -1);
                }
            });
            int exit = p.waitFor();
            drain.join();
            if (cancelled) {
                throw new CancellationException();
            }
            synchronized (log) {
                return new Run(exit, List.copyOf(log), duration.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } finally {
            p.destroyForcibly();
            current = null;
        }
    }

    private static void readLines(InputStream in, Consumer<String> sink) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                sink.accept(line);
            }
        } catch (IOException e) {
            // 프로세스를 강제 종료하면 파이프가 끊긴다. 결과는 종료 코드로 판단한다.
        }
    }
}

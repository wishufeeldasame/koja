package koja.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로컬 모델 파일 저장·다운로드·무결성 검증(D-050).
 * {@code .part} 임시 파일에 받아 Range 이어 받기와 취소를 지원하고, SHA-256 검증 뒤 원자적으로 옮긴다.
 * 같은 경로에 대한 동시 다운로드는 프로세스 내부·외부 파일 잠금({@code .lock})으로 직렬화한다.
 */
public final class ModelStore implements AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int HASH_BUFFER_SIZE = 1024 * 1024;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final long NOT_READING = Long.MIN_VALUE;
    private static final long LOCK_POLL_MILLIS = 50L;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    /** 다운로드할 모델 명세. {@code expectedSize}가 -1이면 크기를 알 수 없는 모델로 처리한다. */
    public record ModelSpec(String relativePath, URI uri, long expectedSize, String sha256) {
        public ModelSpec {
            validateRelativePath(relativePath);
            Objects.requireNonNull(uri, "uri");
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("HTTP/HTTPS 다운로드 URI가 아닙니다: " + uri);
            }
            if (expectedSize < -1) {
                throw new IllegalArgumentException("expectedSize는 -1 이상이어야 합니다: " + expectedSize);
            }
            if (sha256 == null || !SHA256_PATTERN.matcher(sha256.trim()).matches()) {
                throw new IllegalArgumentException("유효한 SHA-256 해시(64자리 16진수)가 아닙니다");
            }
            sha256 = sha256.trim().toLowerCase(Locale.ROOT);
        }

        /** Hugging Face 저장소 특정 리비전의 파일 다운로드 명세를 만든다. */
        public static ModelSpec huggingFace(
                String relativePath,
                String repo,
                String revision,
                String remoteFile,
                long expectedSize,
                String sha256) {
            validateRepo(repo);
            validateSegment(revision, "revision");
            validateRelativePath(remoteFile);
            if (remoteFile.indexOf('?') >= 0 || remoteFile.indexOf('#') >= 0
                    || revision.indexOf('?') >= 0 || revision.indexOf('#') >= 0) {
                throw new IllegalArgumentException("Hugging Face 경로에 '?' 또는 '#'을 쓸 수 없습니다");
            }
            String path = "/" + repo + "/resolve/" + revision + "/" + remoteFile;
            try {
                URI hfUri = new URI("https", "huggingface.co", path, null);
                return new ModelSpec(relativePath, hfUri, expectedSize, sha256);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Hugging Face URI를 만들 수 없습니다: " + path, e);
            }
        }
    }

    /** 다운로드 진행률 콜백. {@code totalBytes}는 알 수 없으면 -1. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    /**
     * 다른 스레드(예: JavaFX UI 스레드)에서 다운로드를 즉시 취소하기 위한 신호.
     * 취소되면 {@code .part} 파일을 남겨 다음 호출에서 이어 받을 수 있게 한다.
     */
    public static final class CancelToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object lock = new Object();
        private Runnable activeAbort;

        public void cancel() {
            Runnable abort;
            synchronized (lock) {
                cancelled.set(true);
                abort = activeAbort;
                activeAbort = null;
            }
            if (abort != null) {
                try {
                    abort.run();
                } catch (RuntimeException ignored) {
                    // 취소 정리 중 예외로 호출 스레드를 깨뜨리지 않는다.
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void setActiveAbort(Runnable abort) {
            boolean alreadyCancelled;
            synchronized (lock) {
                alreadyCancelled = cancelled.get();
                if (!alreadyCancelled) {
                    activeAbort = abort;
                }
            }
            if (alreadyCancelled && abort != null) {
                try {
                    abort.run();
                } catch (RuntimeException ignored) {
                    // 이미 취소 상태다.
                }
            }
        }

        void clearActiveAbort(Runnable abort) {
            synchronized (lock) {
                if (activeAbort == abort) {
                    activeAbort = null;
                }
            }
        }
    }

    /** 다운로드한 파일의 SHA-256이 명세와 다를 때 발생한다. 손상된 {@code .part} 파일은 삭제된다. */
    public static final class ChecksumMismatchException extends IOException {
        private final String relativePath;
        private final String expectedSha256;
        private final String actualSha256;

        public ChecksumMismatchException(String relativePath, String expectedSha256, String actualSha256) {
            super("모델 체크섬이 일치하지 않습니다(" + relativePath + "): 기대=" + expectedSha256 + ", 실제=" + actualSha256);
            this.relativePath = relativePath;
            this.expectedSha256 = expectedSha256;
            this.actualSha256 = actualSha256;
        }

        public String relativePath() {
            return relativePath;
        }

        public String expectedSha256() {
            return expectedSha256;
        }

        public String actualSha256() {
            return actualSha256;
        }
    }

    private final Path modelsDir;
    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final boolean ownsClient;

    public ModelStore(Path modelsDir) {
        this(modelsDir, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build(), DEFAULT_READ_TIMEOUT, true);
    }

    public ModelStore(Path modelsDir, HttpClient httpClient) {
        this(modelsDir, httpClient, DEFAULT_READ_TIMEOUT);
    }

    public ModelStore(Path modelsDir, HttpClient httpClient, Duration readTimeout) {
        this(modelsDir, Objects.requireNonNull(httpClient, "httpClient"), readTimeout, false);
    }

    private ModelStore(Path modelsDir, HttpClient httpClient, Duration readTimeout, boolean ownsClient) {
        this.modelsDir = Objects.requireNonNull(modelsDir, "modelsDir").toAbsolutePath().normalize();
        this.httpClient = httpClient;
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout은 양수여야 합니다");
        }
        this.readTimeout = readTimeout;
        this.ownsClient = ownsClient;
    }

    public Path modelsDir() {
        return modelsDir;
    }

    /** 명세에 해당하는 최종 모델 파일 경로. {@code modelsDir} 밖으로 벗어나지 않는다. */
    public Path pathOf(ModelSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Path current = modelsDir;
        for (String segment : spec.relativePath().split("/")) {
            current = current.resolve(segment);
        }
        Path normalized = current.normalize();
        if (!normalized.startsWith(modelsDir) || normalized.equals(modelsDir)) {
            throw new IllegalArgumentException("모델 경로가 저장 폴더 밖을 가리킵니다: " + spec.relativePath());
        }
        return normalized;
    }

    /** 다운로드 중 임시 {@code .part} 파일 경로(최종 파일과 같은 폴더에 둬 원자적 이동을 보장한다). */
    public Path partPathOf(ModelSpec spec) {
        Path dest = pathOf(spec);
        return dest.resolveSibling(dest.getFileName().toString() + ".part");
    }

    private static Path lockPathOf(Path dest) {
        return dest.resolveSibling(dest.getFileName().toString() + ".lock");
    }

    /** 파일 존재 여부와 크기(지정된 경우)만 빠르게 확인한다. 해시는 계산하지 않는다. */
    public boolean isInstalled(ModelSpec spec) {
        Path dest = pathOf(spec);
        if (!Files.isRegularFile(dest)) {
            return false;
        }
        if (spec.expectedSize() < 0) {
            return true;
        }
        try {
            return Files.size(dest) == spec.expectedSize();
        } catch (IOException e) {
            return false;
        }
    }

    /** 최종 파일의 크기(지정된 경우)와 SHA-256을 검증한다. */
    public boolean verify(ModelSpec spec) throws IOException {
        return verifyFile(pathOf(spec), spec, null);
    }

    public Path ensureDownloaded(ModelSpec spec) throws IOException {
        return ensureDownloaded(spec, null, null);
    }

    /**
     * 모델이 이미 있고 검증을 통과하면 그대로 반환하고, 없으면 다운로드·검증 후 원자적으로 배치한다.
     * 취소되면 {@link CancellationException}을 던지고 {@code .part} 파일을 유지해 다음 호출에서 이어 받는다.
     * SHA-256이 틀리면 {@code .part} 파일을 지우고 {@link ChecksumMismatchException}을 던진다.
     */
    public Path ensureDownloaded(ModelSpec spec, ProgressListener listener, CancelToken cancelToken) throws IOException {
        Objects.requireNonNull(spec, "spec");
        checkCancelled(cancelToken);

        Path dest = pathOf(spec);
        Path part = partPathOf(spec);
        Files.createDirectories(dest.getParent());

        try (PathLock ignored = acquirePathLock(dest, cancelToken)) {
            return ensureDownloadedLocked(spec, dest, part, listener, cancelToken);
        }
    }

    private Path ensureDownloadedLocked(
            ModelSpec spec,
            Path dest,
            Path part,
            ProgressListener listener,
            CancelToken cancelToken) throws IOException {
        if (verifyFile(dest, spec, cancelToken)) {
            long size = Files.size(dest);
            Files.deleteIfExists(part);
            notifyProgress(listener, size, size);
            return dest;
        }

        long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
        if (spec.expectedSize() >= 0 && existing > spec.expectedSize()) {
            Files.deleteIfExists(part);
            existing = 0L;
        }

        boolean skippedNetwork = spec.expectedSize() >= 0 && existing == spec.expectedSize();
        if (!skippedNetwork) {
            downloadToPart(spec, part, existing, listener, cancelToken);
        } else {
            // 직전 실행이 마지막 바이트 기록 후 force 전에 취소됐을 수 있으므로 이동 전 디스크에 반영한다.
            syncFile(part);
        }

        checkCancelled(cancelToken);
        String actualHash = sha256(part, cancelToken);
        if (!actualHash.equals(spec.sha256())) {
            Files.deleteIfExists(part);
            throw new ChecksumMismatchException(spec.relativePath(), spec.sha256(), actualHash);
        }

        // 같은 폴더 안의 임시 파일이라 동일 파일시스템에서 원자적으로 바뀐다.
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (skippedNetwork) {
            long size = Files.size(dest);
            notifyProgress(listener, size, size);
        }
        return dest;
    }

    /** 파일의 SHA-256(소문자 64자리 16진수)을 스트리밍으로 계산한다. */
    public static String sha256(Path file) throws IOException {
        return sha256(file, null);
    }

    private static String sha256(Path file, CancelToken cancelToken) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                checkCancelled(cancelToken);
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void syncFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    private static boolean verifyFile(Path file, ModelSpec spec, CancelToken cancelToken) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (spec.expectedSize() >= 0 && Files.size(file) != spec.expectedSize()) {
            return false;
        }
        return sha256(file, cancelToken).equals(spec.sha256());
    }

    private void downloadToPart(
            ModelSpec spec,
            Path part,
            long initialOffset,
            ProgressListener listener,
            CancelToken cancelToken) throws IOException {
        long offset = initialOffset;
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancelled(cancelToken);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(spec.uri())
                    .timeout(readTimeout)
                    .GET();
            if (offset > 0) {
                reqBuilder.header("Range", "bytes=" + offset + "-");
            }
            HttpResponse<InputStream> response = sendCancellable(reqBuilder.build(), cancelToken);
            int status = response.statusCode();

            long downloaded;
            long totalBytes;
            // 어떤 검증 예외가 나더라도 응답 본문 스트림을 반드시 닫아야 HttpClient.close()가 막히지 않는다.
            try (InputStream body = response.body()) {
                if (status == 416 && offset > 0 && attempt == 0) {
                    Files.deleteIfExists(part);
                    offset = 0L;
                    continue;
                }

                if (status != 200 && status != 206) {
                    throw new IOException("모델 다운로드 실패 (HTTP " + status + "): " + spec.uri());
                }

                boolean resuming = status == 206;
                long writeOffset;
                if (resuming) {
                    if (offset <= 0) {
                        throw new IOException("Range 요청 없이 HTTP 206 응답을 받았습니다: " + spec.uri());
                    }
                    ContentRange range = parseContentRange(response.headers().firstValue("Content-Range").orElse(null));
                    if (range == null || range.start() != offset) {
                        throw new IOException("서버 Content-Range가 요청 오프셋(" + offset + ")과 다릅니다: "
                                + response.headers().firstValue("Content-Range").orElse("(없음)"));
                    }
                    writeOffset = offset;
                    totalBytes = resolveTotalBytes(spec, range.total(),
                            response.headers().firstValueAsLong("Content-Length"), writeOffset);
                } else {
                    writeOffset = 0L;
                    totalBytes = resolveTotalBytes(spec, -1L,
                            response.headers().firstValueAsLong("Content-Length"), 0L);
                }

                downloaded = streamResponseToPart(spec, body, part, writeOffset, totalBytes, listener, cancelToken);
            }

            // 스트림과 파일 채널을 모두 닫은 뒤에 삭제·검증한다(윈도우 파일 잠금 방지).
            if (spec.expectedSize() >= 0 && downloaded > spec.expectedSize()) {
                Files.deleteIfExists(part);
                throw new IOException("받은 크기가 예상 크기를 넘었습니다("
                        + downloaded + " > " + spec.expectedSize() + "): " + spec.relativePath());
            }
            if (totalBytes >= 0 && downloaded < totalBytes) {
                throw new IOException("다운로드가 중간에 끊겼습니다("
                        + downloaded + "/" + totalBytes + " 바이트): " + spec.relativePath());
            }
            if (spec.expectedSize() >= 0 && downloaded < spec.expectedSize()) {
                throw new IOException("받은 크기가 예상 크기보다 작습니다("
                        + downloaded + "/" + spec.expectedSize() + " 바이트): " + spec.relativePath());
            }
            return;
        }
        throw new IOException("모델 다운로드를 시작할 수 없습니다: " + spec.uri());
    }

    private long streamResponseToPart(
            ModelSpec spec,
            InputStream body,
            Path part,
            long writeOffset,
            long totalBytes,
            ProgressListener listener,
            CancelToken cancelToken) throws IOException {
        AtomicBoolean stalled = new AtomicBoolean();
        AtomicBoolean reading = new AtomicBoolean(true);
        // 파일 열기·쓰기·진행 콜백 시간은 빼고 read()에서 막힌 시간만 재야 느린 디스크에서 오탐하지 않는다.
        // 읽기 시작 시각 하나를 CAS로 주고받아, 제때 끝난 읽기를 워치독이 뒤늦게 타임아웃으로 처리하지 않게 한다.
        AtomicLong readStartNanos = new AtomicLong(NOT_READING);
        long timeoutNanos = readTimeout.toNanos();
        long checkMillis = Math.max(10L, Math.min(1_000L, readTimeout.toMillis() / 4));

        Thread watchdog = Thread.ofVirtual()
                .name("koja-download-watchdog")
                .start(() -> {
                    while (reading.get()) {
                        long start = readStartNanos.get();
                        if (start != NOT_READING
                                && System.nanoTime() - start >= timeoutNanos
                                && readStartNanos.compareAndSet(start, NOT_READING)) {
                            stalled.set(true);
                            closeQuietly(body);
                            return;
                        }
                        try {
                            Thread.sleep(checkMillis);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });

        Runnable abort = () -> closeQuietly(body);
        if (cancelToken != null) {
            cancelToken.setActiveAbort(abort);
        }
        try {
            checkCancelled(cancelToken);
            try (FileChannel channel = openPartChannel(part, writeOffset)) {
                long downloaded = writeOffset;
                notifyProgress(listener, downloaded, totalBytes);

                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    checkCancelled(cancelToken);
                    readStartNanos.set(System.nanoTime());
                    int n = body.read(buffer);
                    if (readStartNanos.getAndSet(NOT_READING) == NOT_READING) {
                        throw new IOException("모델 다운로드 본문 수신 시간 초과(" + readTimeout + "): " + spec.relativePath());
                    }
                    if (n < 0) {
                        break;
                    }
                    checkCancelled(cancelToken);
                    if (n > 0) {
                        writeFully(channel, buffer, n);
                        downloaded += n;
                        if (spec.expectedSize() >= 0 && downloaded > spec.expectedSize()) {
                            return downloaded;
                        }
                        notifyProgress(listener, downloaded, totalBytes);
                    }
                }
                checkCancelled(cancelToken);
                // 원자적 이동 전에 디스크에 내려야 정전·강제 종료 시 깨진 파일로 바뀌지 않는다.
                channel.force(true);
                return downloaded;
            }
        } catch (IOException e) {
            if (isCancelled(cancelToken)) {
                throw new CancellationException("모델 다운로드가 취소되었습니다");
            }
            if (stalled.get()) {
                throw new IOException("모델 다운로드 본문 수신 시간 초과(" + readTimeout + "): " + spec.relativePath(), e);
            }
            throw e;
        } finally {
            reading.set(false);
            watchdog.interrupt();
            if (cancelToken != null) {
                cancelToken.clearActiveAbort(abort);
            }
        }
    }

    private HttpResponse<InputStream> sendCancellable(HttpRequest request, CancelToken cancelToken) throws IOException {
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream());
        Runnable abort = () -> future.cancel(true);
        if (cancelToken != null) {
            cancelToken.setActiveAbort(abort);
        }
        try {
            return future.get(readTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeResponseAsyncIfCompleted(future);
            throw new IOException("모델 다운로드 응답 헤더 시간 초과(" + readTimeout + "): " + request.uri(), e);
        } catch (CancellationException e) {
            closeResponseAsyncIfCompleted(future);
            throw new CancellationException("모델 다운로드가 취소되었습니다");
        } catch (InterruptedException e) {
            future.cancel(true);
            closeResponseAsyncIfCompleted(future);
            Thread.currentThread().interrupt();
            throw new CancellationException("모델 다운로드가 중단되었습니다");
        } catch (ExecutionException e) {
            if (isCancelled(cancelToken)) {
                throw new CancellationException("모델 다운로드가 취소되었습니다");
            }
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("모델 다운로드 요청에 실패했습니다: " + request.uri(), cause);
        } finally {
            if (cancelToken != null) {
                cancelToken.clearActiveAbort(abort);
            }
        }
    }

    private static void closeResponseAsyncIfCompleted(CompletableFuture<HttpResponse<InputStream>> future) {
        future.whenComplete((res, err) -> {
            if (res != null) {
                closeQuietly(res.body());
            }
        });
    }

    /** 같은 JVM 안의 스레드 간 잠금 + 다른 프로세스 간 OS 파일 잠금을 순서대로 잡는다. */
    private static PathLock acquirePathLock(Path dest, CancelToken cancelToken) throws IOException {
        Path key = dest.toAbsolutePath().normalize();
        ReentrantLock threadLock = IN_PROCESS_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
        while (true) {
            checkCancelled(cancelToken);
            try {
                if (threadLock.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("모델 잠금 대기가 중단되었습니다");
            }
        }

        FileChannel lockChannel = null;
        FileLock fileLock = null;
        try {
            Path lockFile = lockPathOf(dest);
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                checkCancelled(cancelToken);
                try {
                    fileLock = lockChannel.tryLock();
                    if (fileLock != null) {
                        return new PathLock(threadLock, lockChannel, fileLock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // 다른 경로 표기로 열린 동일 파일 잠금이 풀릴 때까지 잠시 대기한다.
                }
                try {
                    Thread.sleep(LOCK_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("모델 잠금 대기가 중단되었습니다");
                }
            }
        } catch (Throwable t) {
            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException ignored) {
                    // 정리 예외 무시
                }
            }
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (IOException ignored) {
                    // 정리 예외 무시
                }
            }
            threadLock.unlock();
            throw t;
        }
    }

    private static final class PathLock implements AutoCloseable {
        private final ReentrantLock threadLock;
        private final FileChannel channel;
        private final FileLock fileLock;

        PathLock(ReentrantLock threadLock, FileChannel channel, FileLock fileLock) {
            this.threadLock = threadLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() throws IOException {
            IOException error = null;
            try {
                fileLock.release();
            } catch (IOException e) {
                error = e;
            } finally {
                try {
                    channel.close();
                } catch (IOException e) {
                    if (error == null) {
                        error = e;
                    } else {
                        error.addSuppressed(e);
                    }
                } finally {
                    threadLock.unlock();
                }
            }
            if (error != null) {
                throw error;
            }
        }
    }

    private static FileChannel openPartChannel(Path part, long writeOffset) throws IOException {
        if (writeOffset > 0) {
            FileChannel ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                ch.truncate(writeOffset);
                ch.position(writeOffset);
                return ch;
            } catch (IOException e) {
                ch.close();
                throw e;
            }
        }
        return FileChannel.open(part,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void writeFully(FileChannel channel, byte[] buffer, int length) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length);
        while (bb.hasRemaining()) {
            channel.write(bb);
        }
    }

    private static long resolveTotalBytes(
            ModelSpec spec, long rangeTotal, OptionalLong contentLength, long writeOffset) throws IOException {
        long serverTotal = rangeTotal;
        if (serverTotal < 0 && contentLength.isPresent() && contentLength.getAsLong() >= 0) {
            serverTotal = writeOffset + contentLength.getAsLong();
        }
        if (spec.expectedSize() >= 0 && serverTotal >= 0 && serverTotal != spec.expectedSize()) {
            throw new IOException("서버 파일 크기(" + serverTotal + ")가 예상 크기(" + spec.expectedSize()
                    + ")와 다릅니다: " + spec.relativePath());
        }
        return serverTotal >= 0 ? serverTotal : spec.expectedSize();
    }

    private record ContentRange(long start, long end, long total) {}

    private static ContentRange parseContentRange(String header) {
        if (header == null) {
            return null;
        }
        Matcher m = CONTENT_RANGE_PATTERN.matcher(header.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            long start = Long.parseLong(m.group(1));
            long end = Long.parseLong(m.group(2));
            long total = "*".equals(m.group(3)) ? -1L : Long.parseLong(m.group(3));
            if (end < start || (total >= 0 && end >= total)) {
                return null;
            }
            return new ContentRange(start, end, total);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void notifyProgress(ProgressListener listener, long downloaded, long total) {
        if (listener != null) {
            listener.onProgress(downloaded, total);
        }
    }

    private static boolean isCancelled(CancelToken cancelToken) {
        return (cancelToken != null && cancelToken.isCancelled()) || Thread.currentThread().isInterrupted();
    }

    private static void checkCancelled(CancelToken cancelToken) {
        if (isCancelled(cancelToken)) {
            throw new CancellationException("모델 다운로드가 취소되었습니다");
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 정리 단계 예외는 무시한다.
            }
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM에 SHA-256 구현이 없습니다", e);
        }
    }

    private static void validateRelativePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("모델 상대 경로가 비어 있습니다");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("허용되지 않는 모델 상대 경로입니다: " + path);
        }
        for (String segment : path.split("/", -1)) {
            validateSegment(segment, "relativePath");
        }
    }

    private static void validateRepo(String repo) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Hugging Face 저장소 이름이 비어 있습니다");
        }
        String[] parts = repo.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Hugging Face 저장소는 'owner/name' 형식이어야 합니다: " + repo);
        }
        validateSegment(parts[0], "repo");
        validateSegment(parts[1], "repo");
    }

    private static void validateSegment(String segment, String field) {
        if (segment == null || segment.isBlank() || ".".equals(segment) || "..".equals(segment)
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + "에 허용되지 않는 경로 조각이 있습니다: " + segment);
        }
    }

    @Override
    public void close() {
        if (ownsClient) {
            httpClient.close();
        }
    }
}

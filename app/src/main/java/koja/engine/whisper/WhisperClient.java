package koja.engine.whisper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** whisper-server {@code POST /inference} multipart 호출. */
final class WhisperClient implements WhisperTranscriber.Inference {
    // CPU로 돌 때 긴 구간 하나가 수 분 걸릴 수 있다(추정). PoC와 같은 10분.
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient http;
    private final URI inference;

    WhisperClient(HttpClient http, URI baseUri) {
        this.http = http;
        this.inference = baseUri.resolve("/inference");
    }

    @Override
    public String transcribe(byte[] wav, String language) throws IOException, InterruptedException {
        String boundary = "koja-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);
        field(body, boundary, "response_format", "verbose_json");
        field(body, boundary, "language", language);
        field(body, boundary, "temperature", "0");
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(wav);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(inference)
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        // send()는 interrupt로 멈추지 않을 수 있어서 sendAsync를 기다리다 interrupt면 취소한다.
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> res;
        try {
            res = future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException("whisper-server 요청 실패", e.getCause());
        }
        if (res.statusCode() != 200) {
            String text = res.body();
            throw new IOException("whisper-server HTTP " + res.statusCode() + ": "
                    + (text.length() > 500 ? text.substring(0, 500) : text));
        }
        return res.body();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}

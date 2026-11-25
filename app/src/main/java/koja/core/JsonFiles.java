package koja.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 프로젝트 폴더의 JSON 파일 읽기·쓰기. JSON은 항상 UTF-8이다(Jackson 기본). */
final class JsonFiles {
    // version 1.9 같은 실수를 1로 잘라 통과시키지 않는다.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    private JsonFiles() {}

    static <T> T read(Path file, Class<T> type) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readValue(in, type);
        } catch (JacksonException e) {
            throw new IOException(file.getFileName() + "을(를) 읽을 수 없습니다: " + file, e);
        }
    }

    /** 임시 파일에 다 쓴 뒤 바꿔치기한다. 쓰다가 앱이 죽어도 기존 파일이 반쯤 덮이지 않는다. */
    static void write(Object value, Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            byte[] json;
            try {
                json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            } catch (JacksonException e) {
                throw new IOException(file.getFileName() + "을(를) 쓸 수 없습니다: " + file, e);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                Channels.newOutputStream(ch).write(json);
                // 바꿔치기 전에 디스크에 내려야 정전 때 빈 파일로 바뀌지 않는다.
                ch.force(true);
            }
            // 원자적 이동이 안 되는 파일시스템이면 AtomicMoveNotSupportedException으로 실패시킨다. 비원자적 덮어쓰기는 기존 파일을 잃을 수 있다.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

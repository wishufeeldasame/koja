package koja.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** project.json 읽기·쓰기. JSON은 항상 UTF-8이다(Jackson 기본). */
public final class ProjectStore {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ProjectStore() {}

    public static Project load(Path file) throws IOException {
        Project project;
        try (InputStream in = Files.newInputStream(file)) {
            project = MAPPER.readValue(in, Project.class);
        } catch (JacksonException e) {
            throw new IOException("project.json을 읽을 수 없습니다: " + file, e);
        }
        if (project.version() != Project.CURRENT_VERSION) {
            throw new IOException("지원하지 않는 project.json 버전 " + project.version() + ": " + file);
        }
        return project;
    }

    /** 임시 파일에 다 쓴 뒤 바꿔치기한다. 쓰다가 앱이 죽어도 기존 파일이 반쯤 덮이지 않는다. */
    public static void save(Project project, Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "project", ".json.tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, project);
            } catch (JacksonException e) {
                throw new IOException("project.json을 쓸 수 없습니다: " + file, e);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

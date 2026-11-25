package koja.core;

import java.io.IOException;
import java.nio.file.Path;

/** project.json 읽기·쓰기. 원자적 저장은 JsonFiles. */
public final class ProjectStore {
    private ProjectStore() {}

    public static Project load(Path file) throws IOException {
        Project project = JsonFiles.read(file, Project.class);
        validate(project, file);
        return project;
    }

    /** 읽을 때와 쓸 때 같은 규칙을 쓴다. 다시 못 읽을 파일로 기존 저장본을 덮지 않기 위해서다. */
    private static void validate(Project project, Path file) throws IOException {
        if (project == null) {
            throw new IOException("project.json이 비어 있습니다: " + file);
        }
        if (project.version() != Project.CURRENT_VERSION) {
            throw new IOException("지원하지 않는 project.json 버전 " + project.version() + ": " + file);
        }
        if (project.source() == null || project.source().path() == null || project.source().path().isBlank()
                || project.speakers() == null || project.sttSegments() == null || project.rows() == null) {
            throw new IOException("project.json에 필수 항목(source.path, speakers, sttSegments, rows)이 없습니다: " + file);
        }
    }

    public static void save(Project project, Path file) throws IOException {
        validate(project, file);
        JsonFiles.write(project, file);
    }
}

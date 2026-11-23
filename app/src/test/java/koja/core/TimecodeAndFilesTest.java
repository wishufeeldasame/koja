package koja.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimecodeAndFilesTest {
    @TempDir
    Path tmp;

    @Test
    void formatsMinutesAndHours() {
        assertEquals("", Timecode.format(null));
        assertEquals("0:00", Timecode.format(999L));
        assertEquals("6:38", Timecode.format(398_000L));
        assertEquals("59:59", Timecode.format(3_599_999L));
        assertEquals("1:00:00", Timecode.format(3_600_000L));
        assertEquals("1:02:05", Timecode.format(3_725_000L));
    }

    @Test
    void prefersProxyOverSource() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("프로젝트 1"));
        Path file = dir.resolve("project.json");
        Project project = ProjectStoreTest.sample();

        assertEquals(dir.resolve(project.source().path()), ProjectFiles.playbackVideo(file, project));

        Path proxy = Files.createFile(dir.resolve(ProjectFiles.PROXY));
        assertEquals(proxy, ProjectFiles.playbackVideo(file, project));
    }
}

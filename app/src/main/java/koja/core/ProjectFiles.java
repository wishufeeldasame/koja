package koja.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** 프로젝트 폴더(project.json이 있는 폴더) 안의 파일 위치. */
public final class ProjectFiles {
    public static final String PROXY = "proxy.mp4";

    private ProjectFiles() {}

    /** 검수 화면에서 재생할 영상. 프록시가 있으면 프록시, 없으면 원본(상대 경로는 프로젝트 폴더 기준). */
    public static Path playbackVideo(Path projectFile, Project project) {
        Path dir = projectFile.toAbsolutePath().getParent();
        Path proxy = dir.resolve(PROXY);
        return Files.isRegularFile(proxy) ? proxy : dir.resolve(project.source().path());
    }
}

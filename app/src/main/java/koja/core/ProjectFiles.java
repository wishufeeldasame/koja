package koja.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** 프로젝트 폴더(project.json이 있는 폴더) 안의 파일 위치. */
public final class ProjectFiles {
    /** 가져오기가 만드는 프로젝트 파일. 단계 파일(stt.json …)은 폴더에 하나뿐이라 이 파일만 처리한다. */
    public static final String PROJECT = "project.json";
    public static final String PROXY = "proxy.mp4";
    /** STT 입력(16kHz 모노). */
    public static final String AUDIO = "audio.wav";
    /** 단계 결과 파일(설계 5장). */
    public static final String STT = "stt.json";
    public static final String DIAR = "diar.json";

    private ProjectFiles() {}

    /** 검수 화면에서 재생할 영상. 프록시가 있으면 프록시, 없으면 원본(상대 경로는 프로젝트 폴더 기준). */
    public static Path playbackVideo(Path projectFile, Project project) {
        Path dir = projectFile.toAbsolutePath().getParent();
        Path proxy = dir.resolve(PROXY);
        return Files.isRegularFile(proxy) ? proxy : dir.resolve(project.source().path());
    }
}

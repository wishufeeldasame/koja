package koja.engine;

import java.io.IOException;

/**
 * 단계 엔진 공통 계약. 파이프라인이 단계 직전에 열고 끝나면 바로 close()로 내린다. GPU는 한 번에 한 단계만 쓴다(AGENTS.md 절대 규칙 5).
 * 작업 메서드는 진행률을 0~1(모르면 -1)로 알리고, 스레드 interrupt를 받으면 InterruptedException으로 멈춘다(D-049).
 */
public interface Engine extends AutoCloseable {
    /** project.json stages.<단계>.engine에 남는 이름. 모델 이름을 포함해도 된다. */
    String name();

    /** 서버 프로세스·GPU 메모리를 내린다. 두 번 불러도 된다. */
    @Override
    void close() throws IOException;
}

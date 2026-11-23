# 변경 이력

PR마다 `Unreleased`에 한 줄씩 추가한다. 릴리스할 때 `Unreleased`를 버전 번호와 날짜로 바꾼다.
분류: 추가 / 변경 / 수정 / 삭제

## Unreleased

### 추가
- 프로젝트 문서: README, AGENTS.md(모든 AI 도구 공통 작업 규칙), 설계(docs/설계.md), 결정 기록(docs/결정기록.md)
- 프로젝트 에이전트 3종(spec-reviewer, cross-platform-checker, eval-runner). 원본은 `.agents/agents/`, Claude Code·Codex·OpenCode용 파일은 `scripts/sync_agents.py`로 생성
- PR 양식(.github/pull_request_template.md)과 PR 검사 워크플로(.github/workflows/pr-checks.yml): 에이전트 동기화, 비공개 경로, PR 제목 형식, CHANGELOG 갱신
- .gitignore(비공개 문서·샘플·평가 결과·모델·비밀값 제외), .gitattributes(줄바꿈 LF)
- M1 PR 나누기 계획(docs/설계.md 8장), 결정 D-023(Gradle Kotlin DSL)·D-024(M1 설치파일은 Actions 아티팩트로만)
- Gradle 프로젝트(Kotlin DSL, wrapper 9.9.2)와 빈 JavaFX 창(`./gradlew run`). JavaFX는 모듈 경로로 실행(D-025)
- 빌드 CI(.github/workflows/build.yml): 맥·윈도우에서 `./gradlew build`
- Project 모델과 project.json 저장/로드(`koja.core`), 단위 테스트(JUnit). JSON은 Jackson 3(D-027)
- 검수 화면(더미 데이터): 영상 + 행 표, 행 클릭 → 영상 이동, 한국어·일본어 편집 후 저장, 파일 메뉴(열기·더미 프로젝트 만들기·저장). 재생 영상 규칙 D-028, `javafx-media` 추가

### 변경
- 브랜치 흐름: `develop`(기본)으로 작업 PR을 squash, 릴리스는 `develop` → `main` merge commit(D-026). CI가 `develop` 푸시에도 돈다

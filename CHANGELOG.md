# 변경 이력

PR마다 `Unreleased`에 한 줄씩 추가한다. 릴리스할 때 `Unreleased`를 버전 번호와 날짜로 바꾼다.
분류: 추가 / 변경 / 수정 / 삭제

## Unreleased

### 추가
- 프로젝트 문서: README, AGENTS.md(모든 AI 도구 공통 작업 규칙), 설계(docs/설계.md), 결정 기록(docs/결정기록.md)
- 프로젝트 에이전트 3종(spec-reviewer, cross-platform-checker, eval-runner). 원본은 `.agents/agents/`, Claude Code·Codex·OpenCode용 파일은 `scripts/sync_agents.py`로 생성
- PR 양식(.github/pull_request_template.md)과 PR 검사 워크플로(.github/workflows/pr-checks.yml): 에이전트 동기화, 비공개 경로, PR 제목 형식, CHANGELOG 갱신
- .gitignore(비공개 문서·샘플·평가 결과·모델·비밀값 제외), .gitattributes(줄바꿈 LF)

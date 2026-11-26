## 무엇을, 왜 바꿨나

<!-- 한두 줄. 샘플 전사·실제 출연자 이름·요구사항 원문은 쓰지 않는다 (공개 저장소) -->

## 로컬 검사

- [ ] `./gradlew test` 통과 (코드 변경이 없으면 "해당 없음")
- [ ] `python3 scripts/sync_agents.py --check` 통과
- [ ] `git diff --stat`으로 `docs/private/`, `samples/`, `eval/results/` 파일이 없는 것 확인
- [ ] 맥에서 확인
- [ ] 윈도우 실기에서 확인 (CUDA 경로를 건드렸다면 필수. 안 했으면 사유)
- 평가 결과 요약: <!-- 해당 없으면 지운다. 수치만, 전사 예시는 넣지 않는다 -->

## 리뷰

<!-- 코드 변경이 없는 PR(문서·양식·CI 설정)은 "코드 변경 없음, 리뷰 생략"만 쓰고 아래는 지운다 -->

- 작성한 도구: <!-- Claude Code / Codex / Antigravity / OpenCode -->
- 리뷰한 도구: <!-- 작성한 도구와 달라야 한다. Codex가 쓴 PR은 Claude Code -->
- 리뷰 코멘트: <!-- PR 코멘트 링크. 못 돌렸으면 사유를 쓰고 draft로 둔다 -->
- [ ] 확실한 문제(버그·보안·데이터 손실)는 이 PR에서 고침
- [ ] 취향 지적은 사용자 확인 후 반영 여부 결정

## 문서

- [ ] `CHANGELOG.md` `Unreleased`에 추가 (CI 설정만 바꾸는 PR은 `skip-changelog` 라벨)
- [ ] 결정이 있었으면 `docs/결정기록.md`에 추가
- [ ] 바뀐 내용이 설명된 문서 갱신 (`docs/설계.md` / `AGENTS.md` / `README.md`)
- 문서 변경 없음 사유: <!-- 위 항목에 해당이 없을 때만 -->

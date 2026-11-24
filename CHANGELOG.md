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
- 설치파일 생성: `./gradlew jpackage`(맥 .dmg / 윈도우 .msi), CI가 OS별로 만들어 아티팩트로 올림(14일 보관, D-035)
- 가져오기(파일 메뉴): ffmpeg로 `audio.wav`(16kHz 모노)·`proxy.mp4`(720p H.264, 1초 키프레임) 생성, 진행률·취소·오류 표시, 빈 project.json 생성 후 검수 화면 열기(`koja.media`, `koja.runtime`). ffmpeg 방침 D-037, H.264 인코더 D-038
- 설치파일에 LGPL ffmpeg 9.0.2 번들: `scripts/bundle_ffmpeg.sh`(윈도우 BtbN 월말 빌드 다운로드, 맥 소스 빌드, SHA-256·GPL·인코더 검사), CI 캐시와 설치파일 안 ffmpeg 확인, 앱은 번들 ffmpeg를 먼저 찾음(D-042)
- M0 PoC 스크립트(`poc/`, Python + uv): WAV 추출, 정답 시트 파싱, 공통 VAD, STT 5종·화자 구분 3종 실행, 2회 방식 병합, 정답 시트 비교 평가, 윈도우 측정 안내(poc/WINDOWS.md)
- 번역 모델 비교 스크립트(`poc/translate.py`, `poc/mt_eval.py`): llama-server로 시트 한국어 번역, 행 ID 대응 검증, chrF·영문 용어·처리 시간·GPU 메모리. 설계 7.2에 수치, 윈도우 측정 절차 추가. 결정 D-041(로컬 번역 모델, 보류)
- 윈도우 담당자 PC용 테스트 묶음(`poc/windows/`, `poc/win_setup.py`, `poc/win_run.py`): 더블클릭 설치(uv·CUDA 빌드·모델 자동 다운로드, 이어 받기·sha256 검사) → 11단계 테스트(STT 2회·화자·번역 E4B/26B) → 숫자만 담은 결과 파일. 쉬운 설명서 포함. 의존성 `psutil` 추가(단계별 RAM 측정)
- 윈도우 실측 1차(RTX 3060 Ti 8GB): STT 2회·화자 구분 수치를 D-039·설계 7.1에 기록. 영상 이름이 PC마다 달라(`C0003.MP4`) 시트를 못 찾던 문제를 고침(시트 클립 → 영상 이름을 `samples/`에서 찾음, 시트 읽기는 재실행마다 다시 함)
- 결정 D-039(로컬 STT는 whisper.cpp 내장, VAD·화자 구분은 sherpa-onnx), D-040(STT 2회 방식, 행 시각은 단어 시간). 설계 7.1에 PoC 수치 요약

### 변경
- 윈도우 실기 확인은 데스크톱 한 대로 충분(D-043). AGENTS.md 크로스 플랫폼 규칙
- 설계 4단계를 "ja 구간 일본어 재전사"에서 "비한국어 구간 한국어 재전사"로, 행 `startMs`를 첫 단어 시간으로 바꿈(D-040). D-006(보류)을 D-039로 대체
- 라이선스를 두지 않기로 함(개인용, D-029). D-021(보류)을 대체
- 잡담 구간은 행으로 만들지 않고 무시 목록에 넣도록 절대 규칙 1을 고침(D-030). spec-reviewer 검증 문구도 같이 고침
- 인터뷰 영상은 질문자·답변자 발언만 행으로 남기고 통역·잡담은 무시(D-031, D-030 대체)
- 목적을 편집용 초벌 기록으로 명시: 빠짐없음·시간 정확도 우선, 정제는 추천까지만(D-032). 설계 1·2장, README
- 무시한 구간은 검수 화면에서 접어 두고 되살릴 수 있게(D-033, M3에서 구현)
- 사용 범위를 팀 내부 배포에서 개인 사용으로 바꿈(D-034, D-001 대체)
- .gitignore: samples/ 밖에 둔 영상·오디오 파일(mp4, mov, mkv, mxf, m4v, wav)도 제외
- 브랜치 흐름: `develop`(기본)으로 작업 PR을 squash, 릴리스는 `develop` → `main` merge commit(D-026). CI가 `develop` 푸시에도 돈다
- `--enable-native-access`에 `javafx.media` 추가(영상 재생 시 native access 경고 제거)

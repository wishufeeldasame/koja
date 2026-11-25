# AGENTS.md

이 저장소에서 일하는 AI 에이전트(Claude Code, Codex, OpenCode/oh-my-opencode, Antigravity)와 사람을 위한 작업 규칙. 네 도구 모두 이 파일을 읽는다(Claude Code는 `CLAUDE.md`의 `@AGENTS.md`로 불러온다).

## 프로젝트 한 줄

한국어(일부 일본어) 게임 인터뷰·리뷰 영상 → 타임라인·화자·한국어 전사 → 일본어 번역 → 앱 안에서 검수 → Google 시트로 내보내는 **윈도우·맥 설치형 데스크톱 앱**.

## 먼저 읽을 문서

| 문서 | 내용 |
|---|---|
| `docs/private/요구사항.md` | 원본 요구사항(**비공개, 로컬에만 있음**). 행 구성·질문 처리·검수 표시 규칙의 기준 |
| [docs/설계.md](docs/설계.md) | 확정된 구조, 데이터 모델, 처리 흐름, 마일스톤 |
| [docs/결정기록.md](docs/결정기록.md) | 무엇을 왜 정했고 무엇을 버렸는지 |
| `docs/private/샘플요청.md` | 평가용 샘플 조건(비공개) |

설계와 다른 결정을 해야 하면 코드보다 먼저 `docs/설계.md`를 고치고, `docs/결정기록.md`에 항목을 추가한다.

### 결정 기록 규칙

- **결정이 생기면 그 자리에서 바로 기록한다.** 사용자와 대화 중에 정한 것도 포함한다(기술 선택, 범위 포함/제외, 규칙, 이름 등). 작업을 끝낸 뒤로 미루지 않는다.
- 항목에는 결정, 이유, 버린 대안을 쓴다. 근거가 추측이면 "추정"이라고 적는다.
- 결정 내용은 지우거나 고치지 않고 추가만 한다. 결정이 바뀌면 새 항목을 쓰고, 이전 항목 상태를 `대체됨 → D-NNN`으로 바꾼다. 출처 추가와 오타 수정은 허용한다.
- 현재 구조에 영향이 있으면 `docs/설계.md`도 같이 고친다.

## 절대 규칙

1. **타임라인과 한국어 원문은 STT 결과에서만 나온다.** LLM 출력에 시간·원문 필드를 두지 않는다. LLM은 STT 구간 ID만 참조하고, 결과는 코드로 검증한다(모든 구간 ID가 행 또는 무시 목록에 순서대로 한 번씩 쓰였는지. 한국어 후보가 있는 2회 방식 구간만 일본어 질문 행과 한국어 행에 한 번씩 함께 쓸 수 있다, D-045). 인터뷰 영상에서는 질문자·답변자 발언만 행으로 만들고, 번역가 발언과 관계자 잡담은 무시 목록에 넣는다(D-031).
2. **모르면 비우고 표시한다.** 화자 이름이나 들리지 않는 내용을 추측해 채우지 않는다. `SPEAKER_UNSURE` / `TEXT_UNSURE` 플래그를 단다.
3. **이 저장소는 공개(Public)다. 비공개 자료는 커밋하지 않는다.**
   - 비공개 문서는 `docs/private/`, 샘플 영상·정답 시트는 `samples/`, 평가 결과는 `eval/results/`에 둔다. 셋 다 `.gitignore` 대상이다.
   - 커밋·PR·이슈·코드 주석에 샘플 전사 내용, 실제 출연자 이름, 요구사항 원문을 옮기지 않는다. 예시는 `선수A`, `화자 미확인 1` 같은 가명으로 쓴다.
   - 샘플마다 외부 AI 전송 허용 여부를 `samples/README.md`에 적는다. 적혀 있지 않은 샘플은 **금지**로 본다. 금지된 샘플을 외부 API로 보내지 않는다.
4. **API 키·OAuth 토큰은 저장소와 로그에 남기지 않는다.** OS 키체인에 둔다.
5. **GPU는 한 번에 한 단계만 쓴다.** 8GB VRAM 카드 기준으로 맞춘다. 단계가 끝나면 엔진을 내린다.

## 기술스택

- Java 25 LTS, JavaFX, Gradle, jpackage
- 내장 바이너리: ffmpeg(LGPL 빌드), llama.cpp `llama-server`, whisper.cpp `whisper-server`(둘 다 Win CUDA / Mac Metal)
- 로컬 STT: whisper-server(large-v3-turbo), 2회 방식(D-039, D-040). VAD·화자 구분: sherpa-onnx Java(CPU)
- Google Sheets API v4 + OAuth Desktop(루프백 + PKCE), 스코프 `drive.file`

새 의존성은 표준 라이브러리나 이미 있는 의존성으로 안 될 때만 추가하고, 이유를 커밋 메시지에 쓴다.

## 디렉터리

지금 있는 것:

```
AGENTS.md       작업 규칙 (모든 AI 도구 공통)
CLAUDE.md       Claude Code용. AGENTS.md를 불러오기만 함
CHANGELOG.md    변경 이력
README.md       프로젝트 소개
docs/           공개 문서 (설계, 결정 기록)
  private/      비공개 문서 (커밋 금지)
.agents/agents/ 프로젝트 에이전트 원본
.claude/ .codex/ .opencode/   도구별 에이전트 파일 (생성물)
scripts/        저장소 관리 스크립트 (sync_agents.py, bundle_ffmpeg.sh)
.github/        PR 양식, CI (pr-checks.yml, build.yml)
settings.gradle.kts, gradlew, gradle/   Gradle 빌드 (Kotlin DSL)
app/            JavaFX 앱 (모듈 `koja`, 패키지 `koja.*`)
  build.gradle.kts
  src/main/java/module-info.java
  src/main/java/koja/ui    화면 (진입점 KojaApp)
poc/            M0 비교 실험 스크립트 (Python + uv, 앱과 분리). 사용법 poc/README.md, 윈도우 측정 poc/WINDOWS.md
  src/main/java/koja/core  Project 모델, project.json 저장/로드 (ProjectStore)
  src/main/java/koja/media    ffmpeg 호출 (가져오기: audio.wav, proxy.mp4)
  src/main/java/koja/runtime  ffmpeg 위치, OS별 H.264 인코더
  src/test/java/           JUnit 테스트
```

M1 이후 생길 것:

```
app/src/main/java/koja/
  core     파이프라인, 검증기
  engine   Transcriber / Diarizer / LlmClient / FrameReader 와 구현
  media    프레임 추출 등 ffmpeg 호출 추가
  runtime  내장 바이너리(번들 ffmpeg 포함)·모델 관리
  export   Google Sheets
eval/           평가 스크립트와 결과 (results/ 아래 날짜별)
samples/        샘플 영상·정답 시트, README.md(외부 전송 허용 표) (커밋 금지)
```

## 엔진 추가 규칙

- 단계별 인터페이스(`Transcriber`, `Diarizer`, `LlmClient`, `FrameReader`)를 구현한다. 로컬과 외부 구현은 같은 인터페이스를 쓴다.
- 엔진은 단계 결과 파일(`stt.json`, `diar.json`, `rows.json` …)만 만든다. 다른 단계 파일을 고치지 않는다.
- 외부 API 구현은 모델 이름과 가격을 하드코딩하지 않고 설정으로 둔다. 모델명·가격은 자주 바뀐다.

## 크로스 플랫폼

맥에서 개발하고 윈도우에서 주로 쓴다. 맥에서 돌아간다고 윈도우에서 돌아가는 게 아니다.

- 경로는 `java.nio.file.Path`로만 다룬다. 구분자를 문자열로 붙이지 않는다. 한글·공백이 든 경로를 가정한다.
- 파일·프로세스 입출력은 UTF-8을 명시한다. 윈도우 기본 인코딩에 기대지 않는다.
- 외부 프로세스는 `ProcessBuilder` + 인자 리스트로 실행한다. 셸 문자열을 조립하지 않는다.
- OS·GPU별 분기(CUDA / Metal, 실행파일 확장자, 데이터 폴더)는 `runtime` 패키지 한곳에 모은다.
- CUDA 경로는 맥에서 테스트할 수 없다. 윈도우 실기(데스크톱 한 대면 충분, D-043)에서 확인했는지 PR에 적는다.

## 빌드·실행·테스트

지금 쓸 수 있는 명령:

```bash
python3 scripts/sync_agents.py --check   # 에이전트 생성 파일이 원본과 맞는지
./gradlew run        # 앱 실행
./gradlew test       # 테스트 (ffmpeg가 PATH에 없으면 가져오기 테스트는 건너뜀)
./gradlew build      # 컴파일 + 테스트 (CI build.yml이 맥·윈도우에서 돌림)
scripts/bundle_ffmpeg.sh   # 설치파일에 넣을 LGPL ffmpeg를 app/build/ffmpeg에 (맥: 소스 빌드 약 1분, 윈도우: Git Bash에서 다운로드)
./gradlew jpackage  # 현재 OS용 설치파일을 app/build/jpackage에 (맥 .dmg / 윈도우 .msi). 버전은 -PappVersion=1.2.3. app/build/ffmpeg가 없으면 실패
```

- 윈도우에서는 `gradlew.bat`을 쓴다.
- 윈도우에서 `.msi`를 직접 만들려면 [WiX Toolset](https://wixtoolset.org/)이 설치되어 있고 PATH에 있어야 한다(jpackage가 요구). 맥 `.dmg`는 추가 도구가 필요 없다. 맥에서 ffmpeg를 빌드하려면 Xcode 명령줄 도구가 있어야 한다.
- JDK 25가 없어도 된다. Gradle 툴체인이 처음 빌드할 때 받아 `~/.gradle/jdks`에 둔다. Gradle을 실행할 JDK(17 이상)는 있어야 한다.

- 새 로직(파서, 검증기, 시간 변환, 행 묶기)에는 작은 단위 테스트를 붙인다.
- STT·번역 품질 변화는 `eval/` 평가로 확인하고 결과 파일을 남긴다.

## Git·PR

- 브랜치는 둘이다. `develop`(기본 브랜치, 개발 통합)과 `main`(릴리스). 둘 다 직접 푸시할 수 없다(ruleset).
- 작업 브랜치는 `develop`에서 만들고, `develop`으로 PR을 열어 **squash** 병합한다.
- 릴리스는 `develop` → `main` PR을 **merge commit**으로 병합한다. squash나 GitHub rebase 병합은 커밋을 새로 만들어 두 브랜치 이력이 갈라진다. 릴리스 PR에서 `CHANGELOG.md`의 `Unreleased`를 버전으로 바꾼다.
- 작업 브랜치 이름은 `<종류>/<짧은 설명>`, 예: `feat/review-table`, `docs/decision-log`.
- 커밋은 작은 단위로 하고, 메시지는 Conventional Commits 형식으로 쓴다. squash 병합 커밋 메시지는 PR 제목이므로 PR 제목도 같은 형식으로 쓴다.
- PR 전에 **작성한 도구와 다른 모델**로 코드 리뷰를 돌린다. 예: Claude Code에서 작업했으면 Codex로 리뷰(`codex-review` 스킬, 안 되면 `/code-review`), Codex에서 작업했으면 Claude Code `/code-review` 등 다른 도구로. 어떤 리뷰를 돌렸는지, 못 돌렸으면 그 사실을 PR 설명에 쓴다.
- PR 설명에는 다음을 적는다: 맥/윈도우 중 어디서 확인했는지, 평가를 돌렸다면 수치 요약(전사 예시는 넣지 않음). 양식은 `.github/pull_request_template.md`.

### PR마다 문서화

PR 하나에 코드와 문서를 함께 넣는다. 문서를 다음 PR로 미루지 않는다.

- **`CHANGELOG.md`**: `Unreleased`에 이 PR의 변경을 한 줄씩 추가한다(추가 / 변경 / 수정 / 삭제).
- **바뀐 내용이 설명된 문서**를 같이 고친다.
  - 구조·데이터 모델·처리 흐름 → `docs/설계.md`
  - 빌드·실행·테스트 명령, 작업 규칙 → `AGENTS.md`
  - 사용자가 보는 기능, 설치·실행 방법 → `README.md`
  - 결정이 있었으면 → `docs/결정기록.md`
- 고칠 문서가 없으면 PR 설명에 "문서 변경 없음"과 이유를 쓴다.

### PR 검사 (CI, `.github/workflows/pr-checks.yml`)

아래가 실패하면 병합하지 않는다.

| 검사 | 실패 조건 | 예외 |
|---|---|---|
| 에이전트 동기화 | `scripts/sync_agents.py --check` 실패 | 없음 |
| 비공개 경로 | `docs/private/`, `samples/`, `eval/results/` 파일이 커밋돼 있음 | 없음 |
| PR 제목 | Conventional Commits 형식이 아님 | 없음 |
| CHANGELOG | PR에서 `CHANGELOG.md`를 고치지 않음 | `skip-changelog` 라벨 (CI 설정만 바꾸는 PR 등) |

결정 기록·설계 문서 갱신과 PR 양식 체크박스는 기계로 판단할 수 없어서 리뷰에서 확인한다.

## 프로젝트 에이전트

원본은 `.agents/agents/<이름>.md` 하나다. 도구별 파일은 원본을 가리키는 얇은 껍데기이고 `scripts/sync_agents.py`가 만든다.

| 도구 | 읽는 위치 | 원본/생성 |
|---|---|---|
| Antigravity (`agy`) | `.agents/agents/*.md` | **원본** |
| Claude Code | `.claude/agents/*.md` | 생성 |
| Codex | `.codex/agents/*.toml` | 생성 |
| OpenCode / oh-my-opencode | `.opencode/agents/*.md` | 생성 |

- 에이전트를 고치거나 추가할 때는 원본만 고치고 `python3 scripts/sync_agents.py`를 돌린다. 생성 파일을 직접 고치지 않는다.
- 커밋 전에 `python3 scripts/sync_agents.py --check`가 통과해야 한다(생성 파일이 원본과 맞는지 확인).
- 원본 frontmatter에는 `name`(파일명과 같게), `description`만 쓴다. 도구별 권한·모델은 스크립트가 넣는다.

| 이름 | 언제 쓰나 |
|---|---|
| `spec-reviewer` | 파이프라인·데이터 모델·내보내기 변경이 요구사항과 설계 규칙을 지키는지 검토 |
| `cross-platform-checker` | 맥에서 짠 코드가 윈도우에서 깨질 곳(경로, 인코딩, 프로세스, GPU 분기) 점검 |
| `eval-runner` | 샘플로 STT·화자·번역 품질을 측정하고 결과를 `eval/results/`에 기록 |

코드 리뷰·디버깅 같은 일반 작업은 각 도구의 기본 기능이나 사용자 전역 에이전트를 쓴다. 이 저장소에는 프로젝트 규칙이 필요한 에이전트만 둔다.

## 언어

문서와 사용자 응답은 한국어. 코드 식별자는 영어. 주석은 코드로 드러나지 않는 이유만 쓴다.

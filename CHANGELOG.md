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
- 윈도우 실측: 3060 Ti 8GB(STT 2회·화자 구분), 3080 10GB(11단계 전부, 번역 E4B·26B 포함) 수치를 D-039·D-041·설계 7장에 기록. 설계 7.2 맥 chrF 샘플 순서 정정. 결정 D-044(번역 기본 Gemma 4 E4B, 26B-A4B는 선택 설치, D-041 대체) 영상 이름이 PC마다 달라(`C0003.MP4`) 시트를 못 찾던 문제를 고침(시트 클립 → 영상 이름을 `samples/`에서 찾음, 시트 읽기는 재실행마다 다시 함)
- 구조화 모델 비교 스크립트(`poc/structure.py`, `poc/struct_eval.py`): STT 구간·화자 → llama-server로 rows·무시 목록, 구간 ID 검증·오류 알려 재시도, 문장 조각 분할 선택(`KOJA_SPLIT`). 시트 행 재현율·시간·행 경계·일본어 질문·무시·검증·처리 시간·GPU 메모리. 설계 7.3에 Gemma 4 E4B·Qwen3.5-9B 수치. `translate.Server`에 컨텍스트 길이 인자. 결정 D-045(한국어 후보가 있는 구간은 일본어 질문 행과 한국어 행에 함께 쓸 수 있음, 설계 2장·AGENTS.md 절대 규칙 1·spec-reviewer 보완), D-046(구조화 기본 Gemma 4 E4B, 설계 6장)
- 설계 4장에 시트 구성(9단계 내보내기): A タイムライン · B 話者 · C 韓国語 · D 日本語 열과 `rows` 필드 대응, 질문·플래그 표시 규칙
- 결정 D-039(로컬 STT는 whisper.cpp 내장, VAD·화자 구분은 sherpa-onnx), D-040(STT 2회 방식, 행 시각은 단어 시간). 설계 7.1에 PoC 수치 요약
- `koja.engine` 단계 엔진 계약(`Engine`, `Transcriber`, `Diarizer`, `LlmClient`, `FrameReader`)과 순차 파이프라인 골격(`Pipeline`: STT → 화자 구분 → 구간에 화자 붙이기, 끝난 단계 건너뛰기, D-049)
- 단계 결과 파일 `stt.json`·`diar.json` 읽기·쓰기와 검사(`koja.core.StageStore`, D-048). `project.json`에 `ignoredSegIds`, STT 구간 단어 시간 `words`·한국어 후보 `ko` 선택 항목 추가. 옛 파일은 그대로 읽는다(D-048)
- `koja.runtime` 공통 인프라: OS별 데이터·모델 경로(`AppPaths`), Hugging Face 모델 다운로드·Range 이어 받기·취소·SHA-256 검증·원자적 이동(`ModelStore`, D-050), `whisper-server`/`llama-server` 공용 로컬 HTTP 서버 수명주기(`LocalServer`: 동적 포트·프록시 우회·헬스체크·프로세스 트리 정리, D-051)와 단위 테스트
- 구글 로그인(`koja.export.GoogleAuth`): OAuth Desktop 루프백(127.0.0.1) + PKCE, 스코프 `drive.file`, 클라이언트는 환경 변수(D-058). 리프레시 토큰은 `koja.runtime.SecretStore`(맥 Keychain / 윈도우 DPAPI, D-056)에 저장. `drive.file` 폴더 확인용 `./gradlew oauthPoc`. 실측 결과 사용자가 고른 기존 폴더(내 드라이브)에도 시트 생성 가능(D-057)
- `prompt-engineer` 프로젝트 에이전트 추가(`.agents/agents/prompt-engineer.md`, D-015) 및 도구별 동기화 생성물, `AGENTS.md` 에이전트 표 갱신
- M3 구조화 스펙 확정 및 재측정(`poc/structure.py`, `poc/struct_eval.py`, `poc/README.md`, 설계 4·7.3·9장): 1단계 영상 유형·화자 역할 분석 + 2단계 구간 1:1 평탄 고정 순서 JSON 스키마·과병합 방지·기본 플래그 보강(D-053), 1차 언어 인식 문장·화자·한국어 절 분할(`clause`) 및 기본 VAD 유지(D-054), 최대 24구간·3분·앞뒤 6구간(30초)·8K 컨텍스트(D-055). Gemma 4 E4B 검증 1회 통과 100%(42/42·51/51), 대체 청크 0 달성

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

### 삭제
- PoC의 선택 의존성 `torch`(qwen-asr, pyannote.audio)와 그 실행 경로(`stt.py` qwen/univa, `diar.py` pyannote). Dependabot 경고 49건(Pillow·transformers·accelerate)이 모두 여기서 나왔다(D-047)

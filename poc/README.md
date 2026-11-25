# M0 PoC: 로컬 STT·화자 구분 비교

앱 코드와 분리된 Python 실험 스크립트다(설계 7장, D-006). 샘플·산출물·결과는 커밋하지 않는다.

- 입력: `samples/` (영상, `sheet.xlsx`)
- 중간 산출물: `eval/work/` (WAV, VAD 구간, 엔진 출력, 모델)
- 결과: `eval/results/<날짜>/` (요약·행별 비교. 전사 내용이 들어 있어 비공개)

worktree에서 돌릴 때는 샘플이 있는 원래 체크아웃을 `KOJA_DATA_ROOT`로 지정한다.

```bash
cd poc
uv sync --extra torch                     # Qwen3-ASR·pyannote까지 비교할 때. 결정 경로만이면 uv sync
export KOJA_DATA_ROOT=/path/to/koja       # samples/가 있는 체크아웃
uv run python extract_audio.py            # 영상 → 16kHz mono WAV (한 번만)
uv run python parse_sheet.py              # 정답 시트 → eval/work/sheet/<샘플>.json
uv run python vad.py C0003-002 C0004-001 C0008-003            # 공통 VAD 구간 → eval/work/vad/
uv run python vad.py --split 10 C0003-002 C0004-001 C0008-003 # 10초 넘는 구간 강제 분할 → vad10/
uv run python stt.py qwen C0003-002 ...   # 엔진: qwen, univa, whisper, sherpa06, sherpa17 (-ko: 한국어 고정)
KOJA_VAD=vad10 uv run python stt.py qwen ...                  # 결과 폴더 qwen@vad10
KOJA_VAD=vad10 uv run python stt.py qwen-ko ...               # 같은 구간을 한국어 고정으로
uv run python merge_2pass.py qwen@vad10 qwen-ko@vad10 qwen-2pass@vad10   # 2회 방식
uv run python diar.py sherpa-campplus@0.9 C0003-002 ...  # pyannote, sherpa-eres2net, sherpa-campplus (@군집 기준값)
uv run python evaluate.py qwen-2pass@vad10 pyannote      # STT 결과 [화자 결과]
uv run python translate.py e4b C0003-002 ...           # 번역: e4b, q9b, g26b → eval/work/mt/<모델>/
uv run python mt_eval.py e4b q9b g26b                    # chrF·처리 시간·GPU 메모리
uv run python structure.py e4b C0003-002 ...           # 구조화: whisper-2pass + sherpa-campplus@0.9 → eval/work/struct/<모델>/
KOJA_SPLIT=1 uv run python structure.py e4b ...          # 구간을 문장 조각으로 나눈 뒤 구조화 → struct/<모델>@sent/
uv run python struct_eval.py e4b e4b@sent q9b q9b@sent  # 행 재현율·시간·행 경계·질문·무시·검증·처리 시간
./run_all.sh stt qwen whisper ...         # 엔진을 하나씩 돌리며 로그·최대 메모리 기록(맥)
```

처리 시간은 스크립트가 `meta.json`에, 최대 메모리는 `run_all.sh`가 `/usr/bin/time -l`(맥: peak memory footprint)로 잰다. whisper는 서버 프로세스 값이 `eval/work/stt/<엔진>/server_time.txt`에 남는다. 윈도우 실기 테스트는 담당자 PC용 묶음으로 한다: [WINDOWS.md](WINDOWS.md).

**2회 방식**: 자동 언어로 한 번 돌리고, `ja` 등 한국어가 아닌 구간만 한국어로 고정해 한 번 더 돌려 둘 다 남긴다. 한·일이 섞인 구간에서 자동 언어 판정이 한국어 답변까지 일본어로 번역해 버리는 것을 막는다.

## 모델

| 엔진 | 모델 | 받는 곳 |
|---|---|---|
| qwen | Qwen/Qwen3-ASR-1.7B + Qwen/Qwen3-ForcedAligner-0.6B | Hugging Face 캐시 |
| univa | TeamUNIVA/qwen3_asr_1.7b_ko_beta + 같은 정렬기 | Hugging Face 캐시 |
| whisper | ggml-large-v3-turbo.bin, `whisper-server`(brew `whisper-cpp`) | `eval/work/models/` |
| sherpa06 | sherpa-onnx-qwen3-asr-0.6B-int8 (k2-fsa 릴리스) | `eval/work/models/` |
| sherpa17 | ModelScope zengshuishui/Qwen3-ASR-onnx `model_1.7B` int8 | `eval/work/models/sherpa-qwen3-1.7B/` |
| VAD | silero_vad.onnx | `eval/work/models/` |
| sherpa 화자 | pyannote segmentation-3.0 onnx + 3D-Speaker 임베딩 | `eval/work/models/diar/` |
| pyannote | pyannote/speaker-diarization-community-1 (이용 조건 동의·HF 토큰 필요) | Hugging Face 캐시 |
| 번역 e4b / q9b / g26b | unsloth gemma-4-E4B-it / Qwen3.5-9B / gemma-4-26B-A4B-it GGUF Q4_K_M, `llama-server`(brew `llama.cpp`) | `eval/work/models/llm/` |

## 평가 방법 (D-032 순서)

1. **시트 행 재현율**: 시트 한국어 문장을 정규화(공백·문장부호 제거)해 시트 시각 ±90초 안 STT 글자와 문자 정렬한다. 문장 글자의 60% 이상이 정렬되면 "포함".
2. **시간 오차**: 포함된 행에서 첫 정렬 글자의 단어 시간(단어 시간이 없으면 구간 안 비례 배분)과, 그 글자가 든 STT 구간 시작을 각각 시트 시각과 비교한다. 1초·2초 이내 비율.
3. **화자 일치율**: 포함된 행이 든 구간의 화자가 그 영상 선수 화자(최빈값)와 같은 비율.
4. **일본어 질문**: 시트 질문 앞뒤 답변 사이에 `ja` 구간이 있는지, 질문 글자가 그 구간에 얼마나 들어 있는지.

시트는 편집용 최종본이라 시트에 없는 결과 행은 오류로 세지 않는다.

## 번역 평가 방법

입력은 시트 한국어 행, 정답은 시트 일본어(다듬은 최종본이라 참고 지표). chrF는 문자 n-gram 1~6, beta 2, 공백 제외로 말뭉치 단위로 합산한다. 영문 고유명사 유지율은 시트 일본어에 있는 영문 단어가 번역에도 있는 비율이다. GPU 메모리는 맥 IOAccelerator 사용량을 서버 시작 전과 비교한 최대값(시스템 전체 값이라 추정)이다. 1시간 환산은 whisper 한국어 고정 STT의 시간당 글자 수로 계산한다.

## 구조화 평가 방법

`structure.py`는 STT 구간(2회 방식이면 한국어 후보 포함)·화자·시각을 4분 청크(앞뒤 30초 문맥)로 llama-server에 보내 `rows`(kind, segIds)와 `ignoredSegIds`만 JSON 스키마로 받는다. 청크 끝에 걸친 행은 확정하지 않고 다음 청크에서 다시 묻는다. 검증: 모든 구간 ID가 한국어 행·일본어 질문 행·무시 목록 중 어딘가에 오름차순으로 쓰였는지, 각 목록 안에서는 한 번씩인지. 한국어 후보가 있는 구간만 질문 행과 한국어 행에 함께 쓸 수 있다. 틀리면 오류를 알려 주고 3번까지 묻고, 그래도 틀리면 그 청크 구간을 하나씩 LINE으로 남긴다.

`struct_eval.py` 지표(D-032 순서, 정렬은 `evaluate.py`와 같음):

1. **시트 행 재현율**: 시트 한국어 행이 LINE·QUESTION_KO 행의 한국어(±90초)에 60% 이상 정렬되는 비율.
2. **시간 1초 이내**: 시트 행 글자를 가장 많이 담은 출력 행(대표 행)의 첫 단어 시간과 시트 시각의 차이.
3. **행 경계 1:1**: 시트 행에 정렬된 글자의 80% 이상이 대표 행에 있고, 그 행이 다른 시트 행의 대표 행이 아닌 비율.
4. **일본어 질문**: 시트 질문 창(앞뒤 답변 시각 ±5초) 안 QUESTION_JA 행의 일본어에 질문 글자가 30% 이상 정렬되면 검출. 어떤 시트 질문과도 정렬되지 않은 QUESTION_JA 행은 오검출(참고).
5. **무시**: 시트 행 글자가 30% 이상 든 구간이 무시된 비율. 통역 처리는 화자로 대신 본다(추정): 시트 행이 든 구간의 최빈 화자를 선수, 시트 질문이 든 구간의 최빈 화자를 질문자, 나머지를 통역·관계자로 보고 각 구간이 한국어 행·질문 행·무시 중 어디로 갔는지 센다.
6. **검증·자원**: 청크 1회 통과율, 재시도, 대체(LINE으로 남긴) 청크, 1시간 환산 처리 시간(오디오 길이 기준), GPU 메모리(맥 IOAccelerator, 추정).

`seg`·`seg@sent`는 기준선(구간·문장 조각 하나 = LINE 행 하나)이다. 문장 조각(`KOJA_SPLIT=1`)은 한국어 쪽 단어(한국어 후보가 있으면 후보)를 문장 끝 부호 단어 뒤에서 나누고, 1차 단어는 시작 시각이 든 조각에 넣는다.

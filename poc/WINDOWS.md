# 윈도우 실기 측정 (3060 Ti 8GB, 3080 10GB)

결정한 경로(D-035)만 잰다: whisper.cpp `whisper-server`(CUDA) 2회 방식 STT + sherpa-onnx campplus 화자 구분(CPU).
맥 수치와 비교할 값은 **처리 시간(RTF)**, **최대 VRAM**, **최대 RAM**이다. 정확도는 맥과 같은 모델이라 다시 재지 않아도 되지만, 결과 파일이 나오므로 평가도 한 번 돌린다.

## 준비 (한 번만)

1. 필요한 것: [uv](https://docs.astral.sh/uv/), Git, NVIDIA 드라이버(CUDA 12.4 이상 지원), ffmpeg(`winget install Gyan.FFmpeg`).
2. 저장소를 받고 `poc`에서 의존성을 설치한다(torch 계열은 빼고).

   ```powershell
   cd koja\poc
   uv sync
   ```

3. 샘플 영상 3편과 `sheet.xlsx`를 `koja\samples\`에 둔다(커밋 금지 폴더).
4. whisper.cpp 릴리스(https://github.com/ggml-org/whisper.cpp/releases)에서 `whisper-bin-win-cuda-12.4.0-x64.zip`을 받아 압축을 푼다(2026-11 기준 최신 빌드 b5907). 안에 `whisper-server.exe`가 있는지 확인한다.
5. 모델을 `koja\eval\work\models\`에 둔다. 맥에서 쓴 파일을 그대로 복사해도 된다.
   - `ggml-large-v3-turbo.bin` (1.6GB): https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin
   - `silero_vad.onnx`: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
   - `diar\sherpa-onnx-pyannote-segmentation-3-0\model.onnx`: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 (압축 해제)
   - `diar\campplus_zh_en.onnx`: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx (이름을 바꿔 저장)

## 실행

PowerShell에서 `koja\poc` 기준. `KOJA_DATA_ROOT`는 `samples\`가 있는 저장소 경로다.

```powershell
$env:KOJA_DATA_ROOT = (Resolve-Path ..).Path
$env:WHISPER_SERVER = "C:\path\to\whisper-server.exe"
$S = "C0008-003","C0003-002","C0004-001"
uv run python extract_audio.py
uv run python parse_sheet.py
uv run python vad.py @S
```

VRAM은 별도 창에서 1초마다 기록한다(측정이 끝나면 Ctrl+C).

```powershell
New-Item -ItemType Directory -Force ..\eval\work\logs | Out-Null
nvidia-smi --query-gpu=timestamp,memory.used --format=csv -l 1 > ..\eval\work\logs\vram.csv
```

STT 두 번(자동 언어, 한국어 고정)과 병합, 화자 구분, 평가:

```powershell
uv run python stt.py whisper @S
uv run python stt.py whisper-ko @S
uv run python merge_2pass.py whisper whisper-ko whisper-2pass
uv run python diar.py 'sherpa-campplus@0.9' @S
uv run python evaluate.py whisper-2pass 'sherpa-campplus@0.9'
```

`whisper-ko`는 측정 편의상 모든 구간을 다시 돈다. 실제 2회 방식은 `ja`로 잡힌 구간(맥 샘플 기준 54~68%)만 다시 돌리므로 처리 시간은 `whisper` RTF + `whisper-ko` RTF × 0.6으로 환산한다.

최대 RAM은 실행 중 작업 관리자 "세부 정보" 탭의 `whisper-server.exe`·`python.exe` 메모리(최대 작업 집합 열을 켜서)로 본다.

## 보낼 값

장비마다 아래만 알려 주면 된다(전사 내용은 보내지 않는다).

- `eval\work\stt\whisper\meta.json`, `eval\work\stt\whisper-ko\meta.json`, `eval\work\diar\sherpa-campplus@0.9\meta.json`의 `rtf`
- `vram.csv`의 최대 `memory.used`와 측정 전 유휴값
- 작업 관리자의 최대 작업 집합(`whisper-server.exe`, 화자 구분 중 `python.exe`)
- `evaluate.py` 출력 세 줄(재현율·시간 수치만 있다)

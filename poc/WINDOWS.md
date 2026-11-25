# 윈도우 실기 테스트 (담당자 PC: 3060 Ti 8GB, 3080 10GB)

이 테스트는 두 가지를 확인한다.
- 맥에서 고른 경로가 실제로 쓸 윈도우 PC에서 돌아가는지, 얼마나 걸리고 메모리를 얼마나 쓰는지
  - STT: whisper.cpp 2회 방식(D-039, D-040)
  - 화자 구분: sherpa-onnx CAM++
- 번역 모델 결정(D-041)에 필요한 Gemma 4 E4B와 26B-A4B의 속도·VRAM

담당자는 개발 도구 없이 더블클릭만 하도록 **묶음(zip)** 으로 전달한다. 담당자용 설명은 [windows/0_READ_ME_FIRST.txt](windows/0_READ_ME_FIRST.txt)에 있다.

## 묶음 만들기 (개발자)

```bash
cd poc
uv run python windows/make_package.py      # → eval/work/dist/koja-windows-test.zip (수십 KB)
```

- HEAD에 커밋된 `poc/` 파일 내용만 넣는다. `poc/`에 커밋하지 않은 변경이 있으면 멈춘다.
- 모델·실행파일·영상은 넣지 않는다.
  - 모델과 실행파일: 담당자 PC에서 `1_setup.bat`이 받는다.
  - 영상과 `sheet.xlsx`: 따로 전달하고 담당자가 `samples\`에 넣는다. 외부 AI 전송 금지 자료라 AI 서비스를 거치지 않는 경로(USB, 직접 전달 등)로 준다.

## 묶음이 하는 일

| 파일 | 하는 일 |
|---|---|
| `1_setup.bat` → `win_setup.py` | ① uv가 없으면 설치한다. uv·Python·캐시는 모두 묶음 폴더의 `tools\` 안에 두고 PATH는 바꾸지 않아서, 폴더를 지우면 전부 지워진다. ② `uv run`이 Python 3.12와 패키지를 설치한다. ③ 그래픽카드, 드라이버(CUDA 12.4 이상), 디스크 공간을 확인한다. ④ 실행파일과 모델 약 24GB를 받는다. 이어 받기와 sha256 검사를 하고, 다시 실행하면 받은 것은 건너뛴다. 폴더가 OneDrive 안이면 멈춘다 |
| `2_run_test.bat` → `win_run.py` | 11단계를 차례로 돌린다: 소리 추출 → VAD → whisper 자동 언어 → whisper 한국어 고정 → 2회 병합 → 화자 구분(CAM++ 0.9) → 시트 → STT 평가 → 번역 E4B → 번역 26B → 번역 평가. 앞 단계가 실패하면 뒤 단계는 건너뛴다. 끝난 단계는 다시 하지 않는데, 영상·시트가 바뀌었거나 `--again`이면 중간 결과를 지우고 처음부터 한다. 테스트 중에는 절전을 막는다 |
| `RESULT_send_this.txt` | 숫자만 모은다: 단계별 성공 여부, 걸린 시간, 최대 VRAM, 최대 RAM, RTF, 평가 수치, 번역 서버의 층·버퍼 배치. 실패한 단계는 오류 종류만 적는다. 원본 로그에는 영상 파일 경로가 들어갈 수 있어서 `eval\work\logs\`에만 남긴다 |

- 최대 VRAM은 nvidia-smi로 1초마다 잰 **시스템 전체** 값이다. 단계 시작 전 값도 같이 적는다.
- 최대 RAM은 단계 프로세스와 서버 프로세스(`whisper-server.exe`, `llama-server.exe`)의 RSS 합이다.

**받는 실행파일**(고정 버전):
- whisper.cpp b5907 `whisper-bin-win-cuda-12.4.0-x64.zip`
- llama.cpp b12655 `llama-b12655-bin-win-cuda-12.4-x64.zip` + `cudart-llama-bin-win-cuda-12.4-x64.zip`
- ffmpeg GyanD 9.1 essentials(GPL 빌드, 테스트 전용. 앱 번들은 LGPL 빌드)

모델 파일은 맥 PoC에서 쓴 것과 sha256이 같다.

**번역 서버 설정**:
- 1차: `KOJA_LLAMA_ARGS="-ngl auto -lv 4"`로 띄운다.
  - `translate.py`에 박힌 `-ngl 99`를 뒤의 인자가 덮어쓴다. 맥에서 `-ngl 99 -ngl 0` → GPU 층 0/43으로 확인했다.
  - 그러면 llama-server `--fit`(기본 on)이 VRAM에 맞춰 층과 MoE 전문가를 CPU로 나눈다.
- 2차: 1차가 실패하면 `-ngl 99 --cpu-moe`(전문가 전부 CPU)로 한 번 더 시도한다.
- 어느 설정으로 돌았는지, GPU·CPU에 무엇이 올라갔는지는 결과 파일에 남는다.

## 확인한 것 / 못 한 것

- **맥에서 확인한 것**:
  - 설치 스크립트(실제 파일로):
    - 이어 받기(206 응답 확인), 끝까지 받은 `.part`는 요청 없이 바로 검사, 응답이 짧게 끊기면 이어 받기
    - 해시 불일치 때 멈춤, 마커만 남은 경우 다시 받기
    - ffmpeg·llama zip(`llama-server.exe` 포함 53개 파일)·tar.bz2 압축 해제
  - 실행기:
    - 단계 실행, 측정, 결과 파일 작성(맥 결과 데이터로)
    - 가짜 단계로 선행 실패 건너뛰기, 재실행, 영상 추가 시 초기화, 26B 재시도 확인
  - 번역: `-ngl auto -lv 4`로 실제 번역 1편, 배치 로그 수집
  - 윈도우용 휠(sherpa-onnx, sherpa-onnx-core, soundfile, psutil, numpy) 존재
- **윈도우 3060 Ti에서 확인한 것**(1차, 2026-11-24): bat 실행, uv 설치, 다운로드·압축 해제, whisper-server CUDA, sherpa 화자 구분, nvidia-smi·RAM 측정, 결과 파일 작성.
  - 1차에서 정답 비교와 번역이 `FileNotFoundError`로 실패했다. 담당자 PC의 영상 이름이 `C0003.MP4`라 맥 이름(`C0003-002`)을 고정해 둔 시트 해석이 시트 파일을 만들지 못했다. 지금은 시트 클립 번호로 `samples/`의 영상 이름을 찾는다.
  - 결과 파일의 `cuda` 값이 비어 있었다(`nvidia-smi` 머리글에서 CUDA 버전을 못 읽음, 원인 미확인). whisper CUDA 빌드는 VRAM 2GB를 쓰며 돌았으므로 드라이버는 맞다(추정).
- **윈도우 3080에서 확인한 것**(2026-11-25): 11단계 모두 성공. 번역은 두 모델 모두 1차 설정(`-ngl auto`)으로 돌았다. 26B는 31/31층을 GPU에 올리고 MoE 전문가 일부를 CPU에 뒀다(VRAM 8.1GB, RAM 19.5GB). 수치는 `docs/설계.md` 7장.
  - 결과 파일의 번역 서버 배치는 앞 16줄만 남겨서 26B의 CUDA 버퍼 줄이 잘렸다.
- **윈도우에서 아직 확인하지 못한 것**:
  - 8GB(3060 Ti)에서 번역(재지 않기로 함, D-039)
  - 한글 사용자명 경로(묶음을 `C:\koja-test`에 두게 해서 피함)

## 받은 결과 정리

`RESULT_send_this.txt`의 수치를 `docs/설계.md` 7장과 결정기록(D-039 윈도우 측정, D-041 결정 조건)에 옮긴다.
- VRAM은 "시작 전" 값을 빼서 본다.
- `whisper-ko`는 측정 편의상 모든 구간을 다시 돈다. 실제 2회 방식은 `ja`로 잡힌 구간(맥 샘플 기준 54~68%)만 다시 돌리므로, STT 처리 시간은 `whisper` RTF + `whisper-ko` RTF × 0.6으로 환산한다.

## 수동 실행 (참고)

묶음 없이 저장소에서 직접 돌릴 때는 `win_run.py`가 부르는 명령을 그대로 쓴다.
- 환경변수 `FFMPEG`, `WHISPER_SERVER`, `LLAMA_SERVER`, `KOJA_LLAMA_ARGS`, `KOJA_DATA_ROOT`는 `win_run.py`의 `env()`를 본다.
- 명령 순서는 `steps()`를 본다.
- 26B의 MoE 전문가 오프로드 수를 손으로 정하려면 `KOJA_LLAMA_ARGS="--n-cpu-moe 20"`처럼 준다. 서버가 시작 중 메모리 부족으로 죽으면 값을 늘린다.

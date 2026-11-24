"""윈도우 테스트 PC 준비: 그래픽카드 확인, 실행파일(ffmpeg·whisper.cpp·llama.cpp)과 모델을 받아 eval/work/ 아래에 둔다.
1_setup.bat 이 부른다. 이미 받은 파일은 해시를 확인하고 건너뛴다(중간에 끊겨도 다시 실행하면 이어 받는다).
--plan: 받을 목록과 크기만 출력(맥에서 점검용)."""
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

from common import DATA_ROOT, work_dir

BIN = work_dir("bin")
MODELS = work_dir("models")
GB = 1024 ** 3


def hf(repo, rev, name):
    return f"https://huggingface.co/{repo}/resolve/{rev}/{name}"


GH_WHISPER = "https://github.com/ggml-org/whisper.cpp/releases/download/b5907/"
GH_LLAMA = "https://github.com/ggml-org/llama.cpp/releases/download/b12655/"
GH_SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"

# (설명, URL, 저장 경로, 크기, sha256 또는 None, 풀기)
# 버전·해시는 맥 PoC(2026-11-23)에서 쓴 파일과 같다. whisper.cpp·llama.cpp 는 윈도우 CUDA 12.4 빌드.
# 번역 모델은 D-037 결정 조건대로 Gemma 4 E4B·26B-A4B 두 개만(Qwen3.5-9B 제외).
FILES = [
    ("ffmpeg (영상에서 소리 추출)",  # 테스트용이라 GPL 빌드. 앱 번들은 LGPL 빌드를 쓴다(설계 3장)
     "https://github.com/GyanD/codexffmpeg/releases/download/9.1/ffmpeg-9.1-essentials_build.zip",
     BIN / "ffmpeg.zip", 119467219, "b9bbbb3acece000cbeec994f856649354e52c773807841e78908374b24d658fd", "ffmpeg"),
    ("whisper.cpp (음성 인식 프로그램)", GH_WHISPER + "whisper-bin-win-cuda-12.4.0-x64.zip",
     BIN / "whisper.zip", 735571214, "34f872ce724ca1ff0a4afca481853e5ee74250f8cb22153e499f953562846039", "whisper"),
    ("llama.cpp (번역 프로그램)", GH_LLAMA + "llama-b12655-bin-win-cuda-12.4-x64.zip",
     BIN / "llama.zip", 358033331, "0dbd9120332552733dc94bd23582dc3bfb3a60879ae907ddedf3afdb8a628b06", "llama"),
    ("llama.cpp CUDA 실행 파일", GH_LLAMA + "cudart-llama-bin-win-cuda-12.4-x64.zip",
     BIN / "cudart.zip", 391443627, "8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6", "cudart"),
    ("음성 인식 모델", hf("ggerganov/whisper.cpp", "5359861c739e955e79d9a303bcbc70fb988958b1", "ggml-large-v3-turbo.bin"),
     MODELS / "ggml-large-v3-turbo.bin", 1624555275, "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69", None),
    ("말소리 구간 모델", GH_SHERPA + "asr-models/silero_vad.onnx",
     MODELS / "silero_vad.onnx", 643854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", None),
    ("화자 구분 모델 1", GH_SHERPA + "speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
     MODELS / "diar" / "seg.tar.bz2", 6958444, None, "seg"),
    ("화자 구분 모델 2", GH_SHERPA + "speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
     MODELS / "diar" / "campplus_zh_en.onnx", 28281165, "53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b", None),
    ("번역 모델 1 (Gemma 4 E4B)", hf("unsloth/gemma-4-E4B-it-GGUF", "bfc15c382204943c3a8fff0c750b94ae2364d7a3", "gemma-4-E4B-it-Q4_K_M.gguf"),
     MODELS / "llm" / "gemma-4-E4B-it-Q4_K_M.gguf", 4977171584, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87", None),
    ("번역 모델 2 (Gemma 4 26B)", hf("unsloth/gemma-4-26B-A4B-it-GGUF", "c099eb48e663fd284577b04978a94ffccb261841", "gemma-4-26B-A4B-it-UD-Q4_K_M.gguf"),
     MODELS / "llm" / "gemma-4-26B-A4B-it-UD-Q4_K_M.gguf", 16947541728, "f2c28b3dc4776931ac6f879e11f203dec637ea0f14267a86ec8f6165f63f293f", None),
]
SEG_SHA = "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"  # 압축 안 model.onnx
# 압축 파일은 풀고 지우므로, 다시 실행할 때 풀린 결과가 남아 있는지로 끝났는지 판단한다
OUTPUTS = {"ffmpeg": [BIN / "ffmpeg" / "ffmpeg.exe"],
           "whisper": [BIN / "whisper" / "whisper-server.exe", BIN / "whisper" / "cublas64_12.dll"],
           "llama": [BIN / "llama" / "llama-server.exe", BIN / "llama" / "ggml-cuda.dll"],
           "cudart": [BIN / "llama" / "cudart64_12.dll", BIN / "llama" / "cublasLt64_12.dll"],
           "seg": [MODELS / "diar" / "sherpa-onnx-pyannote-segmentation-3-0" / "model.onnx"]}
UNPACK_SLACK = 3 * GB  # whisper·llama 압축을 풀 때 늘어나는 공간(추정)
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler(urllib.request.getproxies()))


def say(msg=""):
    print(msg, flush=True)


def stop(msg):
    say()
    say("!" * 60)
    say(msg)
    say("!" * 60)
    sys.exit(1)


def sha256(p: Path):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def done_marker(dest: Path):
    return dest.with_name(dest.name + ".ok")


def download(desc, url, dest: Path, size, sha):
    """이어 받기(Range)·재시도. 끝까지 받으면 크기와 해시를 확인한다. 받던 .part 는 지우지 않고 이어 받는다."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    for attempt in range(1, 9):
        have = part.stat().st_size if part.exists() else 0
        if have > size:
            part.unlink()
            have = 0
        if have == size:  # 다 받은 뒤 검사 중에 끊긴 경우: 다시 요청하지 않고 바로 검사한다
            break
        try:
            req = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
            with OPENER.open(req, timeout=60) as r, open(part, "ab" if have and r.status == 206 else "wb") as f:
                got = have if r.status == 206 else 0
                last = 0
                while chunk := r.read(1 << 20):
                    f.write(chunk)
                    got += len(chunk)
                    if time.time() - last > 2:
                        last = time.time()
                        print(f"\r    {got / GB:6.2f} / {size / GB:.2f} GB ({got * 100 // size}%)   ", end="", flush=True)
            print()
        except Exception as e:  # 네트워크가 잠깐 끊겨도 이어 받는다
            say(f"\n    연결 문제({e}). {attempt}/8회째, 10초 뒤 이어서 받습니다.")
            time.sleep(10)
    if not part.exists() or part.stat().st_size != size:
        stop(f"'{desc}'을(를) 끝까지 받지 못했습니다. 인터넷 연결을 확인하고 1_setup.bat 을 다시 실행하세요(받은 부분부터 이어 받습니다).")
    if sha:
        say("    파일 검사 중...")
        if sha256(part) != sha:
            part.unlink()
            stop(f"'{desc}' 파일이 손상됐습니다. 1_setup.bat 을 다시 실행하세요.")
    part.replace(dest)


def complete(dest: Path, kind):
    """마커가 있고, 결과 파일도 실제로 남아 있으면 끝난 것으로 본다."""
    outs = OUTPUTS[kind] if kind else [dest]
    return done_marker(dest).exists() and all(p.exists() for p in outs)


def remaining(dest: Path, size):
    part = dest.with_name(dest.name + ".part")
    have = part.stat().st_size if part.exists() else 0
    return size - have if have <= size else size


def extract(kind, src: Path):
    if kind == "ffmpeg":
        with zipfile.ZipFile(src) as z:
            name = next(n for n in z.namelist() if n.endswith("/bin/ffmpeg.exe"))
            (BIN / "ffmpeg").mkdir(exist_ok=True)
            with z.open(name) as a, open(BIN / "ffmpeg" / "ffmpeg.exe", "wb") as b:
                shutil.copyfileobj(a, b)
    elif kind in ("whisper", "llama", "cudart"):
        out = BIN / ("llama" if kind == "cudart" else kind)  # CUDA 런타임 dll 은 llama-server.exe 옆에 둔다
        with zipfile.ZipFile(src) as z:
            for info in z.infolist():
                if info.is_dir():
                    continue
                target = out / Path(info.filename).name  # 폴더 구조를 펴서 exe 와 dll 을 한곳에 둔다
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as a, open(target, "wb") as b:
                    shutil.copyfileobj(a, b)
    elif kind == "seg":
        out = MODELS / "diar" / "sherpa-onnx-pyannote-segmentation-3-0"
        out.mkdir(parents=True, exist_ok=True)
        with tarfile.open(src, "r:bz2") as t:
            m = next(m for m in t.getmembers() if m.name.endswith("/model.onnx"))
            with t.extractfile(m) as a, open(out / "model.onnx", "wb") as b:
                shutil.copyfileobj(a, b)
        if sha256(out / "model.onnx") != SEG_SHA:
            stop("화자 구분 모델 1 파일이 손상됐습니다. 1_setup.bat 을 다시 실행하세요.")
    src.unlink()  # 압축 파일은 풀고 나면 지운다(디스크 절약)


def gpu_info():
    smi = shutil.which("nvidia-smi") or r"C:\Windows\System32\nvidia-smi.exe"
    try:
        q = subprocess.run([smi, "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader,nounits"],
                           capture_output=True, text=True, encoding="utf-8", timeout=30)
        head = subprocess.run([smi], capture_output=True, text=True, encoding="utf-8", timeout=30).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    if q.returncode != 0 or not q.stdout.strip():
        return None
    name, mem, drv = [x.strip() for x in q.stdout.strip().splitlines()[0].split(",")]
    cuda = re.search(r"CUDA Version:\s*([\d.]+)", head)
    return {"name": name, "vramMiB": int(float(mem)), "driver": drv, "cuda": cuda[1] if cuda else None}


def main():
    plan = "--plan" in sys.argv
    todo = [f for f in FILES if not complete(f[2], f[5])]
    if plan:
        for f in FILES:
            say(f"{f[0]:32} {f[3] / GB:6.2f} GB  {'받음' if f not in todo else '받을 것'}")
        say(f"합계 {sum(f[3] for f in todo) / GB:.1f} GB")
        return

    say("=" * 60)
    say(" koja 테스트 준비 (1단계: 설치)")
    say("=" * 60)
    if sys.platform != "win32":
        stop("이 스크립트는 윈도우 전용입니다.")
    root = str(DATA_ROOT.resolve())
    say(f"설치 위치: {root}")
    if "onedrive" in root.lower():
        stop("이 폴더는 OneDrive(클라우드 동기화) 안에 있습니다. 파일이 수십 GB라 동기화되면 안 됩니다.\n"
             "폴더를 통째로 C:\\koja-test 로 옮긴 뒤 다시 실행하세요.")

    gpu = gpu_info()
    if not gpu:
        stop("NVIDIA 그래픽카드를 찾지 못했습니다(nvidia-smi 실행 실패).\n"
             "NVIDIA 그래픽 드라이버를 설치·업데이트한 뒤 다시 실행하세요: https://www.nvidia.com/ko-kr/drivers/")
    say(f"그래픽카드: {gpu['name']} / 메모리 {gpu['vramMiB'] / 1024:.1f} GB / 드라이버 {gpu['driver']} / CUDA {gpu['cuda']}")
    if gpu["cuda"] and tuple(map(int, gpu["cuda"].split(".")[:2])) < (12, 4):
        stop("그래픽 드라이버가 오래됐습니다(CUDA 12.4 이상 필요).\n"
             "NVIDIA 드라이버를 최신으로 업데이트한 뒤 다시 실행하세요: https://www.nvidia.com/ko-kr/drivers/")

    left = sum(remaining(f[2], f[3]) for f in todo)
    need = left + (UNPACK_SLACK if any(f[5] for f in todo) else 0) + 2 * GB
    free = shutil.disk_usage(root).free
    say(f"받을 파일: {left / GB:.1f} GB / 남은 디스크: {free / GB:.1f} GB")
    if free < need:
        stop(f"디스크 공간이 부족합니다. 최소 {need / GB:.0f} GB 가 비어 있어야 합니다(영상 파일은 별도).")

    for i, (desc, url, dest, size, sha, kind) in enumerate(FILES, 1):
        say(f"[{i}/{len(FILES)}] {desc}")
        if complete(dest, kind):
            say("    이미 받음")
            continue
        done_marker(dest).unlink(missing_ok=True)  # 마커만 남고 파일이 지워진 경우 다시 받는다
        if not (dest.exists() and kind is None and dest.stat().st_size == size and (not sha or sha256(dest) == sha)):
            download(desc, url, dest, size, sha)
        if kind:
            say("    압축 푸는 중...")
            extract(kind, dest)
        done_marker(dest).write_text("ok", encoding="utf-8")

    missing = [f[0] for f in FILES if not complete(f[2], f[5])]
    if missing:
        stop(f"빠진 파일이 있습니다: {', '.join(missing)}. 1_setup.bat 을 다시 실행하세요.")
    (work_dir() / "windows_env.json").write_text(json.dumps({"gpu": gpu}, ensure_ascii=False), encoding="utf-8")
    say()
    say("=" * 60)
    say(" 설치가 끝났습니다.")
    say(" samples 폴더에 영상(.MP4)과 sheet.xlsx 를 넣고 2_run_test.bat 을 실행하세요.")
    say("=" * 60)


if __name__ == "__main__":
    main()

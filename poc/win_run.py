"""윈도우 테스트 실행: 음성 인식(whisper 2회) → 화자 구분 → 정답 비교 → 번역 3종 → 번역 비교를 차례로 돌리고,
단계마다 걸린 시간·최대 그래픽 메모리(VRAM)·최대 RAM을 재서 RESULT_send_this.txt 에 숫자만 모은다.
2_run_test.bat 이 부른다. 끝난 단계는 다시 실행해도 건너뛴다(--again: 처음부터)."""
import ctypes
import json
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

import psutil

from common import DATA_ROOT, work_dir

POC = Path(__file__).resolve().parent
BIN = work_dir("bin")
LOGS = work_dir("logs")
STATE = LOGS / "win_state.json"
RESULT = DATA_ROOT / "RESULT_send_this.txt"
SERVERS = {"whisper-server.exe", "llama-server.exe", "whisper-server", "llama-server"}
DIAR = "sherpa-campplus@0.9"
MT = [("e4b", "번역 모델 1 (Gemma 4 E4B)"), ("g26b", "번역 모델 2 (Gemma 4 26B)")]  # D-041 결정 조건
# translate.py 의 -ngl 99 뒤에 붙어 덮어쓴다. 1차: llama-server --fit(기본 on)이 VRAM 에 맞춰 층·MoE 전문가를 나눈다.
# 2차(1차 실패 시): 전문가 전부 CPU. 어느 쪽으로 돌았는지는 결과 파일의 서버 로그 줄로 남는다.
LLAMA_ARGS = ["-ngl auto -lv 4", "-ngl 99 --cpu-moe -lv 4"]  # -lv 4: 층·버퍼 배치를 로그에 남긴다


def say(msg=""):
    print(msg, flush=True)


class Monitor(threading.Thread):
    """1초마다 GPU 사용 메모리(nvidia-smi)와, 단계 프로세스 + 서버 프로세스의 RAM 합을 잰다."""

    def __init__(self):
        super().__init__(daemon=True)
        self.smi = shutil.which("nvidia-smi") or r"C:\Windows\System32\nvidia-smi.exe"
        self.vram = self.vram_peak = self.ram_peak = 0
        self.root = None
        self.stop = threading.Event()

    def vram_now(self):
        try:
            out = subprocess.run([self.smi, "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
                                 capture_output=True, text=True, timeout=10).stdout
            return int(out.split()[0])
        except (OSError, ValueError, IndexError, subprocess.TimeoutExpired):
            return 0

    def ram_now(self):
        procs = []
        if self.root:
            try:
                procs = [self.root] + self.root.children(recursive=True)
            except psutil.Error:
                pass
        procs += [p for p in psutil.process_iter(["name"]) if p.info["name"] in SERVERS and p not in procs]
        total = 0
        for p in procs:
            try:
                total += p.memory_info().rss
            except psutil.Error:
                pass
        return total

    def reset(self, pid):
        self.root = psutil.Process(pid)
        self.vram_peak = self.ram_peak = 0

    def run(self):
        while not self.stop.wait(1):
            self.vram = self.vram_now()
            self.vram_peak = max(self.vram_peak, self.vram)
            self.ram_peak = max(self.ram_peak, self.ram_now())


def keep_awake():
    if sys.platform == "win32":  # 테스트 중 절전 모드로 들어가지 않게(끝나면 자동 해제)
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)


def steps(names, has_sheet):
    """(키, 설명, 명령, 선행 단계). 선행 단계가 성공하지 않으면 건너뛴다(입력이 없어도 0으로 끝나는 스크립트가 있어서)."""
    py = [sys.executable, "-X", "utf8"]
    s = [("audio", "영상에서 소리 뽑기", py + ["extract_audio.py"], []),
         ("vad", "말소리 구간 찾기", py + ["vad.py"] + names, ["audio"]),
         ("stt_auto", "음성 인식 1회차 (언어 자동)", py + ["stt.py", "whisper"] + names, ["vad"]),
         ("stt_ko", "음성 인식 2회차 (한국어 고정)", py + ["stt.py", "whisper-ko"] + names, ["vad"]),
         ("merge", "음성 인식 결과 합치기", py + ["merge_2pass.py", "whisper", "whisper-ko", "whisper-2pass"], ["stt_auto", "stt_ko"]),
         ("diar", "화자 구분", py + ["diar.py", DIAR] + names, ["audio"])]
    if has_sheet:
        s += [("sheet", "정답 시트 읽기", py + ["parse_sheet.py"], []),
              ("eval", "음성 인식 정답 비교", py + ["evaluate.py", "whisper-2pass", DIAR], ["merge", "diar", "sheet"])]
        s += [(f"mt_{k}", desc, py + ["translate.py", k] + names, ["sheet"]) for k, desc in MT]
        s += [("mt_eval", "번역 정답 비교", py + ["mt_eval.py"] + [k for k, _ in MT], ["stt_ko"] + [f"mt_{k}" for k, _ in MT])]
    return s


def fingerprint(samples):
    """영상·시트의 이름·크기·수정 시각. 바뀌면 이전 결과를 버리고 처음부터 한다."""
    return sorted([p.name, p.stat().st_size, int(p.stat().st_mtime)] for p in samples.iterdir()
                  if p.suffix.lower() in (".mp4", ".xlsx"))


def reset_outputs():
    # 모델·실행파일(bin, models)은 두고, 영상에서 나온 중간 결과만 지운다
    for d in ("audio", "vad", "stt", "diar", "sheet", "mt", "tmp"):
        shutil.rmtree(work_dir() / d, ignore_errors=True)


def env(llama_args=LLAMA_ARGS[0]):
    e = dict(os.environ)
    e.update({"KOJA_DATA_ROOT": str(DATA_ROOT), "PYTHONUTF8": "1", "PYTHONIOENCODING": "utf-8",
              "FFMPEG": str(BIN / "ffmpeg" / "ffmpeg.exe"),
              "WHISPER_SERVER": str(BIN / "whisper" / "whisper-server.exe"),
              "LLAMA_SERVER": str(BIN / "llama" / "llama-server.exe"),
              "KOJA_LLAMA_ARGS": llama_args})
    return e


def run_step(key, desc, cmd, mon, llama_args=LLAMA_ARGS[0]):
    log = LOGS / f"win_{key}.log"
    mon.vram_base = mon.vram_now()  # 직전 단계 서버 메모리가 섞이지 않게 시작 직전에 잰다
    t0 = time.time()
    with open(log, "w", encoding="utf-8") as f:
        p = subprocess.Popen(cmd, cwd=POC, env=env(llama_args), stdout=f, stderr=subprocess.STDOUT)
        mon.reset(p.pid)
        while p.poll() is None:
            el = int(time.time() - t0)
            print(f"\r    진행 중... {el // 60}분 {el % 60:02d}초 (그래픽 메모리 {mon.vram / 1024:.1f} GB)   ", end="", flush=True)
            time.sleep(1)
    print()
    return {"ok": p.returncode == 0, "code": p.returncode, "sec": round(time.time() - t0),
            "vramPeakGB": round(mon.vram_peak / 1024, 2), "vramBaseGB": round(mon.vram_base / 1024, 2),
            "ramPeakGB": round(mon.ram_peak / 1024 ** 3, 2), "llamaArgs": llama_args if key.startswith("mt_") else None,
            "at": datetime.now().isoformat(timespec="seconds")}


def tail(path, n=20):
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()[-n:]
    except OSError:
        return []


def read_json(p):
    try:
        return json.loads(Path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def write_result(state, plan):
    """숫자만 모은다. 전사·번역 내용은 넣지 않는다(외부로 보내는 파일)."""
    envj = read_json(work_dir() / "windows_env.json") or {}
    vm = psutil.virtual_memory()
    out = [f"koja 윈도우 테스트 결과 ({datetime.now():%Y-%m-%d %H:%M})", "",
           f"그래픽카드: {envj.get('gpu')}",
           f"CPU: {platform.processor()} / 코어 {psutil.cpu_count(logical=False)} / RAM {vm.total / 1024 ** 3:.0f} GB",
           f"윈도우: {platform.platform()}", "",
           "[단계별] 상태 | 걸린 시간 | 최대 VRAM(시작 전) | 최대 RAM(테스트 프로세스 합)"]
    for key, desc, _, _ in plan:
        r = state.get(key)
        if not r:
            out.append(f"- {desc}: 실행 안 함")
            continue
        if r.get("skipped"):
            out.append(f"- {desc}: 건너뜀(앞 단계 실패)")
            continue
        out.append(f"- {desc}: {'성공' if r['ok'] else '실패(코드 %s)' % r['code']} | {r['sec'] // 60}분 {r['sec'] % 60}초 | "
                   f"{r['vramPeakGB']} GB ({r['vramBaseGB']} GB) | {r['ramPeakGB']} GB"
                   + (f" | 서버 인자 {r['llamaArgs']}" if r.get("llamaArgs") else ""))
    out += ["", "[처리 속도] RTF = 처리 시간 / 영상 길이"]
    for tag in ("whisper", "whisper-ko"):
        m = read_json(work_dir("stt", tag) / "meta.json")
        if m:
            out.append(f"- {tag}: " + ", ".join(f"{k} {v['rtf']}" for k, v in m["samples"].items()))
    m = read_json(work_dir("diar", DIAR) / "meta.json")
    if m:
        out.append(f"- {DIAR}: " + ", ".join(f"{k} {v['rtf']} (화자 {v['speakers']})" for k, v in m["samples"].items()))
    for key, _ in MT:  # 서버가 GPU·CPU 에 무엇을 올렸는지(숫자만 있는 줄)
        lines = [l.strip() for l in tail(work_dir("mt", key) / "server_time.txt", 4000)
                 if any(k in l for k in ("offloaded", "model buffer size", "KV buffer size", "llama_params_fit"))]
        if lines:
            out += ["", f"[번역 서버 {key} 메모리 배치]"] + lines[:16]
    for key, title in (("eval", "음성 인식 정답 비교"), ("mt_eval", "번역 정답 비교")):
        lines = [l for l in tail(LOGS / f"win_{key}.log", 60) if l[:1].isalnum() and ("{" in l or "chrF" in l or "Per" in l or "Peak" in l)]
        if lines:
            out += ["", f"[{title}]"] + lines
    failed = [k for k, _, _, _ in plan if state.get(k) and not state[k]["ok"] and not state[k].get("skipped")]
    for k in failed:
        # 원본 로그는 영상 파일 경로(이름) 등이 들어갈 수 있어 보내는 파일에 넣지 않는다. 오류 종류만 적는다
        err = next((l.split(":")[0].strip() for l in reversed(tail(LOGS / f"win_{k}.log", 200))
                    if l[:1].isalpha() and ("Error" in l.split(":")[0] or "Exit" in l.split(":")[0])), "알 수 없음")
        out.append(f"[실패한 단계 {k}] 오류 종류: {err} (자세한 기록: eval\\work\\logs\\win_{k}.log, 보내지 말고 보관)")
    RESULT.write_text("\n".join(out) + "\n", encoding="utf-8-sig")  # 메모장에서 한글이 깨지지 않게 BOM
    return failed


def main():
    say("=" * 60)
    say(" koja 테스트 실행 (2단계)")
    say("=" * 60)
    for exe in (BIN / "ffmpeg" / "ffmpeg.exe", BIN / "whisper" / "whisper-server.exe", BIN / "llama" / "llama-server.exe"):
        if not exe.exists():
            say("설치가 끝나지 않았습니다. 먼저 1_setup.bat 을 실행하세요.")
            sys.exit(1)
    samples = DATA_ROOT / "samples"
    names = sorted({p.stem for p in samples.glob("*") if p.suffix.lower() == ".mp4"})  # extract_audio.py 와 같은 확장자
    if not names:
        say(f"영상이 없습니다. {samples} 폴더에 영상 파일(.MP4)을 넣고 다시 실행하세요.")
        sys.exit(1)
    has_sheet = (samples / "sheet.xlsx").exists()
    say(f"영상 {len(names)}개: {', '.join(names)}")
    say("정답 시트: " + ("있음" if has_sheet else "없음 (정답 비교와 번역 테스트는 건너뜁니다)"))
    plan = steps(names, has_sheet)
    state = read_json(STATE) or {}
    fp = fingerprint(samples)
    if "--again" in sys.argv or state.get("_inputs") != fp:
        if state:
            say("영상이나 시트가 바뀌었거나 처음부터 다시 하라는 요청이라, 이전 결과를 지우고 처음부터 합니다.")
        reset_outputs()
        state = {"_inputs": fp}
    keep_awake()
    mon = Monitor()
    mon.start()
    for i, (key, desc, cmd, deps) in enumerate(plan, 1):
        say(f"\n[{i}/{len(plan)}] {desc}")
        if state.get(key, {}).get("ok"):
            say("    이미 끝남 (건너뜀)")
            continue
        if not all(state.get(d, {}).get("ok") for d in deps):
            say("    앞 단계가 실패해서 건너뜁니다.")
            state[key] = {"ok": False, "skipped": True}
            STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
            continue
        state[key] = run_step(key, desc, cmd, mon)
        if not state[key]["ok"] and key.startswith("mt_"):
            say("    그래픽 메모리가 부족했을 수 있습니다. 다른 설정으로 한 번 더 시도합니다.")
            state[key] = run_step(key, desc, cmd, mon, LLAMA_ARGS[1])
        STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
        r = state[key]
        say(f"    {'끝' if r['ok'] else '실패'}: {r['sec'] // 60}분 {r['sec'] % 60}초, 최대 그래픽 메모리 {r['vramPeakGB']} GB")
    mon.stop.set()
    failed = write_result(state, plan)
    say()
    say("=" * 60)
    if failed:
        say(f" 일부 단계가 실패했습니다: {', '.join(failed)}")
        say(" 그래도 결과 파일을 보내 주세요. 다시 실행하면 실패한 단계부터 이어서 합니다.")
    else:
        say(" 모든 테스트가 끝났습니다.")
    say(f" 이 파일을 보내 주세요: {RESULT}")
    say("=" * 60)


if __name__ == "__main__":
    main()

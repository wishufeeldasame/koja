"""VAD 구간마다 STT 후보 하나를 돌려 eval/work/stt/<엔진>/<샘플>.json 을 만든다.
출력 구간: id, startMs, endMs, text, lang, words[{text,startMs,endMs}] (VAD 구간 기준 절대 시간).
사용: uv run python stt.py <엔진> <샘플>... (엔진: qwen, univa, whisper, sherpa06, sherpa17)
엔진 이름 끝에 -ko를 붙이면 언어를 한국어로 고정한다(qwen, univa, whisper)."""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

import numpy as np
import soundfile as sf

from common import work_dir

VAD = os.environ.get("KOJA_VAD", "vad")  # 다른 VAD 구간(예: vad10)으로 돌리면 결과 폴더는 <엔진>@vad10

SR = 16000
MODELS = work_dir("models")
HF = {"qwen": "Qwen/Qwen3-ASR-1.7B", "univa": "TeamUNIVA/qwen3_asr_1.7b_ko_beta"}
ALIGNER = "Qwen/Qwen3-ForcedAligner-0.6B"
# 로컬 서버 요청이 HTTP_PROXY 설정을 타고 밖으로 나가지 않게 프록시를 끈다(샘플 외부 전송 금지)
LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))
LANG = {"Korean": "ko", "Japanese": "ja", "English": "en", "korean": "ko", "japanese": "ja", "english": "en"}


def load(name):
    wav, sr = sf.read(work_dir("audio") / f"{name}.wav", dtype="float32")
    assert sr == SR
    vad = json.loads((work_dir(VAD) / f"{name}.json").read_text(encoding="utf-8"))
    return wav, vad


def cut(wav, seg):
    return wav[seg["startMs"] * SR // 1000: seg["endMs"] * SR // 1000]


class Qwen:
    def __init__(self, repo, language=None):
        self.language = language
        import torch
        from qwen_asr import Qwen3ASRModel
        dev = "mps" if torch.backends.mps.is_available() else ("cuda" if torch.cuda.is_available() else "cpu")
        self.m = Qwen3ASRModel.from_pretrained(
            repo, forced_aligner=ALIGNER, forced_aligner_kwargs={"dtype": torch.bfloat16, "device_map": dev},
            dtype=torch.bfloat16, device_map=dev, max_inference_batch_size=8, max_new_tokens=512)

    def run(self, clips):
        res = self.m.transcribe([(c, SR) for c in clips], language=self.language, return_time_stamps=True)
        out = []
        for r in res:
            words = [{"text": w.text, "s": w.start_time, "e": w.end_time} for w in (r.time_stamps or [])]
            out.append((r.text, LANG.get(r.language, r.language), words))
        return out


class Sherpa:
    def __init__(self, d: Path):
        import sherpa_onnx
        find = lambda pat: str(next(d.glob(pat)))
        self.r = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=find("conv_frontend*.onnx"), encoder=find("encoder*int8.onnx"),
            decoder=find("decoder*int8.onnx"), tokenizer=str(d / "tokenizer"), num_threads=8,
            max_total_len=1024, max_new_tokens=512)

    def run(self, clips):
        out = []
        for c in clips:
            s = self.r.create_stream()
            s.accept_waveform(SR, c)
            self.r.decode_stream(s)
            res = s.result
            # shortcut: sherpa Qwen3-ASR는 단어 시간·언어 태그를 주지 않는다(문서 출력 기준). 언어는 문자로 추정한다.
            out.append((res.text, guess_lang(res.text), []))
        return out


def guess_lang(t):
    kana = sum(1 for ch in t if "぀" <= ch <= "ヿ")
    hangul = sum(1 for ch in t if "가" <= ch <= "힣")
    return "ja" if kana > hangul else "ko"


class Whisper:
    """whisper.cpp 서버를 띄워 두고 구간마다 보낸다(모델을 한 번만 올리려고)."""
    PORT = 18080

    def __init__(self, language="auto", tag="whisper"):
        self.language = language
        self.log = work_dir("stt", tag) / "server_time.txt"
        server = os.environ.get("WHISPER_SERVER", shutil.which("whisper-server") or "whisper-server")
        cmd = [server, "-m", str(MODELS / "ggml-large-v3-turbo.bin"), "--port", str(self.PORT), "-l", language, "-t", "8"]
        # 맥에서는 서버 최대 메모리를 /usr/bin/time 으로 잰다. 윈도우는 nvidia-smi 로 따로 잰다(poc/WINDOWS.md).
        self.timed = sys.platform == "darwin"
        self.p = subprocess.Popen((["/usr/bin/time", "-l"] if self.timed else []) + cmd,
                                  stdout=subprocess.DEVNULL, stderr=open(self.log, "w", encoding="utf-8"))
        for _ in range(120):
            if self.p.poll() is not None:
                raise SystemExit(f"whisper-server가 시작 중 종료됨. 로그: {self.log}")
            try:
                LOCAL.open(f"http://127.0.0.1:{self.PORT}/", timeout=1)
                return
            except Exception:
                time.sleep(1)
        self.close()
        raise SystemExit(f"whisper-server 준비 시간 초과. 로그: {self.log}")

    def run(self, clips):
        out = []
        tmp = work_dir("tmp") / "w.wav"
        for c in clips:
            sf.write(tmp, c, SR, subtype="PCM_16")
            r = post(f"http://127.0.0.1:{self.PORT}/inference", tmp,
                     {"response_format": "verbose_json", "language": self.language, "temperature": "0"})
            words = [{"text": w["word"], "s": w["start"], "e": w["end"]}
                     for s in r.get("segments", []) for w in s.get("words", [])]
            out.append((r.get("text", "").strip(), LANG.get(r.get("language"), r.get("language")), words))
        return out

    def close(self):
        if self.timed:  # /usr/bin/time 이 아니라 그 자식(서버)을 끝내야 time 이 최대 메모리를 기록한다
            subprocess.run(["pkill", "-TERM", "-P", str(self.p.pid)])
        else:
            self.p.terminate()
        self.p.wait()


def post(url, path, fields):
    b = uuid.uuid4().hex
    body = b"".join(f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode() for k, v in fields.items())
    body += (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
             f"Content-Type: audio/wav\r\n\r\n").encode() + Path(path).read_bytes() + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(url, body, {"Content-Type": f"multipart/form-data; boundary={b}"})
    return json.loads(LOCAL.open(req, timeout=600).read())


def make(engine):
    base, ko = engine.removesuffix("-ko"), engine.endswith("-ko")
    if base in HF:
        return Qwen(HF[base], "Korean" if ko else None)
    if base == "whisper":
        return Whisper("ko" if ko else "auto", engine if VAD == "vad" else f"{engine}@{VAD}")
    if engine == "sherpa06":
        return Sherpa(next(MODELS.glob("sherpa-onnx-qwen3-asr-0.6B*")))
    if engine == "sherpa17":
        return Sherpa(MODELS / "sherpa-qwen3-1.7B")
    raise SystemExit(f"unknown engine {engine}")


def main():
    engine, names = sys.argv[1], sys.argv[2:]
    tag = engine if VAD == "vad" else f"{engine}@{VAD}"
    t0 = time.time()
    eng = make(engine)
    load_s = time.time() - t0
    meta = {"engine": engine, "loadSec": round(load_s, 1), "samples": {}}
    try:
        for name in names:
            wav, vad = load(name)
            t1 = time.time()
            res = []
            for i in range(0, len(vad), 8):
                batch = vad[i:i + 8]
                for seg, (text, lang, words) in zip(batch, eng.run([cut(wav, s) for s in batch])):
                    res.append({**seg, "text": text, "lang": lang,
                                "words": [{"text": w["text"], "startMs": seg["startMs"] + int(w["s"] * 1000),
                                           "endMs": seg["startMs"] + int(w["e"] * 1000)} for w in words]})
            sec = time.time() - t1
            (work_dir("stt", tag) / f"{name}.json").write_text(json.dumps(res, ensure_ascii=False), encoding="utf-8")
            meta["samples"][name] = {"sec": round(sec, 1), "audioSec": round(len(wav) / SR, 1), "rtf": round(sec / (len(wav) / SR), 3)}
            print(engine, name, meta["samples"][name], flush=True)
    finally:  # 실패해도 서버가 포트·GPU 메모리를 잡고 남지 않게
        if hasattr(eng, "close"):
            eng.close()
    (work_dir("stt", tag) / "meta.json").write_text(json.dumps(meta), encoding="utf-8")


if __name__ == "__main__":
    main()

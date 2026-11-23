"""모든 STT 후보가 같은 구간을 쓰도록 Silero VAD로 발화 구간을 한 번 자른다. → eval/work/vad/<샘플>.json
--split N: N초 넘는 구간을 가장 조용한 지점에서 강제로 나눈다(sherpa VAD의 최대 길이는 강제가 아니다). → eval/work/vadN/"""
import argparse
import json

import numpy as np
import sherpa_onnx
import soundfile as sf

from common import work_dir

SR = 16000


def segments(wav: np.ndarray, model: str, max_sec: float = 20.0):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = model
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.3
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = max_sec
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    win = cfg.silero_vad.window_size
    out = []

    def drain():
        while not vad.empty():
            s = vad.front
            out.append((s.start, s.start + len(s.samples)))
            vad.pop()

    for i in range(0, len(wav), win):
        vad.accept_waveform(wav[i:i + win])
        drain()
    vad.flush()
    drain()
    return out


def split(wav, s, e, max_len):
    """[s,e) 가 max_len 샘플보다 길면 가운데 쪽에서 30ms 에너지가 가장 낮은 곳을 잘라 재귀로 나눈다."""
    if e - s <= max_len:
        return [(s, e)]
    hop = SR * 30 // 1000
    margin = min(SR, (e - s) // 4)
    frames = np.arange(s + margin, e - margin - hop, hop)
    cut = int(frames[np.argmin([np.square(wav[f:f + hop]).mean() for f in frames])])
    return split(wav, s, cut, max_len) + split(wav, cut, e, max_len)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="+")
    ap.add_argument("--split", type=int, default=0)  # 정수 초만 받아 폴더 이름(vadN)과 1:1로 맞춘다
    args = ap.parse_args()
    model = str(work_dir("models") / "silero_vad.onnx")
    out_dir = work_dir(f"vad{args.split}" if args.split else "vad")
    for name in args.names:
        wav, sr = sf.read(work_dir("audio") / f"{name}.wav", dtype="float32")
        assert sr == SR
        segs = segments(wav, model)
        if args.split:
            segs = [p for s, e in segs for p in split(wav, s, e, int(args.split * SR))]
        data = [{"id": i + 1, "startMs": s * 1000 // SR, "endMs": e * 1000 // SR} for i, (s, e) in enumerate(segs)]
        (out_dir / f"{name}.json").write_text(json.dumps(data), encoding="utf-8")
        speech = sum(d["endMs"] - d["startMs"] for d in data) / 1000
        print(name, "segments", len(data), "speech_s", round(speech), "of", round(len(wav) / SR))


if __name__ == "__main__":
    _w = np.ones(SR * 25, dtype="float32"); _w[SR * 12:SR * 12 + 800] = 0
    assert all(e - s <= SR * 10 for s, e in split(_w, 0, len(_w), SR * 10))
    main()

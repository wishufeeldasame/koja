"""화자 구분 후보를 돌려 eval/work/diar/<엔진>/<샘플>.json([{startMs,endMs,speaker}])을 만든다.
사용: uv run python diar.py <엔진> <샘플>... (엔진: pyannote, sherpa-eres2net, sherpa-campplus)
sherpa 엔진은 군집 거리 기준값을 붙일 수 있다(예: sherpa-eres2net@0.8, 기본 0.5. 클수록 화자가 적게 나뉜다)."""
import json
import os
import sys
import time

import soundfile as sf

from common import work_dir

SR = 16000
D = work_dir("models", "diar")
EMB = {"sherpa-eres2net": "eres2net_base.onnx", "sherpa-campplus": "campplus_zh_en.onnx"}


def sherpa(emb, threshold=0.5):
    import sherpa_onnx as so
    cfg = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(D / "sherpa-onnx-pyannote-segmentation-3-0" / "model.onnx")), num_threads=8),
        embedding=so.SpeakerEmbeddingExtractorConfig(model=str(D / emb), num_threads=8),
        clustering=so.FastClusteringConfig(num_clusters=-1, threshold=threshold),
        min_duration_on=0.3, min_duration_off=0.5)
    assert cfg.validate()
    sd = so.OfflineSpeakerDiarization(cfg)

    def run(wav):
        return [{"startMs": int(r.start * 1000), "endMs": int(r.end * 1000), "speaker": f"S{r.speaker}"}
                for r in sd.process(wav).sort_by_start_time()]
    return run


def pyannote():
    import torch
    from pyannote.audio import Pipeline
    p = Pipeline.from_pretrained("pyannote/speaker-diarization-community-1", token=os.environ.get("HF_TOKEN"))
    if torch.backends.mps.is_available():
        p.to(torch.device("mps"))

    def run(wav):
        out = p({"waveform": torch.from_numpy(wav)[None], "sample_rate": SR})
        ann = getattr(out, "speaker_diarization", out)
        return [{"startMs": int(t.start * 1000), "endMs": int(t.end * 1000), "speaker": str(s)}
                for t, _, s in ann.itertracks(yield_label=True)]
    return run


def main():
    engine, names = sys.argv[1], sys.argv[2:]
    t0 = time.time()
    base, _, th = engine.partition("@")
    run = pyannote() if base == "pyannote" else sherpa(EMB[base], float(th or 0.5))
    meta = {"engine": engine, "loadSec": round(time.time() - t0, 1), "samples": {}}
    for name in names:
        wav, sr = sf.read(work_dir("audio") / f"{name}.wav", dtype="float32")
        assert sr == SR
        t1 = time.time()
        turns = run(wav)
        sec = time.time() - t1
        (work_dir("diar", engine) / f"{name}.json").write_text(json.dumps(turns), encoding="utf-8")
        meta["samples"][name] = {"sec": round(sec, 1), "rtf": round(sec / (len(wav) / SR), 3),
                                 "speakers": len({t["speaker"] for t in turns})}
        print(engine, name, meta["samples"][name], flush=True)
    (work_dir("diar", engine) / "meta.json").write_text(json.dumps(meta), encoding="utf-8")


if __name__ == "__main__":
    main()

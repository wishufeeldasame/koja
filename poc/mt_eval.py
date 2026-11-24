"""번역 결과를 정답 시트 일본어(D열)와 chrF로 비교한다. 시트 일본어는 다듬은 최종본이라 참고 지표다.
chrF: 문자 n-gram(1~6) 정밀도·재현율을 말뭉치 전체로 합산한 F(beta=2), 공백 제외(sacrebleu 기본과 같은 정의).
사용: uv run python mt_eval.py <모델 키>..."""
import json
import re
import sys
from collections import Counter
from datetime import date

from common import DATA_ROOT, work_dir

N, BETA = 6, 2.0


def ngrams(s, n):
    s = "".join(s.split())
    return Counter(s[i:i + n] for i in range(len(s) - n + 1))


def chrf(hyps, refs):
    match = [0] * N
    hyp_n = [0] * N
    ref_n = [0] * N
    for h, r in zip(hyps, refs):
        for n in range(1, N + 1):
            hc, rc = ngrams(h, n), ngrams(r, n)
            match[n - 1] += sum((hc & rc).values())
            hyp_n[n - 1] += sum(hc.values())
            ref_n[n - 1] += sum(rc.values())
    ps = [m / h for m, h in zip(match, hyp_n) if h]
    rs = [m / r for m, r in zip(match, ref_n) if r]
    p, r = (sum(ps) / len(ps) if ps else 0), (sum(rs) / len(rs) if rs else 0)
    return 0.0 if p + r == 0 else 100 * (1 + BETA ** 2) * p * r / (BETA ** 2 * p + r)


LATIN = re.compile(r"[A-Za-z][A-Za-z0-9.]+")


def latin_kept(hyp, ref):
    """시트 일본어의 영문 단어(팀·롤·대회 이름)가 번역에도 있는 개수, 전체 개수."""
    words = {w.lower() for w in LATIN.findall(ref)}
    return sum(w in hyp.lower() for w in words), len(words)


def has_kana(t):
    return any("぀" <= ch <= "ヿ" for ch in t)


def ko_chars_per_hour():
    """1시간 영상에서 번역할 한국어 글자 수 추정: whisper 한국어 고정 STT 출력 글자 수 / 오디오 길이.
    일본어 구간도 한국어로 적힌 값이라 실제보다 많게(보수적으로) 잡힌다."""
    d = work_dir("stt", "whisper-ko")
    meta = json.loads((d / "meta.json").read_text(encoding="utf-8"))["samples"]
    chars = sum(len(s["text"].strip()) for n in meta  # translate.py koChars 와 같이 공백 포함
                for s in json.loads((d / f"{n}.json").read_text(encoding="utf-8")))
    return chars / sum(m["audioSec"] for m in meta.values()) * 3600


def main():
    per_hour = ko_chars_per_hour()
    print("koCharsPerHour", round(per_hour))
    out_dir = DATA_ROOT / "eval" / "results" / date.today().isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {}
    for key in sys.argv[1:]:
        meta = json.loads((work_dir("mt", key) / "meta.json").read_text(encoding="utf-8"))
        summary[key] = {"gpuPeakGB": meta.get("gpuPeakGB"), "loadSec": meta["loadSec"]}
        tot_sec = sum(m["sec"] for m in meta["samples"].values())
        tot_chars = sum(m["koChars"] for m in meta["samples"].values())
        summary[key]["minPerHourVideo"] = round(tot_sec / tot_chars * per_hour / 60, 1)
        print(key, "gpuPeakGB", meta.get("gpuPeakGB"), "minPerHourVideo", summary[key]["minPerHourVideo"])
        for name, m in meta["samples"].items():
            sheet = {r["sheetRow"]: r for r in json.loads((work_dir("sheet") / f"{name}.json").read_text(encoding="utf-8"))
                     if "startMs" in r and r.get("ja")}
            mt = {r["id"]: r["ja"] for r in json.loads((work_dir("mt", key) / f"{name}.json").read_text(encoding="utf-8"))}
            ids = [i for i in sheet if i in mt]
            res = {"rows": len(ids), "chrF": round(chrf([mt[i] for i in ids], [sheet[i]["ja"] for i in ids]), 1),
                   "latinKept": [sum(x) for x in zip(*[latin_kept(mt[i], sheet[i]["ja"]) for i in ids])] or [0, 0],
                   "noKana": sum(not has_kana(mt[i]) for i in ids), "empty": sum(not mt[i] for i in ids),
                   "secPer1kKoChars": round(m["sec"] / m["koChars"] * 1000, 1),
                   "retries": m["retries"], "fallbackBatches": m["fallbackBatches"], "failedRows": m["failedRows"]}
            summary[key][name] = res
            print(key, name, json.dumps(res))
    tag = "_".join(sys.argv[1:])
    (out_dir / f"mt_summary_{tag}.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")


if __name__ == "__main__":
    assert round(chrf(["今日は晴れ"], ["今日は晴れ"]), 1) == 100.0 and chrf(["あ"], ["い"]) == 0.0
    assert 0 < chrf(["今日は雨です"], ["今日は晴れです"]) < 100
    assert latin_kept("VALの選手", "val Champions") == (1, 2)
    main()

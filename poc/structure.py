"""5단계 구조화: STT 구간(2회 방식) + 화자 → 시트 행(rows)과 무시 목록을 로컬 llama-server로 만든다(설계 4장, D-031).
입력: eval/work/stt/<STT>/<샘플>.json, eval/work/diar/<화자>/<샘플>.json. 출력: eval/work/struct/<모델>[@sent]/<샘플>.json, meta.json
LLM에는 구간 ID·언어·화자·시각·텍스트를 주고 rows(kind, segIds)와 ignoredSegIds만 받는다. 시간·원문 필드는 받지 않는다.
청크는 CHUNK_MS + 앞뒤 CTX_MS 문맥. 청크 끝에 걸친 행이 잘리지 않게 끝 행들은 확정하지 않고 다음 청크에서 다시 묻는다.
검증: 청크의 모든 구간 ID가 한국어 행(LINE·QUESTION_KO), 일본어 질문 행(QUESTION_JA), 무시 목록 중 어딘가에 순서대로 쓰였나.
각 목록 안에서는 한 번씩이다. 한국어 후보가 있는 구간(2회 방식)만 일본어 질문과 한국어 행에 함께 쓸 수 있다
(한·일이 섞인 한 구간에 질문과 답변이 같이 든 경우, D-040). 틀리면 오류를 알려 주고 다시 묻는다(최대 TRIES번).
그래도 틀리면 그 청크 구간을 하나씩 LINE 행으로 남긴다(빠뜨리는 것보다 남기는 게 낫다, D-032).
KOJA_SPLIT=1 이면 한국어 구간을 단어 시간의 문장 끝 부호(. ? !)에서 문장 조각으로 나눈 뒤 묶는다(구간 ID 새로 매김).
사용: uv run python structure.py <모델 키> <샘플>...  (모델 키: translate.py MODELS)
환경변수: KOJA_STT(기본 whisper-2pass), KOJA_DIAR(기본 sherpa-campplus@0.9), KOJA_SPLIT, KOJA_LLAMA_ARGS."""
import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.request
import wave
from collections import Counter

from common import work_dir
from evaluate import speaker_of
from translate import LOCAL, MODELS, GpuPeak, Server

CHUNK_MS = 240_000
CTX_MS = 30_000
TRIES = 3
KINDS = ["LINE", "QUESTION_KO", "QUESTION_JA"]
END = re.compile(r"[.?!。？！]$")

SYSTEM = """당신은 게임(e스포츠) 인터뷰 영상의 음성 인식 결과를 자막 편집용 시트 행으로 정리합니다.
입력은 음성 인식 구간 목록입니다. 각 줄: [구간ID] 시각 화자 언어: 텍스트. 언어가 ko가 아닌 구간은 같은 소리를 한국어로 다시 인식한 'ko후보'가 함께 있습니다.
언어 표시는 자동 판정이라 틀릴 수 있습니다. ja로 표시됐어도 ko후보가 자연스러운 한국어 문장이면 한국어 발언일 수 있습니다.

각 구간을 다음 중 하나로 분류하고, 이어지는 구간을 행으로 묶으세요. 구간은 쪼갤 수 없습니다.
- LINE: 답변자(선수 등 인터뷰이)의 한국어 발언. 의미가 완성되는 한 문장을 한 행으로. 한 구간에 문장이 여럿이면 그 구간이 한 행입니다. 긴 문장이 여러 구간에 걸치면 묶습니다.
- QUESTION_JA: 일본인 인터뷰어의 일본어 질문. 질문 하나(의미가 완성되는 단위)를 한 행으로 묶습니다.
- QUESTION_KO: 한국인 인터뷰어의 한국어 질문. 한 문장을 한 행으로.
- 무시(ignoredSegIds): 통역사의 통역(일본어 질문을 한국어로 옮기는 말, 한국어 답변을 일본어로 옮기는 말), 관계자 잡담, 의미 없는 소리.
통역은 바로 앞 발언을 다른 언어로 되풀이하는 말입니다. 화자 표와 앞뒤 문맥으로 누가 인터뷰어·통역·답변자인지 판단하세요.
판단이 애매한 한국어 발언은 무시하지 말고 LINE으로 남기세요.
ko후보가 있는 구간 하나에 일본어 질문과 한국어 답변이 함께 들어 있으면, 그 구간을 QUESTION_JA 행과 LINE 행에 한 번씩 넣을 수 있습니다.

'처리할 구간'의 모든 구간ID를 rows 또는 ignoredSegIds에 넣으세요. 무시한 구간은 rows에 넣지 않습니다.
한국어 행(LINE·QUESTION_KO)끼리, QUESTION_JA 행끼리, ignoredSegIds 안에서는 각 구간ID를 한 번만, 오름차순으로 씁니다.
'앞 문맥'과 '뒤 문맥' 구간은 참고만 하고 출력하지 마세요. 출력은 지정한 JSON만."""


def mmss(ms):
    return f"{ms // 60000}:{ms // 1000 % 60:02d}"


def split_words(words):
    """단어 목록을 문장 끝 부호가 붙은 단어 뒤에서 나눈다."""
    out, cur = [], []
    for w in words:
        cur.append(w)
        if END.search(w["text"].strip()):
            out.append(cur)
            cur = []
    return out + ([cur] if cur else [])


def join(ws):
    return "".join(w["text"] for w in ws).strip()


def load(name, stt, diar, split=False):
    """2회 방식 결과를 단위 하나에 1차 텍스트·단어와 한국어 후보 텍스트·단어를 함께 둔 목록으로 바꾸고 화자를 붙인다.
    split 이면 한국어 쪽 단어(한국어 후보가 있으면 후보, 없으면 1차가 ko일 때 1차)를 문장 끝에서 나누고,
    한국어 후보가 있는 구간의 1차 단어는 시작 시각이 든 조각에 넣는다. 조각 ID는 1부터 새로 매긴다."""
    raw = json.loads((work_dir("stt", stt) / f"{name}.json").read_text(encoding="utf-8"))
    turns = json.loads((work_dir("diar", diar) / f"{name}.json").read_text(encoding="utf-8"))
    ko = {s["id"]: s for s in raw if s.get("secondPass")}
    units = []
    for s in raw:
        if s.get("secondPass"):
            continue
        k = ko.get(s["id"])
        u = {"src": s["id"], "startMs": s["startMs"], "endMs": s["endMs"], "lang": s["lang"],
             "text": s["text"].strip(), "words": s.get("words") or [],
             "ko": k["text"].strip() if k else None, "koWords": (k.get("words") or []) if k else None}
        side = u["koWords"] if k else (u["words"] if s["lang"] == "ko" else [])
        pieces = split_words(side) if split else []
        if len(pieces) < 2:
            units.append(u)
            continue
        starts = [p[0]["startMs"] for p in pieces[1:]]
        prim = [[] for _ in pieces]
        for w in u["words"]:
            prim[sum(w["startMs"] >= t for t in starts)].append(w)
        for p, pw in zip(pieces, prim):
            piece = {**u, "startMs": p[0]["startMs"], "endMs": p[-1]["endMs"]}
            if k:
                piece |= {"koWords": p, "ko": join(p), "words": pw, "text": join(pw)}
            else:
                piece |= {"words": p, "text": join(p)}
            units.append(piece)
    for i, u in enumerate(units, 1):
        u["id"] = i if split else u["src"]
        u["speaker"] = speaker_of(u, turns) or "?"
    return units


def fingerprint(units):
    """구조화에 쓴 단위 목록의 해시. 평가가 다른 STT 입력으로 구간 ID를 잘못 맞추지 않게 출력에 남긴다."""
    key = [[u["id"], u["startMs"], u["endMs"], u["text"], u["ko"], u["speaker"]] for u in units]
    return hashlib.sha256(json.dumps(key, ensure_ascii=False).encode()).hexdigest()


def audio_sec(name):
    with wave.open(str(work_dir("audio") / f"{name}.wav")) as w:
        return round(w.getnframes() / w.getframerate())


def speaker_table(segs):
    """영상 전체에서 화자별 구간 수와 한국어 비율. 인터뷰어(일본어)·통역(둘 다)·답변자(한국어)를 가리는 단서."""
    c = Counter(s["speaker"] for s in segs)
    ko = Counter(s["speaker"] for s in segs if s["lang"] == "ko")
    return "\n".join(f"{k}: 구간 {n}개, 한국어 {ko[k] * 100 // n}%" for k, n in c.most_common())


def seg_line(s, label=""):
    t = f"[{s['id']}] {mmss(s['startMs'])} {s['speaker']} {s['lang']}: {s['text']}"
    if s["ko"] is not None:
        t += f" | ko후보: {s['ko']}"
    return t + label


def prompt(segs, lo, hi, done_kind, table):
    """segs[lo:hi]가 처리할 구간. 앞뒤 CTX_MS 안 구간을 문맥으로 붙인다(앞 문맥은 이미 정한 분류를 표시)."""
    t0, t1 = segs[lo]["startMs"], segs[hi - 1]["endMs"]
    before = [s for s in segs[:lo] if s["startMs"] >= t0 - CTX_MS]
    after = [s for s in segs[hi:] if s["startMs"] <= t1 + CTX_MS]
    parts = [f"화자 표(영상 전체):\n{table}"]
    if before:
        parts.append("앞 문맥(처리됨, 출력하지 말 것):\n" +
                     "\n".join(seg_line(s, f"  → {'+'.join(done_kind[s['id']])}") for s in before))
    parts.append("처리할 구간:\n" + "\n".join(seg_line(s) for s in segs[lo:hi]))
    if after:
        parts.append("뒤 문맥(출력하지 말 것):\n" + "\n".join(seg_line(s) for s in after))
    return [{"role": "system", "content": SYSTEM}, {"role": "user", "content": "\n\n".join(parts)}]


def schema(ids):
    sid = {"type": "integer", "enum": ids}
    return {"type": "object", "required": ["rows", "ignoredSegIds"], "properties": {
        "rows": {"type": "array", "items": {"type": "object", "required": ["kind", "segIds"], "properties": {
            "kind": {"type": "string", "enum": KINDS}, "segIds": {"type": "array", "minItems": 1, "items": sid}}}},
        "ignoredSegIds": {"type": "array", "items": sid}}}


def check(out, ids, dual):
    """설계 원칙 1의 검증. dual 은 한국어 후보가 있는 구간 ID(질문·한국어 행에 함께 쓸 수 있음). 맞으면 None, 틀리면 오류 문장."""
    if not isinstance(out, dict) or not isinstance(out.get("rows"), list) or not isinstance(out.get("ignoredSegIds"), list):
        return "rows와 ignoredSegIds가 있는 객체가 아닙니다."
    rows, ign = out["rows"], out["ignoredSegIds"]
    if any(not isinstance(r, dict) or r.get("kind") not in KINDS or not isinstance(r.get("segIds"), list)
           or not r["segIds"] for r in rows):
        return "rows 항목에 kind 또는 비어 있지 않은 segIds가 없습니다."
    ko = [i for r in rows if r["kind"] != "QUESTION_JA" for i in r["segIds"]]
    ja = [i for r in rows if r["kind"] == "QUESTION_JA" for i in r["segIds"]]
    for name, xs in (("한국어 행", ko), ("QUESTION_JA 행", ja), ("ignoredSegIds", ign)):
        if xs != sorted(set(xs)):
            return f"{name}의 구간ID를 이어 읽으면 중복 없는 오름차순이어야 합니다."
    both = sorted((set(ko) & set(ja)) - set(dual))
    ign_used = sorted(set(ign) & set(ko + ja))
    missing = sorted(set(ids) - set(ko + ja + ign))
    extra = sorted(set(ko + ja + ign) - set(ids))
    if both or ign_used or missing or extra:
        return (f"빠진 구간ID {missing}, 무시하면서 행에도 쓴 ID {ign_used}, ko후보 없이 질문·한국어 행에 함께 쓴 ID {both}, "
                f"처리할 구간이 아닌 ID {extra}.")
    return None


def chat(srv, messages, ids, stats):
    body = {"messages": messages, "temperature": 0, "max_tokens": 4096,
            "response_format": {"type": "json_schema", "json_schema": {"schema": schema(ids)}}}
    req = urllib.request.Request(f"http://127.0.0.1:{srv.port}/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    r = json.loads(LOCAL.open(req, timeout=900).read())
    u = r.get("usage", {})
    stats["promptTokens"] += u.get("prompt_tokens", 0)
    stats["completionTokens"] += u.get("completion_tokens", 0)
    stats["maxPromptTokens"] = max(stats["maxPromptTokens"], u.get("prompt_tokens", 0))
    return r["choices"][0]["message"]["content"]


def ask(srv, segs, lo, hi, done_kind, table, stats):
    ids = [s["id"] for s in segs[lo:hi]]
    dual = [s["id"] for s in segs[lo:hi] if s["ko"] is not None]
    messages = prompt(segs, lo, hi, done_kind, table)
    for attempt in range(TRIES):
        content = chat(srv, messages, ids, stats)
        try:
            out = json.loads(content)
        except json.JSONDecodeError:
            out, err = None, "JSON으로 읽을 수 없습니다."
        else:
            err = check(out, ids, dual)
        if err is None:
            stats["firstTry"] += attempt == 0
            return out
        stats["retries"] += attempt < TRIES - 1  # 실제로 다시 보낸 요청만 센다
        stats["errors"].append(err.split(" [")[0][:40])  # 구간 ID 목록은 빼고 종류만 남긴다
        messages = messages + [{"role": "assistant", "content": content},
                               {"role": "user", "content": f"검증 실패: {err} 처음부터 다시 출력하세요."}]
    stats["fallbackChunks"] += 1
    return {"rows": [{"kind": "LINE", "segIds": [i]} for i in ids], "ignoredSegIds": []}


def settle(rows, ignored, first, last_chunk):
    """확정할 행·무시 ID와 다음 청크 시작 ID(cut, 없으면 None)를 고른다. 끝 행과, 그 첫 구간 이후 ID를 하나라도 가진 행은
    다음 청크에서 다시 묻는다. 그래야 확정된 행은 cut 앞 ID만, 다시 물을 행은 cut 이후 ID만 갖는다."""
    keep = sorted(rows, key=lambda r: r["segIds"][0])
    if last_chunk or not keep:
        return keep, ignored, None
    cut = keep[-1]["segIds"][0]
    while late := [r for r in keep if max(r["segIds"]) >= cut]:
        keep = [r for r in keep if r not in late]
        cut = min([cut] + [r["segIds"][0] for r in late])
    if cut <= first:  # 청크 전체가 한 행이면 다시 물어도 진행하지 않으니 그대로 확정한다
        return sorted(rows, key=lambda r: r["segIds"][0]), ignored, None
    return keep, [i for i in ignored if i < cut], cut


def structure(srv, segs, stats):
    table = speaker_table(segs)
    rows, ignored, done_kind = [], [], {}
    lo = 0
    while lo < len(segs):
        hi = lo
        while hi < len(segs) and segs[hi]["startMs"] < segs[lo]["startMs"] + CHUNK_MS:
            hi += 1
        out = ask(srv, segs, lo, hi, done_kind, table, stats)
        stats["chunks"] += 1
        keep, ign, cut = settle(out["rows"], out["ignoredSegIds"], segs[lo]["id"], hi == len(segs))
        for r in keep:
            rows.append(r)
            for i in r["segIds"]:
                done_kind.setdefault(i, []).append(r["kind"])
        for i in ign:
            ignored.append(i)
            done_kind[i] = ["무시"]
        # cut 이 없으면 이 청크를 다 확정했으니 다음 청크(hi)로 간다
        lo = hi if cut is None else next(k for k in range(lo, len(segs)) if segs[k]["id"] >= cut)
    return {"rows": rows, "ignoredSegIds": sorted(ignored)}


def main():
    key, names = sys.argv[1], sys.argv[2:]
    stt = os.environ.get("KOJA_STT", "whisper-2pass")
    diar = os.environ.get("KOJA_DIAR", "sherpa-campplus@0.9")
    split = os.environ.get("KOJA_SPLIT") == "1"
    tag = key + ("@sent" if split else "")
    final = work_dir("struct", tag)
    tmp = final.with_name(tag + ".partial")  # translate.py 와 같이 끝까지 돈 실행만 struct/<태그>/ 로 바꿔 놓는다
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    gpu = GpuPeak()
    gpu.start()
    t0 = time.time()
    srv = Server(key, tmp, ctx=16384)
    meta = {"model": MODELS[key], "stt": stt, "diar": diar, "split": split, "chunkMs": CHUNK_MS, "ctxMs": CTX_MS,
            "llamaArgs": os.environ.get("KOJA_LLAMA_ARGS", ""), "samples": {}}
    try:
        srv.wait_ready()
        meta["loadSec"] = round(time.time() - t0, 1)
        for name in names:
            segs = load(name, stt, diar, split)
            stats = {"chunks": 0, "firstTry": 0, "retries": 0, "fallbackChunks": 0, "promptTokens": 0,
                     "completionTokens": 0, "maxPromptTokens": 0, "errors": []}
            t1 = time.time()
            out = structure(srv, segs, stats)
            err = check(out, [s["id"] for s in segs], [s["id"] for s in segs if s["ko"] is not None])
            assert err is None, f"합친 결과 검증 실패: {err[:80]}"
            (tmp / f"{name}.json").write_text(json.dumps({**out, "inputHash": fingerprint(segs)}), encoding="utf-8")
            meta["samples"][name] = {"segs": len(segs), "audioSec": audio_sec(name),
                                     "sec": round(time.time() - t1, 1), "rows": len(out["rows"]),
                                     "ignored": len(out["ignoredSegIds"]), **stats}
            print(tag, name, meta["samples"][name], flush=True)
    finally:
        srv.close()
        gpu.stop.set()
    meta["gpuPeakGB"] = round((gpu.peak - gpu.base) / 1e9, 2) if sys.platform == "darwin" else None
    (tmp / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    shutil.rmtree(final)
    tmp.rename(final)


def _r(kind, *ids):
    return {"kind": kind, "segIds": list(ids)}


def _selftest():
    _ids = [3, 4, 5]
    assert check({"rows": [_r("LINE", 3, 5)], "ignoredSegIds": [4]}, _ids, []) is None
    assert check({"rows": [_r("QUESTION_JA", 3), _r("LINE", 3), _r("LINE", 4, 5)], "ignoredSegIds": []}, _ids, [3]) is None
    assert "함께" in check({"rows": [_r("QUESTION_JA", 3), _r("LINE", 3, 4, 5)], "ignoredSegIds": []}, _ids, [])
    assert "빠진" in check({"rows": [_r("LINE", 3)], "ignoredSegIds": [4]}, _ids, [])
    assert "오름차순" in check({"rows": [_r("LINE", 4), _r("LINE", 3)], "ignoredSegIds": [5]}, _ids, [])
    assert "무시" in check({"rows": [_r("LINE", 3, 4, 5)], "ignoredSegIds": [4]}, _ids, [])
    assert check({"rows": [_r("X", 3)], "ignoredSegIds": [4, 5]}, _ids, []) is not None
    # 끝 행 [5,6]과 그 안 ID 6 이후를 가진 질문 행 [4,7]은 다시 묻는다 → cut 4
    _k, _i, _c = settle([_r("LINE", 1, 2), _r("QUESTION_JA", 4, 7), _r("LINE", 5, 6)], [3, 8], 1, False)
    assert _c == 4 and _k == [_r("LINE", 1, 2)] and _i == [3], (_k, _i, _c)
    assert settle([_r("LINE", 1, 2, 3)], [], 1, False)[2] is None
    assert settle([], [1, 2], 1, False) == ([], [1, 2], None)
    assert [len(x) for x in split_words([{"text": "가."}, {"text": " 나"}, {"text": "다?"}, {"text": " 라"}])] == [1, 2, 1]
    assert mmss(398_000) == "6:38"


if __name__ == "__main__":
    _selftest()
    main()

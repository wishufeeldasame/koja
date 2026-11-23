"""STT(+화자 구분) 결과를 정답 시트와 비교한다(D-032 지표 순서).
1) 시트 행 재현율: 시트 한국어 문장 글자가 시트 시각 ±WIN 안 STT 글자에 얼마나 들어 있나(문자 정렬). COVER 이상이면 포함.
2) 시간 오차: 포함된 행의 추정 시작(정렬된 첫 글자의 단어 시간 / 그 글자가 든 STT 구간 시작) - 시트 시각.
3) 화자 일치율: 포함된 행이 든 구간의 화자가 그 영상의 선수 화자(최빈값)와 같은 비율.
4) 일본어 질문: 앞뒤 답변 행 사이에 ja 구간이 있나, 시트 질문 글자가 그 ja 텍스트에 얼마나 들어 있나.
시트에 없는 결과 행은 오류로 세지 않는다.
사용: uv run python evaluate.py <stt엔진> [화자엔진]"""
import difflib
import json
import re
import statistics as st
import sys
from collections import Counter
from datetime import date

from common import DATA_ROOT, work_dir

WIN = 90_000
COVER = 0.6
NORM = re.compile(r"[^0-9a-z가-힣぀-ヿ一-鿿]")


HANGUL = re.compile(r"[^가-힣]")


def norm(t):
    return NORM.sub("", (t or "").lower())


def hangul(t):
    # 시트는 팀·롤·대회 이름을 영문으로, STT는 한글 음차로 적는다. 포함 판정은 한글 글자만 본다.
    return HANGUL.sub("", t or "")


def stream(segs):
    """STT를 (글자, 시각ms, 구간 인덱스) 목록으로 편다. 단어 시간이 있으면 쓰고, 없으면 구간 안에서 고르게 나눈다."""
    out = []
    for k, s in enumerate(segs):
        if s.get("words"):
            for w in s["words"]:
                out += [(ch, w["startMs"], k) for ch in norm(w["text"])]
        else:
            t = norm(s["text"])
            n = max(len(t), 1)
            out += [(ch, s["startMs"] + (s["endMs"] - s["startMs"]) * i // n, k) for i, ch in enumerate(t)]
    return out


def match(text, chars, lo, hi):
    """시간 창 [lo,hi] 안에서 text 글자가 정렬되는 비율과 첫 정렬 글자 위치(블록 길이 2 이상)."""
    idx = [i for i, c in enumerate(chars) if lo <= c[1] <= hi]
    if not idx or not text:
        return 0.0, None
    hay = "".join(chars[i][0] for i in idx)
    sm = difflib.SequenceMatcher(None, text, hay, autojunk=False)
    blocks = [b for b in sm.get_matching_blocks() if b.size >= 2]
    cov = sum(b.size for b in blocks) / len(text)
    return cov, (idx[blocks[0].b] if blocks else None)


def speaker_of(seg, turns):
    best, who = 0, None
    for t in turns:
        ov = min(seg["endMs"], t["endMs"]) - max(seg["startMs"], t["startMs"])
        if ov > best:
            best, who = ov, t["speaker"]
    return who


def variants(segs):
    """2회 방식 결과는 구간마다 후보가 둘이다. 이어 붙이면 한 발화에서 두 후보 글자를 함께 정렬하게 되므로
    자동 언어 결과(1차)와, 2차 후보가 있는 구간만 바꾼 결과를 따로 만든다. 두 목록은 순서·시각이 같다."""
    primary = [s for s in segs if not s.get("secondPass")]
    second = {s["id"]: s for s in segs if s.get("secondPass")}
    return [primary] + ([[second.get(s["id"], s) for s in primary]] if second else [])


def evaluate(name, segs, turns):
    sheet = json.loads((work_dir("sheet") / f"{name}.json").read_text(encoding="utf-8"))
    vs = variants(segs)
    segs = vs[0]
    streams = []
    for v in vs:
        chars = stream(v)
        streams.append((chars, [c for c in chars if not HANGUL.match(c[0])]))
    spk = [speaker_of(s, turns) for s in segs] if turns is not None else None
    lines, details = [r for r in sheet if "startMs" in r], []
    for r in lines:
        lo, hi = r["startMs"] - WIN, r["startMs"] + WIN
        best = None
        for chars, hchars in streams:
            strict, ci = match(norm(r["ko"]), chars, lo, hi)
            cov, ci_h = match(hangul(r["ko"]), hchars, lo, hi)
            # 시간·화자는 포함 판정에 쓴 한글 정렬 위치에서 가져온다
            at = hchars[ci_h] if ci_h is not None else (chars[ci] if ci is not None else None)
            if best is None or cov > best[0]:
                best = (cov, strict, at)
        cov, strict, at = best
        d = {"sheetRow": r["sheetRow"], "sheetMs": r["startMs"], "cover": round(cov, 3),
             "coverStrict": round(strict, 3), "hit": cov >= COVER}
        if at is not None:
            k = at[2]
            d |= {"wordMs": at[1], "segMs": segs[k]["startMs"], "seg": k, "speaker": spk[k] if spk else None}
        details.append(d)
    hits = [d for d in details if d["hit"] and "wordMs" in d]

    def terr(key):
        e = [d[key] - d["sheetMs"] for d in hits]
        if not e:
            return {}
        return {"medianSignedMs": int(st.median(e)), "medianAbsMs": int(st.median(abs(x) for x in e)),
                "within1s": round(sum(abs(x) <= 1000 for x in e) / len(e), 3),
                "within2s": round(sum(abs(x) <= 2000 for x in e) / len(e), 3)}

    res = {"sheetLines": len(lines), "recall": round(len([d for d in details if d["hit"]]) / len(lines), 3),
           "meanCover": round(st.mean(d["cover"] for d in details), 3),
           "recallStrict": round(sum(d["coverStrict"] >= COVER for d in details) / len(lines), 3),
           "timeWord": terr("wordMs"), "timeSegStart": terr("segMs"), "sttSegments": len(segs)}
    player = None
    if spk:
        # 화자가 붙지 않은 구간(None)은 선수 후보에도 분모에도 넣지 않는다
        c = Counter(d["speaker"] for d in hits if d["speaker"] is not None)
        n = sum(c.values())
        player = c.most_common(1)[0][0] if c else None
        res["speaker"] = {"clusters": len(set(t["speaker"] for t in turns)),
                          "playerMatch": round(c[player] / n, 3) if n else None,
                          "unassigned": sum(d["speaker"] is None for d in hits)}

    # 일본어 질문: 시트에서 질문 앞 답변 시각 ~ 뒤 답변 시각 사이
    qs, qd = [], []
    for i, r in enumerate(sheet):
        if r.get("kind") != "QUESTION_JA":
            continue
        prev = next((x["startMs"] for x in reversed(sheet[:i]) if "startMs" in x), 0)
        nxt = next((x["startMs"] for x in sheet[i + 1:] if "startMs" in x), prev + 120_000)
        lo, hi = min(prev, nxt) - 5_000, max(prev, nxt) + 5_000
        ja = [s for s in segs if s["lang"] == "ja" and s["endMs"] >= lo and s["startMs"] <= hi]
        jchars = stream(ja)
        cov, _ = match(norm(r["ja"]), jchars, lo, hi)  # 창 밖 글자는 match 가 시각으로 거른다
        q = {"sheetRow": r["sheetRow"], "jaSegs": sum(any(lo <= c[1] <= hi for c in stream([s])) for s in ja),
             "cover": round(cov, 3)}
        if spk and ja:
            q["speakers"] = sorted({str(spk[segs.index(s)]) for s in ja if spk[segs.index(s)] is not None})
        qd.append(q)
        qs.append(q)
    if qs:
        res["questionsJa"] = {"count": len(qs), "detected": round(sum(q["jaSegs"] > 0 for q in qs) / len(qs), 3),
                              "meanCover": round(st.mean(q["cover"] for q in qs), 3)}
        if player is not None:
            # 질문 구간 화자 중 선수가 아닌 화자가 있는 비율(질문자를 선수와 구별했나)
            withspk = [q for q in qs if q.get("speakers")]
            res["questionsJa"]["notPlayer"] = round(
                sum(any(x != str(player) for x in q["speakers"]) for q in withspk) / len(withspk), 3) if withspk else None
    return res, details, qd


def main():
    stt_engine = sys.argv[1]
    diar_engine = sys.argv[2] if len(sys.argv) > 2 else None
    tag = stt_engine + (f"+{diar_engine}" if diar_engine else "")
    out_dir = DATA_ROOT / "eval" / "results" / date.today().isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {}
    for p in sorted(work_dir("stt", stt_engine).glob("C*.json")):
        segs = json.loads(p.read_text(encoding="utf-8"))
        turns = None
        if diar_engine:
            turns = json.loads((work_dir("diar", diar_engine) / p.name).read_text(encoding="utf-8"))
        res, details, qd = evaluate(p.stem, segs, turns)
        summary[p.stem] = res
        (out_dir / f"detail_{tag}_{p.stem}.json").write_text(
            json.dumps({"lines": details, "questions": qd}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(tag, p.stem, json.dumps(res, ensure_ascii=False))
    (out_dir / f"summary_{tag}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    assert norm("안녕, VAL 1!") == "안녕val1"
    _c = [(ch, 1000 * i, 0) for i, ch in enumerate("가나다라마바")]
    assert match("다라마", _c, 0, 10_000) == (1.0, 2)
    _v = variants([{"id": 1, "lang": "ja"}, {"id": 1, "lang": "ko", "secondPass": True}, {"id": 2, "lang": "ko"}])
    assert [[x["lang"] for x in v] for v in _v] == [["ja", "ko"], ["ko", "ko"]]
    main()

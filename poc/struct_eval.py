"""구조화 결과(rows, ignoredSegIds)를 정답 시트와 비교한다(D-032 순서). 정렬 방식은 evaluate.py 와 같다.
1) 시트 행 재현율: 시트 한국어 행 한글이 LINE·QUESTION_KO 행의 한국어(2회 방식이면 한국어 후보) ±WIN 안에 COVER 이상 정렬되나.
2) 시간: 포함된 시트 행을 가장 많이 담은 출력 행(대표 행)의 시작(첫 단어 시간) - 시트 시각, 1초 이내 비율.
3) 행 경계: 시트 1행 ↔ 출력 1행 비율. 정렬 글자의 ONE 이상이 대표 행에 있고, 그 행이 다른 시트 행의 대표 행이 아닐 때.
4) 일본어 질문: 시트 질문 앞뒤 답변 사이 QUESTION_JA 행의 일본어에 질문 글자가 Q_COVER 이상 정렬되면 검출.
   어떤 시트 질문과도 정렬되지 않은 QUESTION_JA 행은 오검출(시트가 정제본이라 참고).
5) 무시: 시트 행 글자가 SEG_COVER 이상 든 구간이 무시 목록에 들어간 비율(낮을수록 좋음). 시트가 정제본이라 시트에 없는
   구간은 통역인지 편집 때 뺀 발언인지 모른다. 그래서 화자로 대신 본다(추정): 선수 화자, 질문자 화자, 그 외(통역·관계자)
   구간이 한국어 행·일본어 질문 행·무시 중 어디로 갔나.
기준선(seg:<stt>:<split>)은 각 실행이 쓴 (stt, split) 조합마다 자동으로 함께 잰다(단위 1개 = LINE 행 1개).
사용: uv run python struct_eval.py <태그>... (eval/work/struct/<태그>/, 예: e4b e4b@flat_clause)"""
import difflib
import json
import os
import sys
from collections import Counter
from datetime import date

from common import DATA_ROOT, work_dir
from evaluate import COVER, HANGUL, WIN, hangul, norm, stream
from structure import fingerprint, load, normalize_split

ONE = 0.8
Q_COVER = 0.3
SEG_COVER = 0.3


def blocks(text, chars, lo, hi):
    """evaluate.match 와 같은 정렬. 비율과 정렬된 chars 위치 목록."""
    idx = [i for i, c in enumerate(chars) if lo <= c[1] <= hi]
    if not idx or not text:
        return 0.0, []
    hay = "".join(chars[i][0] for i in idx)
    sm = difflib.SequenceMatcher(None, text, hay, autojunk=False)
    pos = [idx[b.b + j] for b in sm.get_matching_blocks() if b.size >= 2 for j in range(b.size)]
    return len(pos) / len(text), pos


def ko_side(u):
    """단위의 한국어 쪽(한국어 후보가 있으면 후보)을 evaluate.stream 이 읽는 구간 모양으로."""
    if u["ko"] is not None:
        return {"text": u["ko"], "words": u["koWords"], "startMs": u["startMs"], "endMs": u["endMs"]}
    return u


def row_chars(rows, segs, pick, keep_char):
    """행 목록을 (글자, 시각, 행 번호) 흐름으로 편다. pick 은 (1차, 한국어) 중 쓸 구간."""
    out = []
    for k, r in enumerate(rows):
        for i in r["segIds"]:
            out += [(ch, ms, k) for ch, ms, _ in stream([pick(segs[i])]) if keep_char(ch)]
    return out


def is_hangul(ch):
    return not HANGUL.match(ch)


def q_window(sheet, i):
    """evaluate.py 와 같은 질문 창: 질문 앞 답변 시각 ~ 뒤 답변 시각 ±5초."""
    prev = next((x["startMs"] for x in reversed(sheet[:i]) if "startMs" in x), 0)
    nxt = next((x["startMs"] for x in sheet[i + 1:] if "startMs" in x), prev + 120_000)
    return min(prev, nxt) - 5_000, max(prev, nxt) + 5_000


def evaluate(name, out, segs):
    sheet = json.loads((work_dir("sheet") / f"{name}.json").read_text(encoding="utf-8"))
    lines = [r for r in sheet if "startMs" in r]
    ko_rows = [r for r in out["rows"] if r["kind"] in ("LINE", "QUESTION_KO")]
    q_rows = [r for r in out["rows"] if r["kind"] == "QUESTION_JA"]
    kchars = row_chars(ko_rows, segs, ko_side, is_hangul)

    def row_start(r):
        s = ko_side(segs[r["segIds"][0]])
        return s["words"][0]["startMs"] if s.get("words") else s["startMs"]

    hits, dom = [], {}
    for r in lines:
        cov, pos = blocks(hangul(r["ko"]), kchars, r["startMs"] - WIN, r["startMs"] + WIN)
        if cov >= COVER:
            c = Counter(kchars[p][2] for p in pos)
            k, n = c.most_common(1)[0]
            hits.append(r["sheetRow"])
            dom[r["sheetRow"]] = (k, n / len(pos))
    owners = Counter(k for k, _ in dom.values())
    one = [s for s, (k, share) in dom.items() if share >= ONE and owners[k] == 1]
    terr = [row_start(ko_rows[k]) - next(r["startMs"] for r in lines if r["sheetRow"] == s) for s, (k, _) in dom.items()]
    res = {"sheetLines": len(lines), "recall": round(len(hits) / len(lines), 3),
           "within1s": round(sum(abs(e) <= 1000 for e in terr) / len(terr), 3) if terr else None,
           "oneToOne": round(len(one) / len(hits), 3) if hits else None,
           "rows": {k: sum(r["kind"] == k for r in out["rows"]) for k in ("LINE", "QUESTION_KO", "QUESTION_JA")},
           "ignored": len(out["ignoredSegIds"])}
    if "videoType" in out:
        res["videoType"] = out["videoType"]

    qchars = row_chars(q_rows, segs, lambda u: u, lambda ch: True)
    qs, q_used = [], set()
    for i, r in enumerate(sheet):
        if r.get("kind") == "QUESTION_JA":
            cov, pos = blocks(norm(r["ja"]), qchars, *q_window(sheet, i))
            qs.append(cov)
            q_used |= {qchars[p][2] for p in pos}
    if qs:
        false_rows = len(q_rows) - len(q_used)
        res["questionsJa"] = {"count": len(qs), "detected": round(sum(c >= Q_COVER for c in qs) / len(qs), 3),
                              "meanCover": round(sum(qs) / len(qs), 3), "outRows": len(q_rows),
                              "falseRows": false_rows,
                              "falseRate": round(false_rows / len(q_rows), 3) if q_rows else 0.0}

    # 구간이 시트 내용인지: 모든 구간을 대상으로 시트 행(한국어)·질문(일본어)을 정렬해 구간별로 겹친 글자를 센다
    all_rows = [{"segIds": [i]} for i in segs]
    ids = list(segs)
    ak = row_chars(all_rows, segs, ko_side, is_hangul)
    aj = row_chars(all_rows, segs, lambda u: u, lambda ch: True)
    pk, pj = set(), set()  # 창이 겹치는 시트 행이 같은 STT 글자에 정렬돼도 한 번만 센다
    for r in lines:
        pk |= set(blocks(hangul(r["ko"]), ak, r["startMs"] - WIN, r["startMs"] + WIN)[1])
    for i, r in enumerate(sheet):
        if r.get("kind") == "QUESTION_JA":
            pj |= set(blocks(norm(r["ja"]), aj, *q_window(sheet, i))[1])
    got_k, got_j = Counter(ids[ak[p][2]] for p in pk), Counter(ids[aj[p][2]] for p in pj)
    size_k, size_j = Counter(ids[c[2]] for c in ak), Counter(ids[c[2]] for c in aj)
    in_lines = {i for i in ids if size_k[i] and got_k[i] >= SEG_COVER * size_k[i]}
    in_q = {i for i in ids if size_j[i] and got_j[i] >= SEG_COVER * size_j[i]}
    # 화자 대리 지표(추정): 시트 행이 든 구간의 최빈 화자 = 선수, 시트 질문이 든 구간의 최빈 화자 = 질문자, 나머지 = 통역·관계자
    player = Counter(segs[i]["speaker"] for i in in_lines).most_common(1)
    asker = Counter(segs[i]["speaker"] for i in in_q).most_common(1)
    player, asker = player[0][0] if player else None, asker[0][0] if asker else None
    ko_ids = {i for r in ko_rows for i in r["segIds"]}
    ja_ids = {i for r in q_rows for i in r["segIds"]}
    ign = set(out["ignoredSegIds"])

    def where(group):
        n = len(group)
        return {"n": n, "koRow": round(len(group & ko_ids) / n, 3), "qJa": round(len(group & ja_ids) / n, 3),
                "ignored": round(len(group & ign) / n, 3)} if n else None

    res["segs"] = {"inSheetIgnored": round(len(in_lines & ign) / len(in_lines), 3) if in_lines else None,
                   "playerSameAsAsker": player == asker,
                   "player": where({i for i in ids if segs[i]["speaker"] == player}),
                   "asker": where({i for i in ids if segs[i]["speaker"] == asker and asker != player}),
                   "others": where({i for i in ids if segs[i]["speaker"] not in (player, asker)})}
    return res


def main():
    stt = os.environ.get("KOJA_STT", "whisper-2pass")
    diar = os.environ.get("KOJA_DIAR", "sherpa-campplus@0.9")
    out_dir = DATA_ROOT / "eval" / "results" / date.today().isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {}
    metas = {k: json.loads((work_dir("struct", k) / "meta.json").read_text(encoding="utf-8")) for k in sys.argv[1:]}
    ref = next(iter(metas.values()), None)
    base_names = sorted(ref["samples"]) if ref else sorted(p.stem for p in work_dir("sheet").glob("C*.json"))
    if ref:
        assert all(sorted(m["samples"]) == base_names for m in metas.values()), "모델마다 샘플 목록이 다름"
    # 실행에 쓰인 (stt, diar, split) 조합마다 기준선을 자동으로 만든다
    baselines = []
    seen = set()
    for m in metas.values():
        spec = (m["stt"], m["diar"], normalize_split(m["split"]))
        if spec not in seen:
            seen.add(spec)
            baselines.append(spec)
    if not baselines:
        baselines = [(stt, diar, "none"), (stt, diar, "sent"), (stt, diar, "clause")]
    for b_stt, b_diar, b_split in baselines:
        bkey = f"base:{b_stt}:{b_split}"
        summary[bkey] = {}
        for name in base_names:
            segs = {u["id"]: u for u in load(name, b_stt, b_diar, b_split)}
            out = {"rows": [{"kind": "LINE", "segIds": [i]} for i in segs], "ignoredSegIds": []}
            summary[bkey][name] = evaluate(name, out, segs)
            print(bkey, name, json.dumps(summary[bkey][name]))
    for key in sys.argv[1:]:
        meta = metas[key]
        names = sorted(meta["samples"])
        summary[key] = {}
        m = meta["samples"].values()
        sec, audio = sum(x["sec"] for x in m), sum(x["audioSec"] for x in m)
        summary[key] |= {"stt": meta["stt"], "split": normalize_split(meta["split"]),
                         "format": meta.get("format", "grouped"),
                         "gpuPeakGB": meta.get("gpuPeakGB"), "loadSec": meta["loadSec"],
                         "minPerHourVideo": round(sec / audio * 60, 1),
                         "chunks": sum(x["chunks"] for x in m), "firstTry": sum(x.get("firstTry", 0) for x in m),
                         "retries": sum(x["retries"] for x in m),
                         "fallbackChunks": sum(x["fallbackChunks"] for x in m),
                         "maxPromptTokens": max(x["maxPromptTokens"] for x in m)}
        print(key, json.dumps(summary[key]))
        for name in names:
            segs = {u["id"]: u for u in load(name, meta["stt"], meta["diar"], meta["split"])}
            out = json.loads((work_dir("struct", key) / f"{name}.json").read_text(encoding="utf-8"))
            if out.get("inputHash") != fingerprint(list(segs.values())):
                raise SystemExit(f"{key} {name}: 구조화 때와 STT·화자 입력이 다름. 구조화를 다시 돌린다")
            summary[key][name] = evaluate(name, out, segs)
            print(key, name, json.dumps(summary[key][name]))
    tag = "_".join(sys.argv[1:]) or "base"
    (out_dir / f"struct_summary_{tag}.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    _c = [(ch, 1000 * i, i // 3) for i, ch in enumerate("가나다라마바")]
    assert blocks("다라마", _c, 0, 10_000) == (1.0, [2, 3, 4])
    assert blocks("다라마", _c, 0, 1_000) == (0.0, [])
    main()

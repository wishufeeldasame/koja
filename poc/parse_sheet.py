"""정답 시트(xlsx)를 탭별 JSON으로 바꾼다. 시트 형식: A=시간(mm:ss가 hh:mm으로 저장됨) B=화자 C=한국어 D=일본어,
질문 행은 A에 일본어 질문, E에 질문 표시."""
import datetime as dt
import json
import re
from pathlib import Path

import openpyxl

from common import DATA_ROOT, work_dir

# 시트 안 클립 머리글(예: "이름 C0004") → 샘플 파일. 한 탭에 클립이 여럿일 수 있다. 샘플이 없는 클립은 건너뛴다.
CLIP_TO_SAMPLE = {"C0003": "C0003-002", "C0004": "C0004-001", "C0008": "C0008-003"}


def to_ms(v):
    if isinstance(v, dt.time):  # 시트가 m:ss를 h:mm으로 저장했다
        return (v.hour * 60 + v.minute) * 1000 + v.second * 1000 // 60
    if isinstance(v, dt.datetime):
        return to_ms(v.time())
    if isinstance(v, dt.timedelta):
        return int(v.total_seconds() * 1000 / 60)
    if isinstance(v, str) and (m := re.fullmatch(r"\s*(\d+)[:;](\d{2})(?:[:;](\d{2}))?\s*", v)):
        a, b, c = int(m[1]), int(m[2]), int(m[3] or 0)
        return (a * 60 + b) * 1000 if m[3] is None else (a * 3600 + b * 60 + c) * 1000
    return None


def parse(path: Path):
    wb = openpyxl.load_workbook(path, data_only=True)
    out = {}
    for ws in wb.worksheets:
        rows, question = [], None
        for i, r in enumerate(ws.iter_rows(values_only=True), start=1):
            a, b, c, d, e = (list(r) + [None] * 5)[:5]
            if isinstance(a, str) and not any((b, c, d, e)) and (m := re.search(r"C\d{4}", a)):
                rows, question = out.setdefault(m[0], []), None
                continue
            t = to_ms(a)
            if t is not None and c:
                rows.append({"sheetRow": i, "startMs": t, "speaker": b, "ko": str(c).strip(),
                             "ja": (str(d).strip() if d else None), "question": question})
                question = None  # 질문은 바로 다음 답변 행에만 붙인다
            elif isinstance(a, str) and e and not c:
                question = {"sheetRow": i, "ja": a.strip(), "type": e}
                rows.append({"sheetRow": i, "kind": "QUESTION_JA", "ja": a.strip(), "type": e})
    return {CLIP_TO_SAMPLE[k]: v for k, v in out.items() if k in CLIP_TO_SAMPLE}


def main():
    data = parse(DATA_ROOT / "samples" / "sheet.xlsx")
    d = work_dir("sheet")
    for name, rows in data.items():
        (d / f"{name}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
        lines = [r for r in rows if "startMs" in r]
        qs = [r for r in rows if r.get("kind") == "QUESTION_JA"]
        ts = [r["startMs"] for r in lines]
        print(name, "lines", len(lines), "questions", len(qs), "first/last ms", ts[0], ts[-1],
              "non-monotonic", sum(1 for x, y in zip(ts, ts[1:]) if y < x),
              "speakers", len({r["speaker"] for r in lines}))


if __name__ == "__main__":
    assert to_ms(dt.time(6, 38)) == 398000
    assert to_ms("1:02:03") == 3723000 and to_ms("6:38") == 398000 and to_ms("24;22") == 1462000 and to_ms("1:00:00") == 3600000
    assert to_ms(dt.timedelta(days=1, hours=1, minutes=13)) == 1513000
    main()

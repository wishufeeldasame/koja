"""2회 방식: 자동 언어 결과를 기본으로 두고, ja로 잡힌 구간은 한국어 고정 결과도 같은 시각에 함께 둔다.
한·일이 섞인 구간에서 한국어 답변이 일본어로 번역돼 사라지는 것을 막는지 본다.
사용: uv run python merge_2pass.py <자동 결과 폴더> <한국어 고정 결과 폴더> <출력 폴더>  (eval/work/stt/ 아래 이름)"""
import json
import sys

from common import work_dir


def merge(auto, ko):
    by_id = {s["id"]: s for s in ko}
    out = []
    for s in auto:
        out.append(s)
        if s["lang"] != "ko" and s["id"] in by_id:
            out.append({**by_id[s["id"]], "lang": "ko", "secondPass": True})
    return out


def main():
    a, k, o = sys.argv[1:4]
    for p in sorted(work_dir("stt", a).glob("C*.json")):
        auto = json.loads(p.read_text(encoding="utf-8"))
        ko = json.loads((work_dir("stt", k) / p.name).read_text(encoding="utf-8"))
        merged = merge(auto, ko)
        (work_dir("stt", o) / p.name).write_text(json.dumps(merged, ensure_ascii=False), encoding="utf-8")
        print(p.stem, "auto", len(auto), "added", len(merged) - len(auto))


if __name__ == "__main__":
    _m = merge([{"id": 1, "lang": "ja"}, {"id": 2, "lang": "ko"}], [{"id": 1, "lang": "ko"}, {"id": 2, "lang": "ko"}])
    assert [s["id"] for s in _m] == [1, 1, 2] and _m[1]["secondPass"]
    main()

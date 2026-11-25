"""5단계 구조화: STT 구간(2회 방식) + 화자 → 시트 행(rows)과 무시 목록을 로컬 llama-server로 만든다(설계 4장, D-031).
입력: eval/work/stt/<STT>/<샘플>.json, eval/work/diar/<화자>/<샘플>.json. 출력: eval/work/struct/<태그>/<샘플>.json, meta.json
LLM에는 구간 ID·언어·화자·시각·텍스트를 주고 구간별 분류(action)·새 행 시작(newRow)·플래그(flags)만 받는다.
시간·원문 필드는 받지 않는다(절대 규칙 1).
1단계(영상 전체 분석): 화자 통계·전환·대표 발화 몇 줄로 영상 유형(videoType)과 화자 역할(인터뷰어·답변자·통역·관계자)을 추정한다.
2단계(청크 구조화): 최대 CHUNK_SEGS 구간(최대 CHUNK_MS) + 앞뒤 문맥으로 각 구간을 1:1 고정 순서 배열로 분류한다.
검증: 출력 배열의 ID가 요청 구간 ID와 정확히 같은 순서·개수인지, BOTH_JA_LINE은 한국어 후보가 있는 구간(D-045)에만 썼는지,
변환된 rows·ignoredSegIds가 D-031·D-045 불변식을 만족하는지 검사한다. 틀리면 오류를 알려 주고 TRIES번까지 다시 묻고,
그래도 틀리면 그 청크 구간을 하나씩 LINE 행으로 남긴다(빠뜨리는 것보다 남기는 게 낫다, D-032).
사용: uv run python structure.py <모델 키> <샘플>...  (모델 키: translate.py MODELS)
환경변수:
  KOJA_STT(기본 whisper-2pass), KOJA_DIAR(기본 sherpa-campplus@0.9),
  KOJA_SPLIT(none|sent|paware|clause, 기본 clause; 1은 구 sent 호환),
  KOJA_TAG(출력 폴더 이름 덮어쓰기), KOJA_CTX(컨텍스트 길이, 기본 8192),
  KOJA_CHUNK_SEGS(청크 최대 구간 수, 기본 24), KOJA_LLAMA_ARGS."""
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
from evaluate import hangul, speaker_of
from translate import LOCAL, MODELS, GpuPeak, Server

CHUNK_MS = 180_000
CHUNK_SEGS = int(os.environ.get("KOJA_CHUNK_SEGS", "24"))
CTX_MS = 30_000
CTX_SEGS = 6
TRIES = 3
MAX_MERGE_CUR_HANGUL = 16
MAX_MERGE_TOTAL_HANGUL = 32
MAX_MERGE_CUR_MS = 4_500
KINDS = ["LINE", "QUESTION_KO", "QUESTION_JA"]
VIDEO_TYPES = ["INTERVIEW_JA", "INTERVIEW_KO", "FREE", "MIXED"]
SPEAKER_ROLES = ["INTERVIEWER_JA", "INTERVIEWER_KO", "INTERVIEWEE", "INTERPRETER", "STAFF", "UNKNOWN"]
FLAGS = ["SPEAKER_UNSURE", "TEXT_UNSURE"]
END = re.compile(r"[.?!。？！]$")
END_CLAUSE = re.compile(r"[,、]$")
KO_END = re.compile(r"(습니다|입니다|세요|어요|아요|해요|예요|에요|거든요|네요|군요|죠|요|다|까|네)[,.?!]?$")
KO_CONN = re.compile(r"(는데|는데요|고요|면서|니까|니까요|지만|지만요|어서|아서|해서|해서요|고|서|면|으면|거나|든지|길래|더라도)[,.?!]?$")

ROLE_KO = {
    "INTERVIEWER_JA": "일본인 인터뷰어(질문자)",
    "INTERVIEWER_KO": "한국인 인터뷰어(질문자)",
    "INTERVIEWEE": "답변자(선수/출연자)",
    "INTERPRETER": "통역사",
    "STAFF": "관계자",
    "UNKNOWN": "역할 미확정",
}

ANALYZE_SYSTEM = """당신은 게임(e스포츠) 영상의 음성 인식 요약과 대화 발췌를 보고 영상 유형과 화자 역할을 판별합니다.
화자별 총 발화 시간, 한국어/일본어 시간 비율, 앞뒤 화자 전환, 실제 연속 대화 발췌를 함께 보고 판별하세요.

1. videoType:
- INTERVIEW_JA: 일본인 인터뷰어가 일본어로 질문하고 한국인 출연자가 한국어로 답변하는 인터뷰(통역사가 함께 있을 수 있음)
- INTERVIEW_KO: 한국인 인터뷰어와 한국인 출연자의 인터뷰
- FREE: 질문/답변 구조 없이 리뷰·해설·자유 대화로 진행되는 영상
- MIXED: 인터뷰와 자유 대화가 섞인 영상

2. 각 화자의 role:
- INTERVIEWEE: 한국어로 답변·설명하는 선수·출연자. 보통 한국어 시간 비율이 압도적으로 높습니다(75% 이상).
- INTERPRETER: 인터뷰어와 답변자 사이에서 통역하는 통역사. 다음 특징 중 하나 이상을 보입니다:
  * 한국어와 일본어를 모두 상당 비율(각 20% 이상) 말함('[한·일 혼합 화자]' 표시)
  * 연속 대화에서 일본어 질문 직후 그 질문을 한국어로 옮기고, 한국어 답변 직후 그 답변을 일본어로 옮김
  * 통역사가 한국어를 많이 말하더라도 일본어 통역을 겸하고 질문/답변을 반복하면 INTERVIEWEE가 아니라 반드시 INTERPRETER입니다!
- INTERVIEWER_JA: 일본어로 새 주제를 질문하는 인터뷰어(일본어 시간 비율이 높고 질문 문장이 많음)
- INTERVIEWER_KO: 한국어로 질문하는 인터뷰어
- STAFF: 촬영 관계자·잡담(짧은 안내나 현장 대화)
- UNKNOWN: '?'(화자 미배정)이거나 구간이 적어 판단하기 어려움

화자 목록의 모든 화자 ID를 입력 순서대로 빠짐없이 한 번씩 출력하세요. 출력은 지정한 JSON만."""

CHUNK_SYSTEM_BASE = """당신은 게임(e스포츠) 영상의 음성 인식 구간을 자막 편집용 시트 행으로 분류·구성합니다.
각 줄 형식: [구간ID] 시각 화자 언어: 텍스트. 언어가 ko가 아닌 구간은 같은 소리를 한국어로 다시 인식한 'ko후보'가 함께 있습니다.
주의: 일본어 음성을 한국어로 강제 인식한 'ko후보'는 발음만 비슷하고 뜻이 통하지 않는 깨진 한국어인 경우가 많습니다. 텍스트가 자연스러운 일본어이고 ko후보가 부자연스러우면 순수 일본어 발화입니다. 반대로 텍스트가 깨지고 ko후보가 자연스러운 한국어 문장이면 한국어 발화입니다.
화자 역할 표는 참고 단서이며 화자 구분이 일부 틀릴 수 있으므로 앞뒤 문맥과 실제 내용을 함께 보고 판단하세요.

'처리할 구간'의 모든 구간ID를 입력과 정확히 같은 순서로 하나씩 출력하세요(앞·뒤 문맥은 참고만 하고 출력하지 마세요).
각 항목 필드:
- id: 입력 구간ID 그대로
- action: 아래 규칙에 따라 선택
- newRow: true이면 새 행 시작, false이면 바로 앞 구간과 같은 행으로 이어 붙임
  * LINE·QUESTION_KO: 각 구간은 이미 자막 한 행 길이의 문장·절 단위이므로 기본값은 반드시 true다. 바로 앞 구간이 10자 미만의 아주 짧은 미완성 조각(주어·접속어만 잘림 등)일 때만 false로 이어 붙인다.
  * QUESTION_JA: 한 질문이 여러 구간에 이어지면 첫 구간만 true, 이어지는 같은 질문 구간은 false로 한 행에 묶는다.
  * 화자나 action이 바뀌거나 IGNORE 다음이면 반드시 true.
- flags: 보통 []. 화자 구분이 문맥과 어긋나 보이면 "SPEAKER_UNSURE", 전사가 깨지거나 들리지 않아 확인이 필요하면 "TEXT_UNSURE"

출력은 지정한 JSON만."""

ACTION_RULES = {
    "FREE": """[영상 유형: FREE (자유 발언형)]
- LINE: 한국어 발언(질문 영역을 만들지 않는다).
- IGNORE: 의미 없는 잡음·비한국어 잡담. 판단이 애매한 한국어 발언은 무시하지 말고 LINE으로 남긴다.""",
    "INTERVIEW_KO": """[영상 유형: INTERVIEW_KO (한국인 인터뷰어)]
- QUESTION_KO: 한국인 인터뷰어의 질문.
- LINE: 답변자(선수/출연자)의 한국어 답변.
- IGNORE: 관계자 잡담, 의미 없는 소리. 애매한 한국어 발언은 무시하지 말고 LINE으로 남긴다.""",
    "INTERVIEW_JA": """[영상 유형: INTERVIEW_JA (일본인 인터뷰어 + 한국인 답변자)]
- LINE: 답변자(INTERVIEWEE)의 한국어 발언.
  * 빠짐없음이 최우선이다. 답변자(INTERVIEWEE)로 추정된 화자의 구간은 언어 태그가 ja/en으로 잘못 붙었더라도 ko후보가 자연스러운 한국어 문장이면 반드시 LINE으로 남긴다(답변자 화자 구간은 명백한 무의미 잡음 외에는 IGNORE 금지).
  * 반대로 인터뷰어·통역사의 순수 일본어 발화(ko후보가 깨진 발음 음차일 뿐인 구간)는 LINE으로 넣지 않는다.
- QUESTION_JA: 일본인 인터뷰어(INTERVIEWER_JA)가 새 주제를 묻는 일본어 질문.
  * 인터뷰어의 정상적인 일본어 질문에 ko후보가 붙어 있어도 LINE이나 BOTH_JA_LINE으로 넣지 말고 QUESTION_JA만 쓴다.
  * 통역사(INTERPRETER)가 바로 앞 한국어 답변을 일본어로 옮기는 말과 짧은 맞장구(はい, なるほど, ありがとうございます, そうですね, うん 등 질문이 아닌 반응)는 QUESTION_JA가 아니라 반드시 IGNORE다!
- BOTH_JA_LINE: 매우 드문 예외. 'ko후보'가 있는 한 구간 안에 인터뷰어의 일본어 질문과 답변자의 실제 한국어 답변이 둘 다 함께 들어 있을 때만 쓴다(순수 일본어 질문이나 순수 한국어 답변에는 절대 쓰지 말 것, ko후보 없는 구간 금지).
- IGNORE:
  * 통역사(INTERPRETER)의 통역: 바로 앞 일본어 질문을 한국어로 옮겨 주는 말, 바로 앞 한국어 답변을 일본어로 옮겨 주는 말.
  * 관계자(STAFF) 잡담, 짧은 맞장구·추임새·의미 없는 소리.
  * 단, 통역사 화자로 붙었더라도 앞 질문의 통역이 아니라 답변자의 실제 답변 내용으로 보이면 IGNORE하지 말고 LINE으로 남긴다.""",
}
ACTION_RULES["MIXED"] = ACTION_RULES["INTERVIEW_JA"].replace(
    "[영상 유형: INTERVIEW_JA (일본인 인터뷰어 + 한국인 답변자)]",
    "[영상 유형: MIXED (혼합형)]",
) + (
    "\n- QUESTION_KO: 인터뷰 구간에서 한국인 인터뷰어의 한국어 질문이 있을 때만 사용."
    "\n- 자유 발언·해설·토론 구간: 인터뷰가 아닌 자유 발언 구간에서는 답변자 외 화자의 한국어 해설·토론도 IGNORE하지 말고 모두 LINE으로 남긴다(질문 영역을 억지로 만들지 않는다)."
)


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


def normalize_split(split):
    if split is True or split in ("1", "sent"):
        return "sent"
    if split in (False, None, "", "0", "none"):
        return "none"
    if split in ("paware", "clause"):
        return split
    raise ValueError(f"알 수 없는 split 모드: {split}")


def word_speaker(w, turns):
    return speaker_of({"startMs": w["startMs"], "endMs": w["endMs"]}, turns) or "?"


def split_clause_words(words, turns, is_ko, clause_cut=True):
    """단어 목록을 문장 끝·화자 전환·(한국어이고 clause_cut이면) 절 경계에서 나눈다."""
    if not words:
        return []
    out, cur = [], []
    for w in words:
        if cur:
            prev = cur[-1]
            dur = prev["endMs"] - cur[0]["startMs"]
            gap = w["startMs"] - prev["endMs"]
            pt = prev["text"].strip()
            sp1, sp2 = word_speaker(prev, turns), word_speaker(w, turns)
            split_here = False
            if not is_ko:
                # 비한국어 구간은 2차 한국어 환각 부호로 자르지 않고 화자 전환 또는 긴 문장 끝에서만 나눈다
                if sp1 != "?" and sp2 != "?" and sp1 != sp2 and dur >= 1500:
                    split_here = True
                elif END.search(pt) and dur >= 8000:
                    split_here = True
            else:
                cur_h = len(hangul(join(cur)))
                if END.search(pt):
                    split_here = True
                elif sp1 != "?" and sp2 != "?" and sp1 != sp2 and dur >= 1000:
                    split_here = True
                elif clause_cut:
                    if END_CLAUSE.search(pt) and cur_h >= 8:
                        split_here = True
                    elif gap >= 250 and cur_h >= 8:
                        split_here = True
                    elif cur_h >= 10 and KO_END.search(pt):
                        split_here = True
                    elif cur_h >= 14 and KO_CONN.search(pt):
                        split_here = True
                    elif cur_h >= 24:
                        split_here = True
            if split_here:
                out.append(cur)
                cur = []
        cur.append(w)
    if cur:
        out.append(cur)
    return out


def load(name, stt, diar, split="clause"):
    """2회 방식 결과를 단위 목록으로 바꾸고 화자를 붙인다.
    split 모드:
      - none: 원본 STT 구간 그대로
      - sent: 기존 문장 끝 부호 분할(이전 평가 결과 호환용)
      - paware: 1차 언어 기준 문장 끝·화자 전환 분할(비한국어 구간을 한국어 후보 부호로 자르지 않음)
      - clause: paware + 한국어 긴 발화를 절·어미·휴지 경계에서 추가 분할"""
    mode = normalize_split(split)
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
        if mode == "none":
            units.append(u)
            continue
        if mode == "sent":
            side = u["koWords"] if k else (u["words"] if s["lang"] == "ko" else [])
            pieces = split_words(side)
            use_ko = bool(k)
        else:
            is_ko = s["lang"] == "ko"
            ws = u["words"] if is_ko else (u["words"] or (u["koWords"] or []))
            use_ko = (not is_ko) and (not u["words"]) and bool(k)
            pieces = split_clause_words(ws, turns, is_ko=is_ko, clause_cut=(mode == "clause"))
        if len(pieces) < 2:
            units.append(u)
            continue
        other_ws = u["words"] if use_ko else (u["koWords"] if k else [])
        other_txt = u["text"] if use_ko else (u["ko"] or "")
        # 한쪽 원문에 텍스트는 있는데 단어 시각이 없으면 분할 시 원문이 사라지므로 분할하지 않는다(절대 규칙 1)
        if k and other_txt and not other_ws:
            units.append(u)
            continue
        starts = [p[0]["startMs"] for p in pieces[1:]]
        other_bins = [[] for _ in pieces]
        for w in other_ws:
            other_bins[sum(w["startMs"] >= t for t in starts)].append(w)
        parent_spk = None if mode == "sent" else speaker_of(u, turns)
        for p, ow in zip(pieces, other_bins):
            piece = {**u, "startMs": p[0]["startMs"], "endMs": p[-1]["endMs"], "_parentSpk": parent_spk}
            if k:
                if use_ko:
                    piece |= {"koWords": p, "ko": join(p), "words": ow, "text": join(ow)}
                else:
                    piece |= {"words": p, "text": join(p), "koWords": ow, "ko": join(ow)}
            else:
                piece |= {"words": p, "text": join(p)}
            units.append(piece)
    for i, u in enumerate(units, 1):
        u["id"] = u["src"] if mode == "none" else i
        u["speaker"] = speaker_of(u, turns) or u.pop("_parentSpk", None) or "?"
        u.pop("_parentSpk", None)
    return units


def fingerprint(units):
    """구조화에 쓴 단위 목록의 해시. 평가가 다른 STT 입력으로 구간 ID를 잘못 맞추지 않게 출력에 남긴다."""
    key = [[u["id"], u["startMs"], u["endMs"], u["text"], u["ko"], u["speaker"]] for u in units]
    return hashlib.sha256(json.dumps(key, ensure_ascii=False).encode()).hexdigest()


def audio_sec(name):
    with wave.open(str(work_dir("audio") / f"{name}.wav")) as w:
        return round(w.getnframes() / w.getframerate())


def speaker_profile(segs):
    """1단계 영상 분석용 요약: 화자별 발화 시간·언어 시간 비율·전환 패턴·연속 대화 발췌."""
    c = Counter(s["speaker"] for s in segs)
    dur = Counter()
    ko_dur = Counter()
    ja_dur = Counter()
    for s in segs:
        d = max(1, s["endMs"] - s["startMs"])
        sp = s["speaker"]
        dur[sp] += d
        if s["lang"] == "ko":
            ko_dur[sp] += d
        elif s["lang"] == "ja":
            ja_dur[sp] += d
    prev_spk, next_spk = {}, {}
    for a, b in zip(segs, segs[1:]):
        if a["speaker"] != b["speaker"]:
            prev_spk.setdefault(b["speaker"], Counter())[a["speaker"]] += 1
            next_spk.setdefault(a["speaker"], Counter())[b["speaker"]] += 1
    lines = []
    speakers = [k for k, _ in dur.most_common()]
    for sp in speakers:
        n = c[sp]
        sec = dur[sp] // 1000
        ko_pct = ko_dur[sp] * 100 // dur[sp]
        ja_pct = ja_dur[sp] * 100 // dur[sp]
        ps = ", ".join(f"{k}({v})" for k, v in prev_spk.get(sp, Counter()).most_common(2)) or "없음"
        ns = ", ".join(f"{k}({v})" for k, v in next_spk.get(sp, Counter()).most_common(2)) or "없음"
        bilingual = " [한·일 혼합 화자]" if ko_pct >= 20 and ja_pct >= 20 else ""
        lines.append(f"- {sp}: {sec}초(구간 {n}개), ko {ko_pct}%, ja {ja_pct}%{bilingual}, 직전 [{ps}], 직후 [{ns}]")
        sp_segs = [s for s in segs if s["speaker"] == sp]
        for lang in ("ko", "ja"):
            cand = sorted([s for s in sp_segs if s["lang"] == lang and len(s["text"]) >= 6],
                          key=lambda s: -len(s["text"]))[:1]
            for s in cand:
                lines.append(f"    예시[{lang}]: {s['text'][:60]}")
    # 실제 연속 대화 흐름(영상 25%·60% 지점 각 12구간): 겹치면 한 범위로 합치고, 떨어지면 별도 발췌로 표시
    n_segs = len(segs)
    if n_segs <= 24:
        ranges = [(0, n_segs)]
    else:
        a0, a1 = n_segs // 4, min(n_segs, n_segs // 4 + 12)
        b0, b1 = n_segs * 3 // 5, min(n_segs, n_segs * 3 // 5 + 12)
        ranges = [(a0, max(a1, b1))] if a1 >= b0 else [(a0, a1), (b0, b1)]
    for idx, (lo, hi) in enumerate(ranges, 1):
        header = f"\n연속 대화 발췌 {idx}(시간 순):" if len(ranges) > 1 else "\n연속 대화 발췌(시간 순):"
        lines.append(header)
        for s in segs[lo:hi]:
            lines.append(f"  {mmss(s['startMs'])} {s['speaker']}({s['lang']}): {s['text'][:65]}")
    return speakers, "\n".join(lines)


def speaker_table(segs, roles=None):
    """청크 프롬프트에 넣는 화자 표(시간 비율 + 1단계 역할 추정 포함)."""
    c = Counter(s["speaker"] for s in segs)
    dur, ko_dur, ja_dur = Counter(), Counter(), Counter()
    for s in segs:
        d = max(1, s["endMs"] - s["startMs"])
        sp = s["speaker"]
        dur[sp] += d
        if s["lang"] == "ko":
            ko_dur[sp] += d
        elif s["lang"] == "ja":
            ja_dur[sp] += d
    out = []
    for k, _ in dur.most_common():
        role_txt = f", 추정 역할: {ROLE_KO.get(roles[k], roles[k])}" if roles and k in roles else ""
        out.append(f"{k}: {dur[k] // 1000}초(구간 {c[k]}개), ko {ko_dur[k] * 100 // dur[k]}%, ja {ja_dur[k] * 100 // dur[k]}%{role_txt}")
    return "\n".join(out)


def has_ko_candidate(s):
    return bool((s.get("ko") or "").strip())


def seg_line(s, label=""):
    t = f"[{s['id']}] {mmss(s['startMs'])} {s['speaker']} {s['lang']}: {s['text']}"
    if has_ko_candidate(s):
        t += f" | ko후보: {s['ko']}"
    return t + label


def analyze_schema(speakers):
    return {
        "type": "object",
        "required": ["videoType", "speakers"],
        "properties": {
            "videoType": {"type": "string", "enum": VIDEO_TYPES},
            "speakers": {
                "type": "array",
                "minItems": len(speakers),
                "maxItems": len(speakers),
                "items": {
                    "type": "object",
                    "required": ["id", "role"],
                    "properties": {
                        "id": {"type": "string", "enum": speakers},
                        "role": {"type": "string", "enum": SPEAKER_ROLES},
                    },
                },
            },
        },
    }


def analyze_video(srv, segs, stats):
    """1단계: 영상 전체 화자 요약으로 영상 유형과 화자 역할을 한 번 추정한다."""
    speakers, profile = speaker_profile(segs)
    if not speakers:
        return "MIXED", {}
    messages = [
        {"role": "system", "content": ANALYZE_SYSTEM},
        {"role": "user", "content": f"영상 전체 구간 수: {len(segs)}개\n\n화자별 요약:\n{profile}"},
    ]
    sch = analyze_schema(speakers)
    for _ in range(2):
        content = chat_json(srv, messages, sch, stats, max_tokens=1024)
        try:
            out = json.loads(content)
            vtype = out["videoType"]
            sp_list = out["speakers"]
            got = [x["id"] for x in sp_list]
            if (vtype in VIDEO_TYPES and len(got) == len(speakers) and set(got) == set(speakers)
                    and all(x["role"] in SPEAKER_ROLES for x in sp_list)):
                return vtype, {x["id"]: x["role"] for x in sp_list}
        except (json.JSONDecodeError, KeyError, TypeError):
            pass
    return "MIXED", {s: "UNKNOWN" for s in speakers}


def actions_for(video_type, has_dual):
    if video_type == "FREE":
        return ["LINE", "IGNORE"]
    if video_type == "INTERVIEW_KO":
        return ["LINE", "QUESTION_KO", "IGNORE"]
    acts = ["LINE", "QUESTION_JA", "IGNORE"]
    if video_type == "MIXED":
        acts.insert(1, "QUESTION_KO")
    if has_dual:
        acts.append("BOTH_JA_LINE")
    return acts


def flat_schema(ids, actions):
    return {
        "type": "object",
        "required": ["items"],
        "properties": {
            "items": {
                "type": "array",
                "minItems": len(ids),
                "maxItems": len(ids),
                "items": {
                    "type": "object",
                    "required": ["id", "action", "newRow", "flags"],
                    "properties": {
                        "id": {"type": "integer", "enum": ids},
                        "action": {"type": "string", "enum": actions},
                        "newRow": {"type": "boolean"},
                        "flags": {"type": "array", "items": {"type": "string", "enum": FLAGS}},
                    },
                },
            }
        },
    }


def chunk_prompt(segs, lo, hi, done_kind, table, video_type):
    """segs[lo:hi]가 처리할 구간. 앞뒤 CTX_SEGS/CTX_MS 안 구간을 문맥으로 붙인다."""
    t0, t1 = segs[lo]["startMs"], segs[hi - 1]["endMs"]
    before = [s for s in segs[max(0, lo - CTX_SEGS):lo] if s["startMs"] >= t0 - CTX_MS]
    after = [s for s in segs[hi:min(len(segs), hi + CTX_SEGS)] if s["startMs"] <= t1 + CTX_MS]
    system = CHUNK_SYSTEM_BASE + "\n\n" + ACTION_RULES.get(video_type, ACTION_RULES["MIXED"])
    parts = [f"화자 표(영상 전체):\n{table}"]
    if before:
        parts.append("앞 문맥(처리됨, 출력하지 말 것):\n" +
                     "\n".join(seg_line(s, f"  → {'+'.join(done_kind[s['id']])}") for s in before))
    parts.append("처리할 구간:\n" + "\n".join(seg_line(s) for s in segs[lo:hi]))
    if after:
        parts.append("뒤 문맥(출력하지 말 것):\n" + "\n".join(seg_line(s) for s in after))
    return [{"role": "system", "content": system}, {"role": "user", "content": "\n\n".join(parts)}]


def check_items(out, ids, dual, allowed_actions):
    """평탄 출력(items) 검증. 맞으면 None, 틀리면 오류 문장."""
    if not isinstance(out, dict) or not isinstance(out.get("items"), list):
        return "items 배열이 있는 객체가 아닙니다."
    items = out["items"]
    got_ids = [x.get("id") if isinstance(x, dict) else None for x in items]
    if got_ids != ids:
        missing = sorted(set(ids) - set(i for i in got_ids if isinstance(i, int)))
        extra = sorted(set(i for i in got_ids if isinstance(i, int)) - set(ids))
        return f"items의 id가 입력 순서·개수와 다릅니다(빠짐 {missing}, 초과 {extra}, 길이 {len(got_ids)}/{len(ids)})."
    dual_set = set(dual)
    bad_both = []
    for it in items:
        act = it.get("action")
        if act not in allowed_actions:
            return f"허용되지 않은 action: {act}"
        if not isinstance(it.get("newRow"), bool):
            return f"구간 {it['id']}의 newRow가 불리언이 아닙니다."
        fl = it.get("flags")
        if not isinstance(fl, list) or any(f not in FLAGS for f in fl):
            return f"구간 {it['id']}의 flags가 올바르지 않습니다."
        if act == "BOTH_JA_LINE" and it["id"] not in dual_set:
            bad_both.append(it["id"])
    if bad_both:
        return f"ko후보가 없는 구간에 BOTH_JA_LINE을 썼습니다: {bad_both}"
    return None


def _ko_text(seg):
    return seg["ko"] if seg.get("ko") is not None else seg.get("text", "")


def _can_merge_ko(cur_ko, seg, seg_map):
    """자막 행 과병합 방지(요구사항 5-3·8-4): 앞 행이 문장 끝이거나 이미 자막 길이에 도달하면 새 행으로 분리한다."""
    prev_seg = seg_map[cur_ko["segIds"][-1]]
    prev_txt = _ko_text(prev_seg).strip()
    if END.search(prev_txt):
        return False
    cur_h = sum(len(hangul(_ko_text(seg_map[i]))) for i in cur_ko["segIds"])
    add_h = len(hangul(_ko_text(seg)))
    cur_ms = prev_seg.get("endMs", 0) - seg_map[cur_ko["segIds"][0]].get("startMs", 0)
    return cur_h < MAX_MERGE_CUR_HANGUL and cur_ms < MAX_MERGE_CUR_MS and (cur_h + add_h) <= MAX_MERGE_TOTAL_HANGUL


def _auto_flags(seg, kind):
    """절대 규칙 2 보강: 화자 미배정이나 한국어 원문 누락 위험 구간에 코드로 기본 플래그를 붙인다."""
    out = []
    if kind != "QUESTION_JA":
        if seg.get("speaker", "?") == "?":
            out.append("SPEAKER_UNSURE")
        if seg.get("lang") and seg.get("lang") != "ko" and len(hangul(_ko_text(seg))) < 2:
            out.append("TEXT_UNSURE")
    return out


def items_to_rows(items, seg_map):
    """검증된 구간별 분류(items)를 결정적으로 rows·ignoredSegIds로 변환한다."""
    rows, ignored = [], []
    cur_ko, cur_ja = None, None
    for it in items:
        sid, act, new_row = it["id"], it["action"], it["newRow"]
        seg = seg_map[sid]
        spk = seg["speaker"]
        base_fl = list(dict.fromkeys(it.get("flags") or []))
        if act == "IGNORE":
            ignored.append(sid)
            cur_ko, cur_ja = None, None
        elif act in ("LINE", "QUESTION_KO"):
            cur_ja = None
            fl = list(dict.fromkeys(base_fl + _auto_flags(seg, act)))
            if (cur_ko and not new_row and cur_ko["kind"] == act and cur_ko["_spk"] == spk
                    and _can_merge_ko(cur_ko, seg, seg_map)):
                cur_ko["segIds"].append(sid)
                for f in fl:
                    if f not in cur_ko["flags"]:
                        cur_ko["flags"].append(f)
            else:
                cur_ko = {"kind": act, "segIds": [sid], "flags": fl, "_spk": spk}
                rows.append(cur_ko)
        elif act == "QUESTION_JA":
            cur_ko = None
            fl = list(base_fl)
            if cur_ja and not new_row:
                cur_ja["segIds"].append(sid)
                for f in fl:
                    if f not in cur_ja["flags"]:
                        cur_ja["flags"].append(f)
            else:
                cur_ja = {"kind": "QUESTION_JA", "segIds": [sid], "flags": fl}
                rows.append(cur_ja)
        elif act == "BOTH_JA_LINE":
            fl_ja = list(base_fl)
            fl_ko = list(dict.fromkeys(base_fl + _auto_flags(seg, "LINE")))
            if cur_ja and not new_row:
                cur_ja["segIds"].append(sid)
                for f in fl_ja:
                    if f not in cur_ja["flags"]:
                        cur_ja["flags"].append(f)
            else:
                cur_ja = {"kind": "QUESTION_JA", "segIds": [sid], "flags": fl_ja}
                rows.append(cur_ja)
            cur_ko = {"kind": "LINE", "segIds": [sid], "flags": fl_ko, "_spk": spk}
            rows.append(cur_ko)
    for r in rows:
        r.pop("_spk", None)
    return {"rows": rows, "ignoredSegIds": ignored}


def check(out, ids, dual):
    """설계 원칙 1(D-031·D-045)의 최종 rows/ignoredSegIds 불변식 검증."""
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


def chat_json(srv, messages, schema_obj, stats, max_tokens=4096):
    body = {"messages": messages, "temperature": 0, "max_tokens": max_tokens,
            "response_format": {"type": "json_schema", "json_schema": {"schema": schema_obj}}}
    req = urllib.request.Request(f"http://127.0.0.1:{srv.port}/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    r = json.loads(LOCAL.open(req, timeout=900).read())
    u = r.get("usage", {})
    stats["promptTokens"] += u.get("prompt_tokens", 0)
    stats["completionTokens"] += u.get("completion_tokens", 0)
    stats["maxPromptTokens"] = max(stats["maxPromptTokens"], u.get("prompt_tokens", 0))
    return r["choices"][0]["message"]["content"]


def ask(srv, segs, seg_map, lo, hi, done_kind, table, video_type, stats):
    ids = [s["id"] for s in segs[lo:hi]]
    dual = [s["id"] for s in segs[lo:hi] if has_ko_candidate(s)]
    acts = actions_for(video_type, bool(dual))
    sch = flat_schema(ids, acts)
    base_messages = chunk_prompt(segs, lo, hi, done_kind, table, video_type)
    messages = base_messages
    for attempt in range(TRIES):
        content = chat_json(srv, messages, sch, stats, max_tokens=4096)
        try:
            raw = json.loads(content)
        except json.JSONDecodeError:
            err = "JSON으로 읽을 수 없습니다."
        else:
            err = check_items(raw, ids, dual, acts)
            if err is None:
                out = items_to_rows(raw["items"], seg_map)
                err = check(out, ids, dual)
        if err is None:
            stats["firstTry"] += attempt == 0
            return out
        stats["retries"] += attempt < TRIES - 1
        stats["errors"].append(err.split(" [")[0][:50])
        # 재시도 때 깨진 긴 JSON을 누적하지 않고 최신 오류와 정확한 ID 목록만 덧붙인다
        messages = base_messages + [
            {"role": "user", "content": f"이전 출력 검증 실패: {err}\n처리할 구간 ID 목록 {ids} 를 빠짐없이 정확히 이 순서대로 다시 JSON 출력하세요."},
        ]
    stats["fallbackChunks"] += 1
    return {"rows": [{"kind": "LINE", "segIds": [i], "flags": _auto_flags(seg_map[i], "LINE")} for i in ids],
            "ignoredSegIds": []}


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
    if cut <= first:
        return sorted(rows, key=lambda r: r["segIds"][0]), ignored, None
    return keep, [i for i in ignored if i < cut], cut


def structure(srv, segs, stats):
    video_type, roles = analyze_video(srv, segs, stats)
    table = speaker_table(segs, roles)
    seg_map = {s["id"]: s for s in segs}
    rows, ignored, done_kind = [], [], {}
    lo = 0
    while lo < len(segs):
        hi = lo
        while hi < len(segs) and (hi - lo) < CHUNK_SEGS and segs[hi]["startMs"] < segs[lo]["startMs"] + CHUNK_MS:
            hi += 1
        if hi == lo:
            hi = lo + 1
        out = ask(srv, segs, seg_map, lo, hi, done_kind, table, video_type, stats)
        stats["chunks"] += 1
        keep, ign, cut = settle(out["rows"], out["ignoredSegIds"], segs[lo]["id"], hi == len(segs))
        for r in keep:
            rows.append(r)
            for i in r["segIds"]:
                done_kind.setdefault(i, []).append(r["kind"])
        for i in ign:
            ignored.append(i)
            done_kind[i] = ["무시"]
        lo = hi if cut is None else next(k for k in range(lo, len(segs)) if segs[k]["id"] >= cut)
    return {"videoType": video_type, "speakerRoles": roles, "rows": rows, "ignoredSegIds": sorted(ignored)}


def main():
    key, names = sys.argv[1], sys.argv[2:]
    stt = os.environ.get("KOJA_STT", "whisper-2pass")
    diar = os.environ.get("KOJA_DIAR", "sherpa-campplus@0.9")
    split = normalize_split(os.environ.get("KOJA_SPLIT", "clause"))
    ctx = int(os.environ.get("KOJA_CTX", "8192"))
    default_tag = f"{key}@flat_{split}" + (f"_{stt.split('@')[1]}" if "@" in stt else "")
    tag = os.environ.get("KOJA_TAG", default_tag)
    final = work_dir("struct", tag)
    tmp = final.with_name(tag + ".partial")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    gpu = GpuPeak()
    gpu.start()
    t0 = time.time()
    srv = Server(key, tmp, ctx=ctx)
    meta = {"model": MODELS[key], "stt": stt, "diar": diar, "split": split, "format": "flat",
            "chunkMs": CHUNK_MS, "chunkSegs": CHUNK_SEGS, "ctxMs": CTX_MS, "ctxSegs": CTX_SEGS, "ctx": ctx,
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
            err = check(out, [s["id"] for s in segs], [s["id"] for s in segs if has_ko_candidate(s)])
            assert err is None, f"합친 결과 검증 실패: {err[:80]}"
            (tmp / f"{name}.json").write_text(json.dumps({**out, "inputHash": fingerprint(segs)}, ensure_ascii=False),
                                               encoding="utf-8")
            flag_counts = Counter(f for r in out["rows"] for f in r.get("flags", []))
            meta["samples"][name] = {"segs": len(segs), "audioSec": audio_sec(name),
                                     "sec": round(time.time() - t1, 1), "videoType": out["videoType"],
                                     "speakerRoles": out["speakerRoles"], "rows": len(out["rows"]),
                                     "ignored": len(out["ignoredSegIds"]), "flags": dict(flag_counts), **stats}
            print(tag, name, meta["samples"][name], flush=True)
    finally:
        srv.close()
        gpu.stop.set()
    meta["gpuPeakGB"] = round((gpu.peak - gpu.base) / 1e9, 2) if sys.platform == "darwin" else None
    (tmp / "meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
    shutil.rmtree(final)
    tmp.rename(final)


def _r(kind, *ids, flags=None):
    return {"kind": kind, "segIds": list(ids), "flags": list(flags or [])}


def _selftest():
    _ids = [3, 4, 5]
    assert check({"rows": [_r("LINE", 3, 5)], "ignoredSegIds": [4]}, _ids, []) is None
    assert check({"rows": [_r("QUESTION_JA", 3), _r("LINE", 3), _r("LINE", 4, 5)], "ignoredSegIds": []}, _ids, [3]) is None
    assert "함께" in check({"rows": [_r("QUESTION_JA", 3), _r("LINE", 3, 4, 5)], "ignoredSegIds": []}, _ids, [])
    assert "빠진" in check({"rows": [_r("LINE", 3)], "ignoredSegIds": [4]}, _ids, [])
    assert "오름차순" in check({"rows": [_r("LINE", 4), _r("LINE", 3)], "ignoredSegIds": [5]}, _ids, [])
    assert "무시" in check({"rows": [_r("LINE", 3, 4, 5)], "ignoredSegIds": [4]}, _ids, [])
    assert check({"rows": [_r("X", 3)], "ignoredSegIds": [4, 5]}, _ids, []) is not None

    # flat items 검증 및 변환 테스트
    acts = actions_for("INTERVIEW_JA", True)
    ok_items = {"items": [
        {"id": 3, "action": "BOTH_JA_LINE", "newRow": True, "flags": []},
        {"id": 4, "action": "LINE", "newRow": False, "flags": ["TEXT_UNSURE"]},
        {"id": 5, "action": "IGNORE", "newRow": True, "flags": []},
    ]}
    assert check_items(ok_items, _ids, [3], acts) is None
    assert "BOTH_JA_LINE" in check_items(ok_items, _ids, [], acts)
    assert "순서" in check_items({"items": list(reversed(ok_items["items"]))}, _ids, [3], acts)
    seg_map = {
        3: {"speaker": "S1", "lang": "ja", "text": "質問", "ko": "짧은 조각", "startMs": 0, "endMs": 1000},
        4: {"speaker": "S1", "lang": "ko", "text": "이어지는 말.", "ko": None, "startMs": 1000, "endMs": 2000},
        5: {"speaker": "S2", "lang": "ja", "text": "通訳", "ko": "통역", "startMs": 2000, "endMs": 3000},
    }
    conv = items_to_rows(ok_items["items"], seg_map)
    assert conv == {"rows": [_r("QUESTION_JA", 3), _r("LINE", 3, 4, flags=["TEXT_UNSURE"])], "ignoredSegIds": [5]}, conv
    assert check(conv, _ids, [3]) is None
    # 화자가 바뀌면 newRow=False여도 새 행으로 분리된다
    seg_map_diff = {
        3: {"speaker": "S1", "lang": "ko", "text": "앞 조각", "ko": None, "startMs": 0, "endMs": 1000},
        4: {"speaker": "S2", "lang": "ko", "text": "다른 화자", "ko": None, "startMs": 1000, "endMs": 2000},
        5: {"speaker": "S2", "lang": "ko", "text": "이어짐", "ko": None, "startMs": 2000, "endMs": 3000},
    }
    conv2 = items_to_rows([
        {"id": 3, "action": "LINE", "newRow": True, "flags": []},
        {"id": 4, "action": "LINE", "newRow": False, "flags": []},
        {"id": 5, "action": "LINE", "newRow": False, "flags": []},
    ], seg_map_diff)
    assert conv2 == {"rows": [_r("LINE", 3), _r("LINE", 4, 5)], "ignoredSegIds": []}, conv2
    # 문장 끝 부호나 길이 상한을 넘으면 newRow=False여도 과병합하지 않고 새 행으로 분리한다
    seg_map_long = {
        3: {"speaker": "S1", "lang": "ko", "text": "이미 충분히 긴 한국어 자막 문장입니다.", "ko": None, "startMs": 0, "endMs": 3000},
        4: {"speaker": "S1", "lang": "ko", "text": "다음 문장이 바로 이어집니다.", "ko": None, "startMs": 3000, "endMs": 6000},
        5: {"speaker": "?", "lang": "ja", "text": "abc", "ko": "음", "startMs": 6000, "endMs": 7000},
    }
    conv3 = items_to_rows([
        {"id": 3, "action": "LINE", "newRow": True, "flags": []},
        {"id": 4, "action": "LINE", "newRow": False, "flags": []},
        {"id": 5, "action": "LINE", "newRow": True, "flags": []},
    ], seg_map_long)
    assert conv3 == {"rows": [_r("LINE", 3), _r("LINE", 4), _r("LINE", 5, flags=["SPEAKER_UNSURE", "TEXT_UNSURE"])], "ignoredSegIds": []}, conv3
    # FREE 영상은 질문 action을 허용하지 않는다
    assert "QUESTION_JA" not in actions_for("FREE", True) and "QUESTION_KO" not in actions_for("FREE", True)

    # 빈 한국어 후보는 BOTH_JA_LINE 이중 사용 대상이 아니다
    assert not has_ko_candidate({"ko": ""}) and not has_ko_candidate({"ko": "  "}) and has_ko_candidate({"ko": "답변"})
    # 짧은 영상(20구간)의 대화 발췌는 중복·역전 없이 시간순 한 번만 들어간다
    short_segs = [{"id": i, "startMs": i * 1000, "endMs": (i + 1) * 1000, "speaker": "S0", "lang": "ko", "text": f"발언 {i}"}
                  for i in range(1, 21)]
    _, prof = speaker_profile(short_segs)
    assert "연속 대화 발췌(시간 순):" in prof and prof.count("발언 13") == 1, prof

    _k, _i, _c = settle([_r("LINE", 1, 2), _r("QUESTION_JA", 4, 7), _r("LINE", 5, 6)], [3, 8], 1, False)
    assert _c == 4 and _k == [_r("LINE", 1, 2)] and _i == [3], (_k, _i, _c)
    assert settle([_r("LINE", 1, 2, 3)], [], 1, False)[2] is None
    assert settle([], [1, 2], 1, False) == ([], [1, 2], None)
    assert [len(x) for x in split_words([{"text": "가."}, {"text": " 나"}, {"text": "다?"}, {"text": " 라"}])] == [1, 2, 1]
    assert mmss(398_000) == "6:38"


if __name__ == "__main__":
    _selftest()
    if len(sys.argv) > 1:
        main()

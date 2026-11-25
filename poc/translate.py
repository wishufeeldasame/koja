"""정답 시트의 한국어 행을 로컬 llama-server로 일본어 번역한다(번역 모델 비교용, 설계 7장).
입력: eval/work/sheet/<샘플>.json 의 한국어 행. 출력: eval/work/mt/<모델>/<샘플>.json, meta.json
행 묶음 단위로 보내고 앞 행(원문+번역)을 문맥으로 준다. 응답의 행 ID가 요청과 같은 순서·개수인지 코드로 검증하고,
틀리면 한 번 다시 묻고, 그래도 틀리면 행 하나씩 보낸다.
사용: uv run python translate.py <모델 키> <샘플>... (모델 키: e4b, q9b, g26b)
서버 인자 추가: 환경변수 KOJA_LLAMA_ARGS (공백 구분)."""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request

from common import work_dir

MODELS = {
    "e4b": "gemma-4-E4B-it-Q4_K_M.gguf",
    "q9b": "Qwen3.5-9B-Q4_K_M.gguf",
    "g26b": "gemma-4-26B-A4B-it-UD-Q4_K_M.gguf",
}
BATCH = 8
CONTEXT = 4
# 로컬 서버 요청이 HTTP_PROXY 설정을 타고 밖으로 나가지 않게 프록시를 끈다(샘플 외부 전송 금지)
LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))

SYSTEM = (
    "あなたは韓国語から日本語への字幕翻訳者です。韓国のeスポーツ(VALORANT)プロ選手へのインタビュー回答を翻訳します。"
    "話し言葉の内容を省略・追加せず、自然な日本語の丁寧語(です・ます)にしてください。"
    "チーム名・選手名・大会名・ゲーム用語は一般的な表記(英字表記があれば英字)にしてください。"
    "出力は指定のJSONだけにしてください。"
)


def gpu_in_use():
    """맥 IOAccelerator 의 GPU 사용 메모리(바이트). llama-server 의 Metal 버퍼는 /usr/bin/time footprint 에 안 잡혀서 따로 잰다."""
    # shortcut: 시스템 전체 값이라 다른 앱의 GPU 사용도 섞인다. 측정 중 다른 GPU 작업을 띄우지 않는다.
    out = subprocess.run(["ioreg", "-r", "-d", "1", "-c", "IOAccelerator"], capture_output=True, text=True).stdout
    return sum(int(x) for x in re.findall(r'"In use system memory"=(\d+)', out))


def free_port():
    # 고정 포트를 쓰면 이미 떠 있는 다른 서버(다른 모델)에 원문을 보낼 수 있다
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class GpuPeak(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.base = self.peak = gpu_in_use() if sys.platform == "darwin" else 0
        self.stop = threading.Event()

    def run(self):
        while sys.platform == "darwin" and not self.stop.wait(1):
            self.peak = max(self.peak, gpu_in_use())


class Server:
    def __init__(self, key, out_dir, ctx=8192):
        self.log = out_dir / "server_time.txt"
        self.model = MODELS[key]
        self.port = free_port()
        server = os.environ.get("LLAMA_SERVER", shutil.which("llama-server") or "llama-server")
        cmd = [server, "-m", str(work_dir("models", "llm") / self.model), "--host", "127.0.0.1", "--port", str(self.port),
               "-ngl", "99", "-c", str(ctx), "-np", "1", "--reasoning", "off"]
        # 8GB 카드에서 26B는 MoE 전문가 일부를 CPU로 내린다(예: KOJA_LLAMA_ARGS="--n-cpu-moe 20")
        cmd += os.environ.get("KOJA_LLAMA_ARGS", "").split()
        # 맥에서는 서버 최대 메모리를 /usr/bin/time 으로 잰다. 윈도우는 nvidia-smi 로 따로 잰다.
        self.timed = sys.platform == "darwin"
        self.p = subprocess.Popen((["/usr/bin/time", "-l"] if self.timed else []) + cmd,
                                  stdout=subprocess.DEVNULL, stderr=open(self.log, "w", encoding="utf-8"))

    def wait_ready(self):
        """우리가 띄운 프로세스가 살아 있고, 응답한 서버가 이 모델을 올렸는지까지 확인한다."""
        url = f"http://127.0.0.1:{self.port}"
        for _ in range(600):
            if self.p.poll() is not None:
                raise SystemExit(f"llama-server가 시작 중 종료됨. 로그: {self.log}")
            try:
                if json.loads(LOCAL.open(f"{url}/health", timeout=1).read()).get("status") == "ok":
                    props = json.loads(LOCAL.open(f"{url}/props", timeout=5).read())
                    if self.p.poll() is None and str(props.get("model_path", "")).endswith(self.model):
                        return
                    raise SystemExit(f"포트 {self.port}에서 다른 서버가 응답함. 로그: {self.log}")
            except (OSError, ValueError):
                pass
            time.sleep(1)
        raise SystemExit(f"llama-server 준비 시간 초과. 로그: {self.log}")

    def chat(self, messages, ids):
        schema = {"type": "array", "minItems": len(ids), "maxItems": len(ids),
                  "items": {"type": "object", "required": ["id", "ja"],
                            "properties": {"id": {"type": "integer"}, "ja": {"type": "string"}}}}
        body = {"messages": messages, "temperature": 0, "max_tokens": 2048,
                "response_format": {"type": "json_schema", "json_schema": {"schema": schema}}}
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        r = json.loads(LOCAL.open(req, timeout=900).read())
        return r["choices"][0]["message"]["content"], r.get("usage", {})

    def close(self):
        if self.timed:  # /usr/bin/time 이 아니라 그 자식(서버)을 끝내야 time 이 최대 메모리를 기록한다
            subprocess.run(["pkill", "-TERM", "-P", str(self.p.pid)])
        else:
            self.p.terminate()
        self.p.wait()


def prompt(batch, done):
    ctx = "\n".join(f"[{r['id']}] {r['ko']}\n→ {r['ja']}" for r in done[-CONTEXT:])
    src = "\n".join(f"[{r['id']}] {r['ko']}" for r in batch)
    user = (f"直前の文脈(翻訳済み):\n{ctx or '(なし)'}\n\n" if done else "") + \
        f"次の各行を日本語に翻訳し、[{{\"id\": 行番号, \"ja\": 訳文}}] の配列で返してください。\n{src}"
    return [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}]


def parse(content, ids):
    """응답이 요청한 행 ID를 같은 순서로 한 번씩 담았을 때만 받아들인다(설계 원칙 1의 검증과 같은 방식)."""
    try:
        out = json.loads(content)
    except json.JSONDecodeError:
        return None
    if not isinstance(out, list) or [o.get("id") if isinstance(o, dict) else None for o in out] != ids:
        return None
    if any(not isinstance(o.get("ja"), str) or not o["ja"].strip() for o in out):
        return None
    return [o["ja"].strip() for o in out]


def translate(srv, rows, stats):
    done = []
    for i in range(0, len(rows), BATCH):
        batch = rows[i:i + BATCH]
        ids = [r["id"] for r in batch]
        ja = None
        for _ in range(2):
            content, usage = srv.chat(prompt(batch, done), ids)
            stats["completionTokens"] += usage.get("completion_tokens", 0)
            if (ja := parse(content, ids)) is not None:
                break
            stats["retries"] += 1
        if ja is None:  # 묶음이 계속 어긋나면 행 하나씩
            stats["fallbackBatches"] += 1
            ja = []
            for r in batch:
                content, usage = srv.chat(prompt([r], done), [r["id"]])
                stats["completionTokens"] += usage.get("completion_tokens", 0)
                one = parse(content, [r["id"]])
                ja.append(one[0] if one else None)
                stats["failedRows"] += one is None
        done += [{**r, "ja": j or ""} for r, j in zip(batch, ja)]
    return done


def main():
    key, names = sys.argv[1], sys.argv[2:]
    # 실행 하나의 번역·측정을 임시 폴더에 모았다가 모든 샘플이 끝났을 때만 mt/<모델>/ 로 바꿔 놓는다.
    # 중간에 끊긴 실행이 이전 실행의 meta.json 과 섞여 평가되지 않게 하려는 것이다.
    final = work_dir("mt", key)
    tmp = final.with_name(key + ".partial")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    gpu = GpuPeak()
    gpu.start()
    t0 = time.time()
    srv = Server(key, tmp)
    meta = {"model": MODELS[key], "llamaArgs": os.environ.get("KOJA_LLAMA_ARGS", ""), "samples": {}}
    try:
        srv.wait_ready()
        meta["loadSec"] = round(time.time() - t0, 1)
        for name in names:
            sheet = json.loads((work_dir("sheet") / f"{name}.json").read_text(encoding="utf-8"))
            rows = [{"id": r["sheetRow"], "ko": r["ko"]} for r in sheet if "startMs" in r]
            stats = {"completionTokens": 0, "retries": 0, "fallbackBatches": 0, "failedRows": 0}
            t1 = time.time()
            out = translate(srv, rows, stats)
            sec = time.time() - t1
            (tmp / f"{name}.json").write_text(
                json.dumps([{"id": r["id"], "ja": r["ja"]} for r in out], ensure_ascii=False), encoding="utf-8")
            meta["samples"][name] = {"rows": len(rows), "koChars": sum(len(r["ko"]) for r in rows),
                                     "sec": round(sec, 1), **stats}
            print(key, name, meta["samples"][name], flush=True)
    finally:  # 준비 중 중단돼도 서버가 GPU 메모리를 잡고 남지 않게
        srv.close()
        gpu.stop.set()
    # 맥 외에는 자동 측정이 없다(윈도우는 nvidia-smi). 0이 아니라 미측정으로 남긴다.
    meta["gpuPeakGB"] = round((gpu.peak - gpu.base) / 1e9, 2) if sys.platform == "darwin" else None
    (tmp / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    shutil.rmtree(final)
    tmp.rename(final)


if __name__ == "__main__":
    assert parse('[{"id": 3, "ja": "a"}, {"id": 5, "ja": "b"}]', [3, 5]) == ["a", "b"]
    assert parse('[{"id": 5, "ja": "b"}, {"id": 3, "ja": "a"}]', [3, 5]) is None
    assert parse('[{"id": 3, "ja": " "}]', [3]) is None and parse("x", [3]) is None
    main()

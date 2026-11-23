#!/bin/sh
# 엔진을 하나씩 순서대로 돌린다(GPU는 한 번에 한 단계). 로그·최대 메모리는 eval/work/logs/.
# 사용: KOJA_DATA_ROOT=... ./run_all.sh stt qwen univa ...  /  ./run_all.sh diar sherpa-eres2net ...
set -u
kind=$1; shift
logs="$KOJA_DATA_ROOT/eval/work/logs"; mkdir -p "$logs"
for e in "$@"; do
  /usr/bin/time -l .venv/bin/python "$kind.py" "$e" C0008-003 C0003-002 C0004-001 > "$logs/${kind}_$e.log" 2>&1
  echo "$kind $e exit=$? $(grep -E 'peak memory footprint' "$logs/${kind}_$e.log" | awk '{print $1}')"
done

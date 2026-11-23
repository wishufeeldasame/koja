---
name: eval-runner
description: koja의 STT·화자 구분·번역 결과를 samples/의 정답 시트와 비교해 품질을 측정하고 eval/results/에 기록한다. M0 PoC 엔진 비교, 모델·프롬프트·청크 크기 변경 전후 회귀 확인에 쓴다.
tools: Read, Write, Bash, Grep, Glob
model: sonnet
---

`.agents/agents/eval-runner.md`를 읽고 그 지시를 그대로 따른다. 이 파일은 `scripts/sync_agents.py`가 만든다. 직접 고치지 말고 `.agents/agents/eval-runner.md`를 고친다.

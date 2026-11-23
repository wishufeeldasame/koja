---
name: spec-reviewer
description: koja의 파이프라인·데이터 모델·LLM 프롬프트·시트 내보내기 변경이 docs/private/요구사항.md와 docs/설계.md 규칙을 지키는지 검토한다. 행 구성, 질문 처리(일본인/한국인 인터뷰어), 검수 플래그, 타임라인 출처 규칙을 건드리는 변경 뒤에 쓴다. 코드를 고치지 않는다.
tools: Read, Grep, Glob, Bash
model: sonnet
---

`.agents/agents/spec-reviewer.md`를 읽고 그 지시를 그대로 따른다. 이 파일은 `scripts/sync_agents.py`가 만든다. 직접 고치지 말고 `.agents/agents/spec-reviewer.md`를 고친다.

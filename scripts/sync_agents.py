"""Generate per-tool agent stubs from the canonical `.agents/agents/*.md`.

Canonical file (read directly by Antigravity): frontmatter `name`, `description` + body.
Stubs for Claude Code, Codex and OpenCode only point at the canonical file, so the
prompt lives in one place.

    python3 scripts/sync_agents.py          # write stubs
    python3 scripts/sync_agents.py --check  # exit 1 if stubs are stale
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / ".agents" / "agents"
READ_ONLY = {"spec-reviewer", "cross-platform-checker"}


def parse(path):
    m = re.match(r"---\n(.*?)\n---\n", path.read_text(encoding="utf-8"), re.S)
    if not m:
        sys.exit(f"{path}: frontmatter 없음")
    fm = dict(line.split(": ", 1) for line in m.group(1).splitlines() if ": " in line)
    if fm.get("name") != path.stem or not fm.get("description"):
        sys.exit(f"{path}: name은 파일명과 같아야 하고 description이 필요함")
    return fm["name"], fm["description"]


def pointer(name):
    rel = f".agents/agents/{name}.md"
    return f"`{rel}`를 읽고 그 지시를 그대로 따른다. 이 파일은 `scripts/sync_agents.py`가 만든다. 직접 고치지 말고 `{rel}`를 고친다.\n"


def stubs(name, desc):
    ro = name in READ_ONLY
    claude_tools = "Read, Grep, Glob, Bash" if ro else "Read, Write, Bash, Grep, Glob"
    opencode_perm = (
        "permissions:\n"
        "  - action: edit\n"
        '    resource: "*"\n'
        "    effect: deny\n"
        if ro
        else ""
    )
    toml_desc = desc.replace("\\", "\\\\").replace('"', '\\"')
    return {
        ROOT / ".claude" / "agents" / f"{name}.md": (
            f"---\nname: {name}\ndescription: {desc}\ntools: {claude_tools}\nmodel: sonnet\n---\n\n{pointer(name)}"
        ),
        ROOT / ".codex" / "agents" / f"{name}.toml": (
            f'name = "{name}"\ndescription = "{toml_desc}"\ndeveloper_instructions = """\n{pointer(name)}"""\n'
        ),
        ROOT / ".opencode" / "agents" / f"{name}.md": (
            f"---\ndescription: {desc}\nmode: subagent\n{opencode_perm}---\n\n{pointer(name)}"
        ),
    }


STUB_GLOBS = [".claude/agents/*.md", ".codex/agents/*.toml", ".opencode/agents/*.md"]


def main():
    check = "--check" in sys.argv
    stale, expected = [], set()
    for src in sorted(SRC.glob("*.md")):
        for path, text in stubs(*parse(src)).items():
            expected.add(path)
            if path.exists() and path.read_text(encoding="utf-8") == text:
                continue
            stale.append(path.relative_to(ROOT))
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                with open(path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
    # 원본을 지웠는데 남아 있는 생성 파일
    orphans = [p for g in STUB_GLOBS for p in ROOT.glob(g) if p not in expected]
    for p in stale:
        print(("stale: " if check else "wrote: ") + str(p))
    for p in orphans:
        print(("orphan: " if check else "removed: ") + str(p.relative_to(ROOT)))
        if not check:
            p.unlink()
    sys.exit(1 if check and (stale or orphans) else 0)


if __name__ == "__main__":
    main()

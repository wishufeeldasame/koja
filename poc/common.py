import os
import shutil
from pathlib import Path

# 샘플·산출물은 .gitignore 대상이라 worktree에는 없다. 원래 체크아웃을 KOJA_DATA_ROOT로 지정한다.
DATA_ROOT = Path(os.environ.get("KOJA_DATA_ROOT", Path(__file__).resolve().parents[1]))
FFMPEG = os.environ.get("FFMPEG", shutil.which("ffmpeg") or "ffmpeg")


def work_dir(*parts: str) -> Path:
    p = DATA_ROOT.joinpath("eval", "work", *parts)
    p.mkdir(parents=True, exist_ok=True)
    return p

"""윈도우 테스트 담당자에게 줄 zip을 만든다(맥·윈도우 어디서든 실행).
구성: koja-test/{0_READ_ME_FIRST.txt, 1_setup.bat, 2_run_test.bat, samples/, poc/}. 모델·영상은 넣지 않는다(설치 때 받음).
사용: uv run python windows/make_package.py [출력 zip 경로]  (기본: eval/work/dist/koja-windows-test.zip)"""
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
POC = HERE.parent
TOP = "koja-test"


def poc_files():
    # 저장소에 올라간 파일만 넣는다(.venv·캐시·비공개 산출물이 섞이지 않게)
    out = subprocess.run(["git", "ls-files", "--", "."], cwd=POC, capture_output=True, text=True, check=True).stdout
    return [POC / p for p in out.split() if not p.startswith("windows/")]


def crlf(text: str) -> bytes:
    return text.replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8")


def main():
    sys.path.insert(0, str(POC))
    from common import work_dir  # KOJA_DATA_ROOT 기준 eval/work (커밋 안 되는 곳)
    dest = Path(sys.argv[1]) if len(sys.argv) > 1 else work_dir("dist") / "koja-windows-test.zip"
    dest.parent.mkdir(parents=True, exist_ok=True)
    files = poc_files()
    for need in ("win_setup.py", "win_run.py", "translate.py", "mt_eval.py", "uv.lock"):
        assert POC / need in files, f"{need} 가 git 에 없음(커밋 먼저)"
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        # 메모장이 한글을 확실히 읽도록 BOM, bat 은 cmd 라벨 처리 때문에 CRLF
        z.writestr(f"{TOP}/0_READ_ME_FIRST.txt", b"\xef\xbb\xbf" + crlf((HERE / "0_READ_ME_FIRST.txt").read_text(encoding="utf-8")))
        for bat in ("1_setup.bat", "2_run_test.bat"):
            z.writestr(f"{TOP}/{bat}", crlf((HERE / bat).read_text(encoding="ascii")))
        z.writestr(f"{TOP}/samples/PUT_VIDEOS_AND_SHEET_HERE.txt", b"\xef\xbb\xbf" + crlf("이 폴더에 영상(.MP4) 3개와 sheet.xlsx 를 넣으세요.\n"))
        for p in files:
            z.write(p, f"{TOP}/poc/{p.relative_to(POC).as_posix()}")
    print(dest, f"{dest.stat().st_size / 1e3:.0f} KB", len(files) + 4, "files")


if __name__ == "__main__":
    main()

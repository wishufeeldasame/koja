"""원본 영상에서 16kHz mono WAV를 한 번만 뽑는다. 이후 실험은 이 WAV만 쓴다."""
import argparse
import subprocess
from pathlib import Path

from common import DATA_ROOT, FFMPEG, work_dir


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=Path, default=DATA_ROOT / "samples")
    args = ap.parse_args()
    out_dir = work_dir("audio")
    for src in sorted(args.samples.glob("*.MP4")) + sorted(args.samples.glob("*.mp4")):
        dst = out_dir / f"{src.stem}.wav"
        if dst.exists():
            print("skip", dst.name)
            continue
        tmp = dst.with_suffix(".tmp.wav")
        subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", str(src),
                        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(tmp)], check=True)
        tmp.rename(dst)  # 중간에 끊겨도 반쪽 WAV가 남지 않게
        print("ok", dst.name)


if __name__ == "__main__":
    main()

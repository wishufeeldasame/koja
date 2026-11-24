#!/usr/bin/env bash
# 설치파일에 넣을 LGPL ffmpeg를 OUT(기본 app/build/ffmpeg)에 만든다(D-037, D-042).
#   윈도우(Git Bash): BtbN win64-lgpl 월말 빌드를 받는다.
#   맥: FFmpeg 소스 릴리스를 LGPL 옵션으로 빌드한다.
# 둘 다 SHA-256을 확인한다. OUT의 NOTICE.txt가 같으면 받기·빌드를 건너뛰고 검사만 한다.
set -euo pipefail

OUT="${1:-app/build/ffmpeg}"

FFMPEG_VERSION=9.0.2
SRC_URL="https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
SRC_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e

WIN_TAG=autobuild-2026-10-31-12-59
WIN_NAME=ffmpeg-n9.0.2-39-g7adfbe40dc-win64-lgpl-9.0
WIN_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/$WIN_TAG/$WIN_NAME.zip"
WIN_SHA256=9d37c564e0d01c18cde184a079fa6ef04d7d2783ada41da81d04165cc8f1a650

case "$(uname -s)" in
  Darwin) os=mac; exe=ffmpeg; encoders=(h264_videotoolbox) ;;
  MINGW* | MSYS* | CYGWIN*) os=win; exe=ffmpeg.exe; encoders=(h264_nvenc h264_mf) ;;
  *) echo "지원하지 않는 OS: $(uname -s)" >&2; exit 1 ;;
esac

sha256() {
  if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

download() { # url sha256 dest
  curl -fsSL --retry 3 -o "$3" "$1"
  local got
  got=$(sha256 "$3")
  if [ "$got" != "$2" ]; then
    echo "SHA-256 불일치: $1 (기대 $2, 실제 $got)" >&2
    exit 1
  fi
}

if [ "$os" = mac ]; then
  notice="FFmpeg $FFMPEG_VERSION (LGPL 2.1 이상), koja CI에서 소스로 빌드
소스: $SRC_URL
SHA-256: $SRC_SHA256
라이선스 전문: COPYING.LGPLv2.1"
else
  notice="FFmpeg $WIN_NAME (LGPL 2.1 이상), BtbN/FFmpeg-Builds 정적 빌드
빌드: https://github.com/BtbN/FFmpeg-Builds/releases/tag/$WIN_TAG
빌드 스크립트·소스: https://github.com/BtbN/FFmpeg-Builds , https://ffmpeg.org/download.html
SHA-256: $WIN_SHA256
라이선스 전문: LICENSE.txt"
fi

if [ -f "$OUT/$exe" ] && [ "$(cat "$OUT/NOTICE.txt" 2> /dev/null)" = "$notice" ]; then
  echo "이미 있음: $OUT/$exe"
else
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  rm -rf "$OUT"
  mkdir -p "$OUT"
  if [ "$os" = mac ]; then
    download "$SRC_URL" "$SRC_SHA256" "$work/src.tar.xz"
    tar -xJf "$work/src.tar.xz" -C "$work"
    src="$work/ffmpeg-$FFMPEG_VERSION"
    # --disable-autodetect: 빌드 PC의 Homebrew 라이브러리(SDL, lzma 등)가 링크되면 다른 맥에서 안 돈다.
    (cd "$src" && ./configure --disable-gpl --disable-nonfree --disable-autodetect \
      --enable-videotoolbox --enable-audiotoolbox --enable-zlib \
      --disable-ffplay --disable-ffprobe --disable-doc --disable-debug \
      && make -j"$(sysctl -n hw.ncpu)")
    cp "$src/ffmpeg" "$OUT/ffmpeg"
    cp "$src/COPYING.LGPLv2.1" "$OUT/"
  else
    download "$WIN_URL" "$WIN_SHA256" "$work/ffmpeg.zip"
    unzip -q -j "$work/ffmpeg.zip" "$WIN_NAME/bin/ffmpeg.exe" "$WIN_NAME/LICENSE.txt" -d "$OUT"
  fi
  printf '%s\n' "$notice" > "$OUT/NOTICE.txt"
fi

# 검사: GPL·nonfree가 아니고, 앱이 쓰는 H.264 인코더(D-038)가 들어 있어야 한다.
bin="$OUT/$exe"
config=$("$bin" -hide_banner -buildconf)
if grep -qE -- '--enable-(gpl|nonfree)' <<< "$config"; then
  echo "GPL 또는 nonfree 빌드다" >&2
  exit 1
fi
grep -q "Lesser General Public License" <<< "$("$bin" -hide_banner -L)" || { echo "LGPL 빌드가 아니다" >&2; exit 1; }
list=$("$bin" -hide_banner -encoders)
for e in "${encoders[@]}"; do
  grep -qw "$e" <<< "$list" || { echo "인코더 없음: $e" >&2; exit 1; }
done
# grep -q를 파이프 끝에 두면 pipefail에서 앞 명령이 SIGPIPE로 실패할 수 있어 변수로 받는다.
if [ "$os" = mac ] && grep -qE '/(opt/homebrew|usr/local)/' <<< "$(otool -L "$bin")"; then
  echo "시스템 밖 동적 라이브러리에 링크됨" >&2
  otool -L "$bin" >&2
  exit 1
fi
"$bin" -hide_banner -version | head -1
echo "검사 통과: ${encoders[*]}"

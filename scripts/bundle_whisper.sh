#!/usr/bin/env bash
# 설치파일에 넣을 whisper.cpp whisper-server를 OUT(기본 app/build/whisper)에 만든다(D-059).
#   맥: 소스를 Metal로 빌드한다(정적 링크, Metal 셰이더 내장).
#   윈도우(Git Bash): 공식 CUDA 12.4 빌드 zip을 받는다. CUDA 런타임 DLL은 CUDA_OUT(기본 app/build/cuda)에 따로 둔다(D-060).
# SHA-256을 확인한다. NOTICE.txt가 같으면 받기·빌드를 건너뛰고 검사만 한다.
# 새로 만들 때는 임시 폴더에서 만들고 검사를 통과한 뒤에만 OUT을 바꾼다.
set -euo pipefail

OUT="${1:-app/build/whisper}"
CUDA_OUT="${2:-app/build/cuda}"
[ "$OUT" != "$CUDA_OUT" ] || { echo "whisper와 cuda 출력 폴더가 같다: $OUT" >&2; exit 1; }

# 윈도우 실측(poc/WINDOWS.md)에 쓴 빌드와 같은 커밋으로 맞춘다.
WHISPER_TAG=b5907
WHISPER_COMMIT=5eaee0cc9024eb9e9a99469199920c8b7f5cb9ec
SRC_URL="https://github.com/ggml-org/whisper.cpp/archive/$WHISPER_COMMIT.tar.gz"
SRC_SHA256=2779bca626cadd18674fdd41ffa73e4fbf2dd90df5977d96fd36c97c1c9b68c9
LICENSE_URL="https://raw.githubusercontent.com/ggml-org/whisper.cpp/$WHISPER_COMMIT/LICENSE"
LICENSE_SHA256=94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d
WIN_URL="https://github.com/ggml-org/whisper.cpp/releases/download/$WHISPER_TAG/whisper-bin-win-cuda-12.4.0-x64.zip"
WIN_SHA256=34f872ce724ca1ff0a4afca481853e5ee74250f8cb22153e499f953562846039
# whisper-server.exe가 바로 또는 백엔드 DLL을 거쳐 읽는 것만 넣는다(PE import 표 확인, 2026-11-26).
WIN_FILES=(whisper-server.exe whisper.dll ggml.dll ggml-base.dll ggml-cuda.dll
  ggml-cpu-x64.dll ggml-cpu-sse42.dll ggml-cpu-sandybridge.dll ggml-cpu-haswell.dll ggml-cpu-skylakex.dll
  ggml-cpu-cannonlake.dll ggml-cpu-cascadelake.dll ggml-cpu-icelake.dll ggml-cpu-alderlake.dll)
CUDA_FILES=(cudart64_12.dll cublas64_12.dll cublasLt64_12.dll)
# NVIDIA 재배포 목록(redistrib_12.4.x.json)의 cuda_cudart·libcublas license_path가 가리키는 같은 EULA 전문. 버전 없는 주소라 바뀌면 해시가 달라져 멈춘다.
CUDA_LICENSE_URL=https://developer.download.nvidia.com/compute/cuda/redist/cuda_cudart/LICENSE.txt
CUDA_LICENSE_SHA256=e2c71babfd18a8e69542dd7e9ca018f9caa438094001a58e6bc4d8c999bf0d07

# ffmpeg 번들과 같은 최소 macOS(D-042).
MACOS_MIN=13.0

case "$(uname -s)" in
  Darwin) os=mac; exe=whisper-server ;;
  MINGW* | MSYS* | CYGWIN*) os=win; exe=whisper-server.exe ;;
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

# 이 스크립트가 만든 폴더(NOTICE.txt 첫 줄이 $2로 시작, 버전이 바뀌어도 같음)이거나 빈 폴더일 때만 덮어쓴다. ffmpeg 등 다른 번들 폴더를 지우지 않게 한다.
check_ours() { # dir prefix
  [ -e "$1" ] || return 0
  [ -d "$1" ] || { echo "폴더가 아니라서 덮어쓰지 않는다: $1" >&2; exit 1; }
  [ -n "$(ls -A "$1")" ] || return 0
  if [[ "$(head -1 "$1/NOTICE.txt" 2> /dev/null)" != "$2"* ]]; then
    echo "이 스크립트가 만든 폴더가 아니라서 덮어쓰지 않는다: $1" >&2
    exit 1
  fi
}

if [ "$os" = mac ]; then
  notice="whisper.cpp $WHISPER_TAG (MIT), koja CI에서 소스로 빌드(Metal, macOS $MACOS_MIN 이상)
소스: https://github.com/ggml-org/whisper.cpp/tree/$WHISPER_COMMIT
SHA-256: $SRC_SHA256
빌드 방법: https://github.com/wishufeeldasame/koja/blob/develop/scripts/bundle_whisper.sh
라이선스 전문: LICENSE"
else
  notice="whisper.cpp $WHISPER_TAG (MIT), 공식 윈도우 CUDA 12.4 빌드
빌드: https://github.com/ggml-org/whisper.cpp/releases/tag/$WHISPER_TAG
소스: https://github.com/ggml-org/whisper.cpp/tree/$WHISPER_COMMIT
SHA-256: $WIN_SHA256
CUDA 런타임 DLL은 ../cuda/ (NVIDIA CUDA 12.4, cuda/NOTICE.txt)
라이선스 전문: LICENSE"
  cuda_notice="NVIDIA CUDA 12.4 런타임 DLL: ${CUDA_FILES[*]}
whisper.cpp $WHISPER_TAG 윈도우 CUDA 12.4 빌드에 들어 있는 것을 그대로 넣었다: $WIN_URL
SHA-256: $WIN_SHA256
NVIDIA CUDA Toolkit EULA 부록 A(재배포 가능 파일)에 따라 배포한다: https://docs.nvidia.com/cuda/eula/
라이선스 전문: LICENSE.txt ($CUDA_LICENSE_URL, SHA-256 $CUDA_LICENSE_SHA256)
whisper-server와 llama-server가 함께 쓴다(koja D-060)."
fi

stage="$OUT"
cuda_stage="$CUDA_OUT"
ready=false
if [ -f "$OUT/$exe" ] && [ -f "$OUT/LICENSE" ] && [ "$(cat "$OUT/NOTICE.txt" 2> /dev/null)" = "$notice" ]; then
  ready=true
  if [ "$os" = win ] && { [ ! -f "$CUDA_OUT/LICENSE.txt" ] || [ "$(cat "$CUDA_OUT/NOTICE.txt" 2> /dev/null)" != "$cuda_notice" ]; }; then
    ready=false
  fi
fi
if $ready; then
  echo "이미 있음: $OUT/$exe"
else
  check_ours "$OUT" "whisper.cpp "
  [ "$os" = win ] && check_ours "$CUDA_OUT" "NVIDIA CUDA "
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  stage="$work/out"
  cuda_stage="$work/cuda"
  mkdir -p "$stage"
  if [ "$os" = mac ]; then
    download "$SRC_URL" "$SRC_SHA256" "$work/src.tar.gz"
    tar -xzf "$work/src.tar.gz" -C "$work"
    src="$work/whisper.cpp-$WHISPER_COMMIT"
    # 정적 링크 + 셰이더 내장으로 실행 파일 하나만 넣는다. OPENMP·NATIVE는 빌드 PC의 Homebrew libomp·CPU에 묶이지 않게 끈다.
    cmake -S "$src" -B "$work/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOS_MIN" \
      -DBUILD_SHARED_LIBS=OFF -DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF \
      -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_SERVER=ON -DWHISPER_SDL2=OFF -DWHISPER_CURL=OFF
    cmake --build "$work/build" --config Release --target whisper-server -j"$(sysctl -n hw.ncpu)"
    cp "$work/build/bin/whisper-server" "$stage/"
    cp "$src/LICENSE" "$stage/"
  else
    download "$WIN_URL" "$WIN_SHA256" "$work/whisper.zip"
    download "$LICENSE_URL" "$LICENSE_SHA256" "$stage/LICENSE"
    unzip -q -j "$work/whisper.zip" "${WIN_FILES[@]/#/Release/}" -d "$stage"
    mkdir -p "$cuda_stage"
    unzip -q -j "$work/whisper.zip" "${CUDA_FILES[@]/#/Release/}" -d "$cuda_stage"
    download "$CUDA_LICENSE_URL" "$CUDA_LICENSE_SHA256" "$cuda_stage/LICENSE.txt"
    printf '%s\n' "$cuda_notice" > "$cuda_stage/NOTICE.txt"
  fi
  printf '%s\n' "$notice" > "$stage/NOTICE.txt"
fi

# 검사: 실행되고, 맥은 시스템 밖 라이브러리에 링크되지 않고 최소 macOS가 맞아야 한다.
bin="$stage/$exe"
if [ "$os" = mac ]; then
  # grep -q를 파이프 끝에 두면 pipefail에서 앞 명령이 SIGPIPE로 실패할 수 있어 변수로 받는다.
  if grep -qE '/(opt/homebrew|usr/local)/|@rpath' <<< "$(otool -L "$bin")"; then
    echo "시스템 밖 동적 라이브러리에 링크됨" >&2
    otool -L "$bin" >&2
    exit 1
  fi
  minos=$(vtool -show-build "$bin" | awk '/minos/ {print $2}')
  [ "$minos" = "$MACOS_MIN" ] || { echo "최소 macOS가 $MACOS_MIN이 아니다: $minos" >&2; exit 1; }
else
  for f in "${WIN_FILES[@]}"; do [ -f "$stage/$f" ] || { echo "파일 없음: $f" >&2; exit 1; }; done
  for f in "${CUDA_FILES[@]}" LICENSE.txt; do [ -f "$cuda_stage/$f" ] || { echo "파일 없음: cuda/$f" >&2; exit 1; }; done
fi
# --help는 모델·GPU 없이 사용법만 찍고 끝난다. 실행 파일과 바로 링크된 DLL이 읽히는지만 본다.
help=$("$bin" --help 2>&1) || true
grep -q -- "--inference-path" <<< "$help" || { echo "whisper-server --help 실패: $help" >&2; exit 1; }
if [ "$stage" != "$OUT" ]; then
  rm -rf "$OUT"
  mkdir -p "$(dirname "$OUT")"
  mv "$stage" "$OUT"
  if [ "$os" = win ]; then
    rm -rf "$CUDA_OUT"
    mkdir -p "$(dirname "$CUDA_OUT")"
    mv "$cuda_stage" "$CUDA_OUT"
  fi
fi
echo "검사 통과: whisper.cpp $WHISPER_TAG → $OUT"

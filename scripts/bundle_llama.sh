#!/usr/bin/env bash
# 설치파일에 넣을 llama.cpp llama-server를 OUT(기본 app/build/llama)에 만든다(D-005, D-065, D-069).
#   맥(Apple Silicon): ggml-org/llama.cpp 공식 macos-arm64 Metal 빌드를 받는다.
#   윈도우(Git Bash): ggml-org/llama.cpp 공식 win-cuda-12.4-x64 빌드를 받는다.
#     CUDA 런타임 DLL은 CUDA_OUT(기본 app/build/cuda)에 공유 배치하며(D-060),
#     bundle_whisper.sh 등이 이미 준비한 유효한 cuda/가 있으면 덮어쓰지 않고 그대로 재사용한다.
# SHA-256을 확인한다. NOTICE.txt가 같으면 받기를 건너뛰고 검사만 한다.
# 새로 만들 때는 임시 폴더에서 만들고 검사를 통과한 뒤에만 OUT(및 필요 시 CUDA_OUT)을 바꾼다.
set -euo pipefail
unset CDPATH

OUT="${1:-app/build/llama}"
CUDA_OUT_ARG="${2-}"

parent_dir() { # os=win에서만 역슬래시를 구분자로 정규화하고 끝 슬래시를 제거한 뒤 상위 디렉터리를 반환한다.
  local p="$1"
  [ "$os" = win ] && p="${p//\\//}"
  while [ "${#p}" -gt 1 ] && [[ "$p" == */ ]]; do
    p="${p%/}"
  done
  local d
  d=$(dirname -- "$p")
  if [ "$os" = win ] && [[ "$d" == [A-Za-z]: ]]; then
    d="$d/"
  fi
  printf '%s' "$d"
}

reject_bad_target() { # 심볼릭 링크·끝 `.`/`..` 세그먼트·윈도우 UNC 경로를 배치 전에 거부한다.
  local raw="$1"
  local p="$raw"
  if [ "$os" = win ]; then
    p="${p//\\//}"
    if [[ "$p" == //* ]]; then
      echo "UNC 네트워크 경로는 지원하지 않는다: $raw" >&2
      exit 1
    fi
  fi
  while [ "${#p}" -gt 1 ] && [[ "$p" == */ ]]; do
    p="${p%/}"
  done
  case "$p" in
    .|..|*/.|*/..)
      echo "끝이 '.' 또는 '..'인 출력 경로는 사용할 수 없다: $raw" >&2
      exit 1
      ;;
  esac
  [ ! -L "$p" ] || { echo "출력 경로가 디렉터리가 아니라서 덮어쓰지 않는다: $raw" >&2; exit 1; }
}

norm_path() { # 상대·`.`·`..`·윈도우 표기(/c vs C:)와 심볼릭 링크를 세그먼트 단위로 물리 경로까지 해소한다.
  local p="$1"
  if [ "$os" = win ]; then
    if command -v cygpath > /dev/null 2>&1; then
      p=$(cygpath -u "$p")
    fi
    p="${p//\\//}"
  fi
  local cur
  if [ "$os" = win ] && [[ "$p" == [A-Za-z]:/* ]]; then
    cur="${p:0:2}"
    p="${p:2}"
  elif [[ "$p" == /* ]]; then
    cur=""
  else
    cur=$(pwd -P)
    cur="${cur%/}"
  fi
  local -a parts
  IFS='/' read -r -a parts <<< "$p"
  local part
  for part in "${parts[@]}"; do
    if [ -z "$part" ] || [ "$part" = "." ]; then
      continue
    elif [ "$part" = ".." ]; then
      if [ -n "$cur" ] && [ -d "$cur" ]; then
        cur=$(cd -- "$cur" && pwd -P)
        cur="${cur%/}"
      fi
      if [ "$os" = win ] && [[ "$cur" == [A-Za-z]: ]]; then
        continue
      fi
      if [[ "$cur" == */* ]]; then
        cur="${cur%/*}"
      else
        cur=""
      fi
    else
      cur="$cur/$part"
      if [ -d "$cur" ]; then
        cur=$(cd -- "$cur" && pwd -P)
        cur="${cur%/}"
      fi
    fi
  done
  printf '%s' "${cur:-/}"
}

LLAMA_TAG=b12655
LLAMA_BUILD=12655
LLAMA_COMMIT=969d7333172c522d6a60fffb8dd35288ce8df236
MACOS_MIN=13.3
GH_BASE="https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_TAG"

MAC_ARCHIVE="llama-$LLAMA_TAG-bin-macos-arm64.tar.gz"
MAC_URL="$GH_BASE/$MAC_ARCHIVE"
MAC_SHA256=03278e8ea62d83d89c9be49f7058d4dbc8a73948a94b77ef6f489e246cba2ae0

WIN_ARCHIVE="llama-$LLAMA_TAG-bin-win-cuda-12.4-x64.zip"
WIN_URL="$GH_BASE/$WIN_ARCHIVE"
WIN_SHA256=0dbd9120332552733dc94bd23582dc3bfb3a60879ae907ddedf3afdb8a628b06

WIN_CUDART_ARCHIVE="cudart-llama-bin-win-cuda-12.4-x64.zip"
WIN_CUDART_URL="$GH_BASE/$WIN_CUDART_ARCHIVE"
WIN_CUDART_SHA256=8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6

LICENSE_URL="https://raw.githubusercontent.com/ggml-org/llama.cpp/$LLAMA_COMMIT/LICENSE"
LICENSE_SHA256=94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d

# bundle_whisper.sh와 같은 NVIDIA CUDA 12.4 런타임 DLL·EULA 전문(부록 A 재배포·부록 B cuBLAS 제3자 고지 포함, D-060).
CUDA_FILES=(cudart64_12.dll cublas64_12.dll cublasLt64_12.dll)
CUDA_SHA256S=(
  d28e42265da7462162a54da6b7a99ea4fa2caf8139d862bb500db875d0b32dfc
  e40202fe4223c1cd2d2dce7beec59e1ed61c7801bd827309183be9b50e358f4c
  2a896460bef60ed57ef32b0875812f355a6984e671d638bb632f5e8c1d7a831f
)
CUDA_LICENSE_URL=https://developer.download.nvidia.com/compute/cuda/redist/cuda_cudart/LICENSE.txt
CUDA_LICENSE_SHA256=e2c71babfd18a8e69542dd7e9ca018f9caa438094001a58e6bc4d8c999bf0d07

case "$(uname -s)" in
  Darwin)
    arch="$(uname -m)"
    if [ "$arch" != "arm64" ]; then
      echo "맥은 Apple Silicon(arm64)만 지원한다: $arch" >&2
      exit 1
    fi
    os=mac
    exe=llama-server
    ;;
  MINGW* | MSYS* | CYGWIN*)
    os=win
    exe=llama-server.exe
    ;;
  *)
    echo "지원하지 않는 OS: $(uname -s)" >&2
    exit 1
    ;;
esac

# 출력 경로의 심볼릭 링크·끝 `.`/`..`·UNC를 먼저 거부한다. CUDA 출력 경로와 중첩 비교는 윈도우에서만 실행한다.
reject_bad_target "$OUT"
CUDA_OUT=""
if [ "$os" = win ]; then
  # 둘째 인자를 생략하면 OUT 형제 cuda/를 기본값으로 쓴다(기본 OUT=app/build/llama → app/build/cuda, D-060·D-069).
  if [ -n "$CUDA_OUT_ARG" ]; then
    CUDA_OUT="$CUDA_OUT_ARG"
  else
    out_dir=$(parent_dir "$OUT")
    CUDA_OUT="${out_dir%/}/cuda"
  fi
  reject_bad_target "$CUDA_OUT"
  out_cmp=$(norm_path "$OUT" | tr '[:upper:]' '[:lower:]')
  cuda_cmp=$(norm_path "$CUDA_OUT" | tr '[:upper:]' '[:lower:]')
  if [ "$out_cmp" = "$cuda_cmp" ] || [[ "$out_cmp" == "$cuda_cmp"/* ]] || [[ "$cuda_cmp" == "$out_cmp"/* ]]; then
    echo "llama와 cuda 출력 폴더가 같거나 서로 포함한다: $OUT / $CUDA_OUT" >&2
    exit 1
  fi
fi

sha256() { # 표준 입력으로 넘겨 역슬래시가 든 경로에서도 GNU sha256sum이 해시 앞에 `\`를 붙이지 않게 한다.
  if command -v sha256sum > /dev/null; then sha256sum -b < "$1"; else shasum -a 256 < "$1"; fi | cut -d' ' -f1
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

# 일반 파일·심볼릭 링크·다른 프로그램 폴더를 덮어쓰지 않도록 보호한다(D-065).
check_ours_llama() { # dir
  [ -e "$1" ] || [ -L "$1" ] || return 0
  if [ ! -d "$1" ] || [ -L "$1" ]; then
    echo "출력 경로가 디렉터리가 아니라서 덮어쓰지 않는다: $1" >&2
    exit 1
  fi
  [ -n "$(ls -A "$1")" ] || return 0
  local head_line
  head_line=$(head -1 "$1/NOTICE.txt" 2> /dev/null || true)
  if [[ "$head_line" == "llama.cpp "*"koja 번들)" ]] \
    || [ "$(cat "$1/.koja-llama-bundle" 2> /dev/null || true)" = "koja-llama-bundle-v1" ]; then
    return 0
  fi
  echo "이 스크립트가 만든 폴더가 아니라서 덮어쓰지 않는다: $1" >&2
  exit 1
}

check_ours_cuda() { # dir
  [ -e "$1" ] || [ -L "$1" ] || return 0
  if [ ! -d "$1" ] || [ -L "$1" ]; then
    echo "출력 경로가 디렉터리가 아니라서 덮어쓰지 않는다: $1" >&2
    exit 1
  fi
  [ -n "$(ls -A "$1")" ] || return 0
  if [[ "$(head -1 "$1/NOTICE.txt" 2> /dev/null || true)" != "NVIDIA CUDA "* ]] \
    || ! grep -qF "(koja D-060)" "$1/NOTICE.txt" 2> /dev/null; then
    echo "이 스크립트가 만든 CUDA 폴더가 아니라서 덮어쓰지 않는다: $1" >&2
    exit 1
  fi
}

# bundle_whisper.sh 또는 이전 단독 실행이 이미 무결한 공유 cuda/를 만들어 두었는지 SHA-256까지 확인한다.
cuda_ready() { # dir
  [ -d "$1" ] && [ ! -L "$1" ] || return 1
  [ -f "$1/LICENSE.txt" ] || return 1
  [ "$(sha256 "$1/LICENSE.txt")" = "$CUDA_LICENSE_SHA256" ] || return 1
  [[ "$(head -1 "$1/NOTICE.txt" 2> /dev/null || true)" == "NVIDIA CUDA "* ]] || return 1
  local i f
  for i in "${!CUDA_FILES[@]}"; do
    f="${CUDA_FILES[$i]}"
    [ -f "$1/$f" ] || return 1
    [ "$(sha256 "$1/$f")" = "${CUDA_SHA256S[$i]}" ] || return 1
  done
  return 0
}

if [ "$os" = mac ]; then
  notice="llama.cpp $LLAMA_TAG (MIT License), macOS arm64 Metal 공식 빌드 (macOS $MACOS_MIN 이상, koja 번들)
릴리스: https://github.com/ggml-org/llama.cpp/releases/tag/$LLAMA_TAG
커밋: https://github.com/ggml-org/llama.cpp/tree/$LLAMA_COMMIT
파일: $MAC_ARCHIVE
SHA-256: $MAC_SHA256
라이선스 전문: LICENSE"
else
  notice="llama.cpp $LLAMA_TAG (MIT License), Windows x64 CUDA 12.4 공식 빌드 (koja 번들)
릴리스: https://github.com/ggml-org/llama.cpp/releases/tag/$LLAMA_TAG
커밋: https://github.com/ggml-org/llama.cpp/tree/$LLAMA_COMMIT
파일: $WIN_ARCHIVE (SHA-256: $WIN_SHA256)
CUDA 런타임 DLL은 ../cuda/ (NVIDIA CUDA 12.4, cuda/NOTICE.txt)
라이선스 전문: LICENSE, LICENSE-LLVM-OpenMP"
  cuda_notice="NVIDIA CUDA 12.4 런타임 DLL: ${CUDA_FILES[*]}
llama.cpp $LLAMA_TAG 윈도우 CUDA 12.4 런타임 아카이브에서 추출: $WIN_CUDART_URL
SHA-256: $WIN_CUDART_SHA256
NVIDIA CUDA Toolkit EULA 부록 A(재배포 가능 파일)·부록 B(cuBLAS 제3자 고지)에 따라 배포한다: https://docs.nvidia.com/cuda/eula/
라이선스 전문: LICENSE.txt ($CUDA_LICENSE_URL, SHA-256 $CUDA_LICENSE_SHA256)
whisper-server와 llama-server가 함께 쓴다(koja D-060)."
fi

# 실행 파일·필수 백엔드 라이브러리·라이선스·소유권 마커가 모두 있는지 확인한다.
llama_files_ok() { # dir
  [ -f "$1/$exe" ] && [ -f "$1/LICENSE" ] && [ -f "$1/NOTICE.txt" ] || return 1
  [ "$(cat "$1/.koja-llama-bundle" 2> /dev/null || true)" = "koja-llama-bundle-v1" ] || return 1
  local req
  if [ "$os" = mac ]; then
    for req in \
      libllama-server-impl.dylib \
      libllama.dylib \
      libllama-common.dylib \
      libmtmd.dylib \
      libggml.dylib \
      libggml-base.dylib \
      libggml-cpu.dylib \
      libggml-metal.dylib; do
      [ -f "$1/$req" ] || return 1
    done
  else
    for req in \
      llama-server-impl.dll \
      llama.dll \
      llama-common.dll \
      mtmd.dll \
      ggml.dll \
      ggml-base.dll \
      ggml-cuda.dll \
      libomp.dll \
      LICENSE-LLVM-OpenMP; do
      [ -f "$1/$req" ] || return 1
    done
    local cpu_dlls=("$1"/ggml-cpu-*.dll)
    [ -f "${cpu_dlls[0]}" ] || return 1
  fi
  return 0
}

stage="$OUT"
cuda_stage="$CUDA_OUT"
need_llama=true
if llama_files_ok "$OUT" && [ "$(cat "$OUT/NOTICE.txt" 2> /dev/null || true)" = "$notice" ]; then
  need_llama=false
  if [ "$os" = win ]; then
    for f in "${CUDA_FILES[@]}"; do
      if [ -f "$OUT/$f" ]; then
        need_llama=true
        break
      fi
    done
  fi
fi
need_cuda=false
if [ "$os" = win ] && ! cuda_ready "$CUDA_OUT"; then
  need_cuda=true
fi

if ! $need_llama && ! $need_cuda; then
  echo "이미 있음: $OUT/$exe"
else
  $need_llama && check_ours_llama "$OUT"
  $need_cuda && check_ours_cuda "$CUDA_OUT"
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT

  if $need_llama; then
    stage="$work/out"
    mkdir -p "$stage"
    if [ "$os" = mac ]; then
      download "$MAC_URL" "$MAC_SHA256" "$work/$MAC_ARCHIVE"
      tar -xzf "$work/$MAC_ARCHIVE" -C "$work"
      src="$work/llama-$LLAMA_TAG"
      # llama-server 실행에 필요한 바이너리·동적 라이브러리·라이선스만 추려 설치파일 크기를 줄인다.
      cp -a \
        "$src/llama-server" \
        "$src/libllama-server-impl.dylib" \
        "$src"/libllama.dylib \
        "$src"/libllama.*.dylib \
        "$src"/libllama-common*.dylib \
        "$src"/libmtmd*.dylib \
        "$src"/libggml*.dylib \
        "$src/LICENSE" \
        "$stage/"
    else
      download "$WIN_URL" "$WIN_SHA256" "$work/$WIN_ARCHIVE"
      download "$LICENSE_URL" "$LICENSE_SHA256" "$stage/LICENSE"
      # llama-server.exe 실행과 동적 백엔드 로딩에 필요한 DLL만 푼다(CUDA 런타임은 공유 cuda/에 둔다, D-060).
      unzip -q -j "$work/$WIN_ARCHIVE" \
        "llama-server.exe" \
        "llama-server-impl.dll" \
        "llama.dll" \
        "llama-common.dll" \
        "mtmd.dll" \
        "ggml.dll" \
        "ggml-base.dll" \
        "ggml-cuda.dll" \
        "ggml-rpc.dll" \
        "ggml-cpu-*.dll" \
        "libomp.dll" \
        "LICENSE-LLVM-OpenMP" \
        -d "$stage"
    fi
    printf '%s\n' "$notice" > "$stage/NOTICE.txt"
    printf 'koja-llama-bundle-v1\n' > "$stage/.koja-llama-bundle"
  fi

  if $need_cuda; then
    cuda_stage="$work/cuda"
    mkdir -p "$cuda_stage"
    download "$WIN_CUDART_URL" "$WIN_CUDART_SHA256" "$work/$WIN_CUDART_ARCHIVE"
    unzip -q -j "$work/$WIN_CUDART_ARCHIVE" "${CUDA_FILES[@]}" -d "$cuda_stage"
    download "$CUDA_LICENSE_URL" "$CUDA_LICENSE_SHA256" "$cuda_stage/LICENSE.txt"
    printf '%s\n' "$cuda_notice" > "$cuda_stage/NOTICE.txt"
  fi
fi

# 검사: 실행 파일·백엔드 라이브러리·고지 파일·소유권 마커가 빠짐없이 있고 버전이 일치해야 한다.
bin="$stage/$exe"
llama_files_ok "$stage" || { echo "필수 파일·라이선스 또는 소유권 마커 누락: $stage" >&2; exit 1; }

if [ "$os" = mac ]; then
  for f in "$bin" "$stage"/*.dylib; do
    [ -L "$f" ] && continue
    deps=$(otool -L "$f")
    if grep -qE '/(opt/homebrew|usr/local)/' <<< "$deps"; then
      echo "시스템 밖 동적 라이브러리에 링크됨: $f" >&2
      echo "$deps" >&2
      exit 1
    fi
  done
  minos=$(vtool -show-build "$bin" | awk '/minos/ {print $2}')
  [ "$minos" = "$MACOS_MIN" ] || { echo "최소 macOS가 $MACOS_MIN이 아니다: $minos" >&2; exit 1; }
  ver=$("$bin" --version 2>&1)
  grep -q "build $LLAMA_BUILD" <<< "$ver" || { echo "버전 불일치: $ver" >&2; exit 1; }
  printf '%s\n' "$ver" | head -n 2
else
  for f in "${CUDA_FILES[@]}"; do
    [ ! -f "$stage/$f" ] || { echo "CUDA 런타임 DLL이 llama 폴더에 중복됨: $f" >&2; exit 1; }
  done
  cuda_ready "$cuda_stage" || { echo "공유 CUDA 폴더 검증 실패(파일 누락 또는 SHA-256 불일치): $cuda_stage" >&2; exit 1; }
  cuda_abs=$(cd -- "$cuda_stage" && pwd -P)
  ver=$(PATH="$cuda_abs:$PATH" "$bin" --version 2>&1)
  grep -q "build $LLAMA_BUILD" <<< "$ver" || { echo "버전 불일치: $ver" >&2; exit 1; }
  printf '%s\n' "$ver" | head -n 2
fi

if [ "$stage" != "$OUT" ]; then
  rm -rf "$OUT"
  mkdir -p "$(parent_dir "$OUT")"
  mv "$stage" "$OUT"
fi
if [ "$os" = win ] && [ "$cuda_stage" != "$CUDA_OUT" ]; then
  rm -rf "$CUDA_OUT"
  mkdir -p "$(parent_dir "$CUDA_OUT")"
  mv "$cuda_stage" "$CUDA_OUT"
fi
echo "검사 통과: llama-server $LLAMA_TAG → $OUT"

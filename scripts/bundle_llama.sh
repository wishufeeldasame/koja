#!/usr/bin/env bash
# 설치파일에 넣을 llama.cpp llama-server를 OUT(기본 app/build/llama)에 만든다(D-005, D-065).
#   맥(Apple Silicon): ggml-org/llama.cpp 공식 macos-arm64 Metal 빌드를 받는다.
#   윈도우(Git Bash): ggml-org/llama.cpp 공식 win-cuda-12.4-x64 빌드와 cudart DLL을 받는다.
# 둘 다 SHA-256을 확인한다. OUT의 .koja-llama-bundle 마커와 NOTICE.txt가 같으면 받기를 건너뛰고 검사만 한다.
# 새로 만들 때는 임시 폴더에서 만들고 검사를 통과한 뒤에만 OUT을 바꾼다.
set -euo pipefail

OUT="${1:-app/build/llama}"

LLAMA_TAG=b12655
LLAMA_BUILD=12655
LLAMA_COMMIT=969d7333172c522d6a60fffb8dd35288ce8df236
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

BUNDLE_MARKER=".koja-llama-bundle"
BUNDLE_MARKER_CONTENT="koja-llama-bundle-v1"

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

write_cublas_notice() { # dest
  cat << 'EOF' > "$1"
NVIDIA CUDA Toolkit 12.4 cuBLAS Third-Party Notices
Source: https://docs.nvidia.com/cuda/archive/12.4.1/eula/index.html#attachment-b

Some of the cuBLAS library routines were written by or derived from code written by Vasily Volkov and are subject to the Modified Berkeley Software Distribution License as follows:

Copyright (c) 2007-2009, Regents of the University of California

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of the University of California, Berkeley nor
      the names of its contributors may be used to endorse or promote
      products derived from this software without specific prior
      written permission.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

---

Some of the cuBLAS library routines were written by or derived from code written by Davide Barbieri and are subject to the Modified Berkeley Software Distribution License as follows:

Copyright (c) 2008-2009 Davide Barbieri @ University of Rome Tor Vergata.

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * The name of the author may not be used to endorse or promote
      products derived from this software without specific prior
      written permission.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

---

Some of the cuBLAS library routines were derived from code developed by the University of Tennessee and are subject to the Modified Berkeley Software Distribution License as follows:

Copyright (c) 2010 The University of Tennessee.

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer listed in this license in the documentation and/or
      other materials provided with the distribution.
    * Neither the name of the copyright holders nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

Some of the cuBLAS library routines were written by or derived from code written by Jonathan Hogg and are subject to the Modified Berkeley Software Distribution License as follows:

Copyright (c) 2012, The Science and Technology Facilities Council (STFC).

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of the STFC nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE STFC BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

Some of the cuBLAS library routines were written by or derived from code written by Ahmad M. Abdelfattah, David Keyes, and Hatem Ltaief, and are subject to the Apache License, Version 2.0, as follows:

-- (C) Copyright 2013 King Abdullah University of Science and Technology
 Authors:
 Ahmad Abdelfattah (ahmad.ahmad@kaust.edu.sa)
 David Keyes (david.keyes@kaust.edu.sa)
 Hatem Ltaief (hatem.ltaief@kaust.edu.sa)

 Redistribution  and  use  in  source and binary forms, with or without
 modification,  are  permitted  provided  that the following conditions
 are met:

 * Redistributions  of  source  code  must  retain  the above copyright
   notice,  this  list  of  conditions  and  the  following  disclaimer.
 * Redistributions  in  binary  form must reproduce the above copyright
   notice,  this  list  of  conditions  and  the following disclaimer in the
   documentation  and/or other materials provided with the distribution.
 * Neither  the  name of the King Abdullah University of Science and
   Technology nor the names of its contributors may be used to endorse
   or promote products derived from this software without specific prior
   written permission.

 THIS  SOFTWARE  IS  PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 ``AS IS''  AND  ANY  EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 LIMITED  TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A  PARTICULAR  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 SPECIAL,  EXEMPLARY,  OR  CONSEQUENTIAL  DAMAGES  (INCLUDING,  BUT NOT
 LIMITED  TO,  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA,  OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY  OF  LIABILITY,  WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 OF  THIS  SOFTWARE,  EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE

---

Some of the cuBLAS library routines uses code from OpenAI, which is subject to the following license:

License URL: https://github.com/openai/openai-gemm/blob/master/LICENSE

The MIT License

Copyright (c) 2016 OpenAI (http://openai.com), 2016 Google Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
EOF
}

if [ "$os" = mac ]; then
  notice="llama.cpp $LLAMA_TAG (MIT License), macOS arm64 Metal 공식 빌드
릴리스: https://github.com/ggml-org/llama.cpp/releases/tag/$LLAMA_TAG
커밋: https://github.com/ggml-org/llama.cpp/tree/$LLAMA_COMMIT
파일: $MAC_ARCHIVE
SHA-256: $MAC_SHA256
라이선스 전문: LICENSE"
else
  notice="llama.cpp $LLAMA_TAG (MIT License), Windows x64 CUDA 12.4 공식 빌드
릴리스: https://github.com/ggml-org/llama.cpp/releases/tag/$LLAMA_TAG
커밋: https://github.com/ggml-org/llama.cpp/tree/$LLAMA_COMMIT
파일: $WIN_ARCHIVE (SHA-256: $WIN_SHA256)
CUDA 런타임: $WIN_CUDART_ARCHIVE (SHA-256: $WIN_CUDART_SHA256)
라이선스·고지 전문: LICENSE, LICENSE-LLVM-OpenMP, NOTICE-CUDA-CUBLAS.txt"
fi

stage="$OUT"
if [ -f "$OUT/$exe" ] \
  && [ "$(cat "$OUT/$BUNDLE_MARKER" 2> /dev/null)" = "$BUNDLE_MARKER_CONTENT" ] \
  && [ "$(cat "$OUT/NOTICE.txt" 2> /dev/null)" = "$notice" ]; then
  echo "이미 있음: $OUT/$exe"
else
  # Koja의 llama 번들 마커가 있는 폴더이거나 빈 폴더일 때만 덮어쓴다(다른 프로그램의 NOTICE.txt 폴더 보호).
  if [ -d "$OUT" ] \
    && [ "$(cat "$OUT/$BUNDLE_MARKER" 2> /dev/null)" != "$BUNDLE_MARKER_CONTENT" ] \
    && [ -n "$(ls -A "$OUT")" ]; then
    echo "이 스크립트가 만든 폴더가 아니라서 덮어쓰지 않는다: $OUT" >&2
    exit 1
  fi
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
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
    download "$WIN_CUDART_URL" "$WIN_CUDART_SHA256" "$work/$WIN_CUDART_ARCHIVE"
    download "$LICENSE_URL" "$LICENSE_SHA256" "$stage/LICENSE"
    # llama-server.exe 실행과 동적 백엔드 로딩에 필요한 DLL만 푼다.
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
    unzip -q -j "$work/$WIN_CUDART_ARCHIVE" "*.dll" -d "$stage"
    write_cublas_notice "$stage/NOTICE-CUDA-CUBLAS.txt"
  fi
  printf '%s\n' "$notice" > "$stage/NOTICE.txt"
  printf '%s\n' "$BUNDLE_MARKER_CONTENT" > "$stage/$BUNDLE_MARKER"
fi

# 검사: 실행 파일·백엔드 라이브러리·고지 파일이 빠짐없이 있고 버전이 일치해야 한다.
bin="$stage/$exe"
[ -f "$bin" ] || { echo "실행 파일 없음: $bin" >&2; exit 1; }
[ -f "$stage/LICENSE" ] || { echo "LICENSE 없음: $stage" >&2; exit 1; }
[ -f "$stage/NOTICE.txt" ] || { echo "NOTICE.txt 없음: $stage" >&2; exit 1; }
[ "$(cat "$stage/$BUNDLE_MARKER" 2> /dev/null)" = "$BUNDLE_MARKER_CONTENT" ] || { echo "번들 마커 없음: $stage" >&2; exit 1; }

if [ "$os" = mac ]; then
  ls "$stage"/libggml-metal*.dylib > /dev/null 2>&1 || { echo "Metal 라이브러리 없음" >&2; exit 1; }
  for f in "$bin" "$stage"/*.dylib; do
    [ -L "$f" ] && continue
    deps=$(otool -L "$f")
    if grep -qE '/(opt/homebrew|usr/local)/' <<< "$deps"; then
      echo "시스템 밖 동적 라이브러리에 링크됨: $f" >&2
      echo "$deps" >&2
      exit 1
    fi
  done
  ver=$("$bin" --version 2>&1)
  grep -q "build $LLAMA_BUILD" <<< "$ver" || { echo "버전 불일치: $ver" >&2; exit 1; }
  printf '%s\n' "$ver" | head -n 2
else
  for req in llama-server-impl.dll llama.dll llama-common.dll mtmd.dll ggml.dll ggml-base.dll ggml-cuda.dll libomp.dll LICENSE-LLVM-OpenMP cudart64_12.dll cublas64_12.dll cublasLt64_12.dll NOTICE-CUDA-CUBLAS.txt; do
    [ -f "$stage/$req" ] || { echo "필수 파일 없음: $req" >&2; exit 1; }
  done
  ver=$("$bin" --version 2>&1)
  grep -q "build $LLAMA_BUILD" <<< "$ver" || { echo "버전 불일치: $ver" >&2; exit 1; }
  printf '%s\n' "$ver" | head -n 2
fi

if [ "$stage" != "$OUT" ]; then
  rm -rf "$OUT"
  mkdir -p "$(dirname "$OUT")"
  mv "$stage" "$OUT"
fi
echo "검사 통과: llama-server $LLAMA_TAG → $OUT"

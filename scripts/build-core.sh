#!/usr/bin/env bash
set -euo pipefail

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/27.2.12479018}"
[ -d "$NDK_DIR" ] || { echo 'Set ANDROID_NDK_HOME or ANDROID_SDK_ROOT to an installed Android NDK/SDK' >&2; exit 1; }
SYSTEM="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$SYSTEM-$ARCH" in
  darwin-arm64|darwin-aarch64) HOST_TAG=darwin-x86_64 ;; # The NDK macOS toolchain is universal.
  *-x86_64|*-amd64) HOST_TAG="$SYSTEM-x86_64" ;;
  *-aarch64|*-arm64) HOST_TAG="$SYSTEM-aarch64" ;;
  *) echo "Unsupported NDK host: $SYSTEM-$ARCH" >&2; exit 1 ;;
esac
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$OUT_DIR"
cd "$ROOT_DIR/go-core"

GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
CC="$NDK_BIN/aarch64-linux-android24-clang" \
CXX="$NDK_BIN/aarch64-linux-android24-clang++" \
go build -buildmode=c-shared \
  -tags "cmfa,with_gvisor,with_low_memory" \
  -trimpath -ldflags="-s -w" \
  -o "$OUT_DIR/libmihomo.so" .

"$NDK_BIN/llvm-strip" --strip-unneeded "$OUT_DIR/libmihomo.so"
rm -f "$OUT_DIR/libmihomo.h"
ls -lh "$OUT_DIR/libmihomo.so"

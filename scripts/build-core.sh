#!/usr/bin/env bash
set -euo pipefail

NDK_DIR="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your Android NDK path}"
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$OUT_DIR"
cd "$ROOT_DIR/go-core"

GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
CC="$NDK_BIN/aarch64-linux-android24-clang" \
CXX="$NDK_BIN/aarch64-linux-android24-clang++" \
go build -buildmode=c-shared \
  -tags "cmfa,with_gvisor,with_low_memory,no_easytier,no_tailscale,no_zerotier,no_fake_tcp" \
  -trimpath -ldflags="-s -w" \
  -o "$OUT_DIR/libmihomo.so" .

"$NDK_BIN/llvm-strip" --strip-unneeded "$OUT_DIR/libmihomo.so"
rm -f "$OUT_DIR/libmihomo.h"
ls -lh "$OUT_DIR/libmihomo.so"

#!/usr/bin/env bash
# Скачивает официальную сборку Stockfish для Android (arm64) и кладёт как libstockfish.so
set -e
TAG=${1:-sf_16}
DEST="$(dirname "$0")/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST" /tmp/sf_dl
curl -L --fail -o /tmp/sf_dl/sf.tar "https://github.com/official-stockfish/Stockfish/releases/download/$TAG/stockfish-android-armv8.tar"
tar -xf /tmp/sf_dl/sf.tar -C /tmp/sf_dl
BIN=$(find /tmp/sf_dl -type f -name 'stockfish-android-armv8*' ! -name '*.tar' | head -1)
cp "$BIN" "$DEST/libstockfish.so"
chmod +x "$DEST/libstockfish.so"
ls -l "$DEST/libstockfish.so"

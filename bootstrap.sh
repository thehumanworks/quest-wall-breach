#!/bin/sh
# One-time setup after cloning (the repository is plain text; binaries are fetched/generated here).
#  1. Gradle wrapper (official Gradle 9.4.1 gradlew script + jar, checksum-verified)
#  2. Procedural sound effects (python3 + numpy + ffmpeg) -> app/src/main/assets/audio/*.ogg
set -e
cd "$(dirname "$0")"
BASE=https://raw.githubusercontent.com/gradle/gradle/v9.4.1
fetch() { # path sha256
  if [ ! -f "$1" ]; then
    curl -fsSL -o "$1" "$BASE/$1"
    GOT=$( (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | cut -d' ' -f1)
    if [ "$GOT" != "$2" ]; then echo "$1: checksum mismatch" >&2; rm -f "$1"; exit 1; fi
    echo "Fetched $1"
  fi
}
fetch gradle/wrapper/gradle-wrapper.jar 55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
fetch gradlew e712dc38715e260bf046106bf3b121b0021a4b2f83e3999913e435332a60e9f6
chmod +x gradlew
if [ ! -f app/src/main/assets/audio/laser.ogg ]; then
  if python3 -c "import numpy" 2>/dev/null && command -v ffmpeg >/dev/null 2>&1; then
    python3 tools/gen_sounds.py
  else
    echo "WARNING: sounds not generated (need python3 + numpy + ffmpeg, e.g. 'brew install ffmpeg && pip3 install numpy')." >&2
    echo "         The game still builds and runs, just silently." >&2
  fi
fi

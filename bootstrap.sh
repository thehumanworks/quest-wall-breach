#!/bin/sh
# One-time setup after cloning (the repository is plain text; binaries are fetched/generated here).
#  1. Gradle wrapper jar (official Gradle 9.4.1 jar, checksum-verified)
#  2. Procedural sound effects (python3 + numpy + ffmpeg) -> app/src/main/assets/audio/*.ogg
set -e
cd "$(dirname "$0")"
JAR=gradle/wrapper/gradle-wrapper.jar
SHA=55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
if [ ! -f "$JAR" ]; then
  curl -fsSL -o "$JAR" https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar
  GOT=$( (sha256sum "$JAR" 2>/dev/null || shasum -a 256 "$JAR") | cut -d' ' -f1)
  if [ "$GOT" != "$SHA" ]; then echo "gradle-wrapper.jar checksum mismatch" >&2; rm -f "$JAR"; exit 1; fi
  echo "Fetched $JAR"
fi
chmod +x gradlew
if [ ! -f app/src/main/assets/audio/laser.ogg ]; then
  if python3 -c "import numpy" 2>/dev/null && command -v ffmpeg >/dev/null 2>&1; then
    python3 tools/gen_sounds.py
  else
    echo "WARNING: sounds not generated (need python3 + numpy + ffmpeg, e.g. 'brew install ffmpeg && pip3 install numpy')." >&2
    echo "         The game still builds and runs, just silently." >&2
  fi
fi

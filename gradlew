#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VER=9.5.1
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
BOOT="$APP_HOME/.gradle-bootstrap"
ZIP="$BOOT/gradle-$VER-bin.zip"
DIST="$BOOT/gradle-$VER"
mkdir -p "$BOOT"
if [ ! -x "$DIST/bin/gradle" ]; then
  echo "[CKS bootstrap] Downloading Gradle $VER..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$VER-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VER-bin.zip"
  else
    echo "Need Gradle, curl, or wget." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$BOOT"
fi
exec "$DIST/bin/gradle" "$@"

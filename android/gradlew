#!/bin/sh
set -eu

GRADLE_VERSION="8.9"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-$GRADLE_VERSION-bin/bootstrap"
GRADLE_DIR="$GRADLE_HOME_DIR/gradle-$GRADLE_VERSION"
ZIP="$GRADLE_HOME_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  mkdir -p "$GRADLE_HOME_DIR"
  echo "Downloading Gradle $GRADLE_VERSION..."
  curl -fsSL --retry 3 -o "$ZIP" "$URL"
  rm -rf "$GRADLE_DIR"
  unzip -q "$ZIP" -d "$GRADLE_HOME_DIR"
fi

exec "$GRADLE_DIR/bin/gradle" "$@"

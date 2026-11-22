#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/ouse/dev/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ouse/dev/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
bash ./gradlew --no-daemon assembleRelease assembleDebug assembleDebugAndroidTest "$@"
python3 tools/verify_apk.py app/build/outputs/apk/release/app-release.apk
python3 tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/OpenCode-0.2.5-arm64.apk
sha256sum dist/OpenCode-0.2.5-arm64.apk

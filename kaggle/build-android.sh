#!/usr/bin/env bash
# Build the noiz Android app on Kaggle (or any clean Linux box).
#
# Usage (inside a cloned copy of this repo, e.g. /kaggle/working/noiz):
#     bash kaggle/build-android.sh
#
# Produces: android/app/build/outputs/apk/debug/app-debug.apk
# Copy it to /kaggle/working so you can download it from the notebook UI:
#     cp android/app/build/outputs/apk/debug/app-debug.apk /kaggle/working/noiz-debug.apk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"

has_sudo() {
    if [ "$(id -u)" = "0" ]; then return 0; fi
    command -v sudo >/dev/null 2>&1 && return 0
    return 1
}

run_root() {
    if [ "$(id -u)" = "0" ]; then "$@"; elif has_sudo; then sudo "$@"; else "$@"; fi
}

echo "== 1/5 Java 17+ =="
# java prints its version banner on stderr, so check with 2>&1.
if ! java -version 2>&1 | head -1 | grep -qE 'version "(17|18|19|20|21|22|23)'; then
    run_root apt-get update -qq && run_root apt-get install -y -qq openjdk-17-jdk-headless
fi
java -version 2>&1 | head -1

echo "== 2/5 Android SDK cmdline-tools =="
command -v unzip >/dev/null 2>&1 || run_root apt-get install -y -qq unzip
CMD_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK_ROOT/cmdline-tools"
    curl -fsSL "https://dl.google.com/android/repository/$CMD_TOOLS_ZIP" -o /tmp/cmdtools.zip
    unzip -q /tmp/cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

echo "== 3/5 Licenses + packages =="
# 'yes' always exits with SIGPIPE when sdkmanager closes the pipe; with
# 'set -o pipefail' that would abort the script, so swallow its exit.
(yes | sdkmanager --licenses >/dev/null) || true
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;34.0.0" >/dev/null

echo "== 4/5 Gradle dependencies (warm cache; Gradle 8.11.1 wrapper downloads itself) =="
cd "$HERE/../android"
./gradlew --version | head -5
./gradlew :app:dependencies --configuration debugCompileClasspath -q >/dev/null || true

echo "== 5/5 assembleDebug =="
# Kaggle gives plenty of RAM/CPU; lift the small-machine limits from gradle.properties.
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" \
    :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
ls -l "$APK"
echo "SUCCESS: $APK"
if [ -d /kaggle/working ]; then
    cp "$APK" /kaggle/working/noiz-debug.apk
    echo "Copied to /kaggle/working/noiz-debug.apk (download it from the notebook output)"
fi

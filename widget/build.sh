#!/usr/bin/env bash
# Builds the Photo Sync widget app into ../apk/photosync-widget.apk.
# Plain Android SDK tools, no Gradle / Android Studio.
#
#   widget/build.sh
#
# First run: downloads the Android SDK command-line tools into ~/Android/Sdk and asks you
# to accept Google's SDK licences (about 250 MB).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT_APK=$(dirname "$HERE")/apk/photosync-widget.apk
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
PLATFORM=android-34
BT=34.0.0
MIN_SDK=24

# --- Java 17 (any JDK 17+ on PATH, or one Gradle/Android Studio downloaded) --------
if ! command -v javac >/dev/null 2>&1; then
  for j in "$HOME"/.gradle/jdks/*17* "$HOME"/.jdks/*17* /usr/lib/jvm/*17* "$HOME"/android-studio/jbr; do
    [[ -x $j/bin/javac ]] && { export JAVA_HOME=$j PATH=$j/bin:$PATH; break; }
  done
fi
command -v javac >/dev/null 2>&1 || { echo "Needs a JDK 17 (e.g. sudo apt install openjdk-17-jdk-headless)." >&2; exit 1; }

# --- Android SDK -------------------------------------------------------------------
JAR=$SDK/platforms/$PLATFORM/android.jar
TOOLS=$SDK/build-tools/$BT
if [[ ! -f $JAR || ! -x $TOOLS/aapt2 ]]; then
  echo "==> Android SDK parts missing; installing into $SDK"
  SDKM=$SDK/cmdline-tools/latest/bin/sdkmanager
  if [[ ! -x $SDKM ]]; then
    mkdir -p "$SDK/cmdline-tools"
    zip=$SDK/cmdline-tools/clt.zip
    # Retries and resumes (-C -) if the connection drops; re-running the script resumes too.
    curl -fL --progress-bar --retry 10 --retry-delay 3 --retry-all-errors -C - -o "$zip.part" \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    mv "$zip.part" "$zip"
    tmp=$(mktemp -d)
    unzip -q "$zip" -d "$tmp"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -f "$zip"
  fi
  echo "==> Review and accept the Android SDK licences:"
  "$SDKM" --sdk_root="$SDK" --licenses
  "$SDKM" --sdk_root="$SDK" "platforms;$PLATFORM" "build-tools;$BT"
fi

# --- build -------------------------------------------------------------------------
B=$HERE/build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$(dirname "$OUT_APK")"
version=$(( $(date +%s) / 60 ))   # always increasing, so `adb install -r` upgrades

echo "==> resources"
"$TOOLS/aapt2" compile --dir "$HERE/res" -o "$B/res.zip"
"$TOOLS/aapt2" link -I "$JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version 34 \
  --version-code "$version" --version-name "1.0.$version" \
  --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "==> java"
find "$HERE/src" "$B/gen" -name '*.java' > "$B/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$JAR:$TOOLS/core-lambda-stubs.jar" -d "$B/classes" @"$B/sources.txt"
find "$B/classes" -name '*.class' > "$B/classes.txt"
"$TOOLS/d8" --release --min-api $MIN_SDK --lib "$JAR" --output "$B/dex" @"$B/classes.txt"

echo "==> package & sign"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -X "$B/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
KS=$HERE/photosync.keystore   # keep this file: updates must be signed with the same key
if [[ ! -f $KS ]]; then
  keytool -genkeypair -keystore "$KS" -storepass photosync -keypass photosync -alias photosync \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Photo Sync" >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:photosync --ks-key-alias photosync \
  --out "$OUT_APK" "$B/aligned.apk"
echo "==> built $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"

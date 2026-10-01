#!/bin/bash
# ProConn Mobile — manual Gradle-free APK build.
# (Gradle daemon can't run in this sandbox; see TOOLS.md.)
set -e
cd "$(dirname "$0")"

TOOLS="$HOME/workspace/tools"
export JAVA_HOME="$TOOLS/jdk-17"
export PATH="$JAVA_HOME/bin:$TOOLS/kotlinc/bin:$PATH"

SDK="$TOOLS/android-sdk"
BT="$SDK/build-tools/34.0.0"
AAPT2="$BT/aapt2"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
KEYSTORE="$TOOLS/debug.keystore"
STDLIB="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"

VERSION_CODE="${VERSION_CODE:-18}"
VERSION_NAME="${VERSION_NAME:-1.1}"
OUT="${OUT:-build-out-v18}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile =="
"$AAPT2" compile --dir app/src/main/res -o "$OUT/compiled_res.zip"

echo "== aapt2 link (v$VERSION_NAME / code $VERSION_CODE) =="
"$AAPT2" link -o "$OUT/app-base.apk" \
  -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" \
  -A app/src/main/assets \
  "$OUT/compiled_res.zip"

echo "== kotlinc =="
find app/src/main/java -name '*.kt' > "$OUT/sources.txt"
find "$OUT/gen" -name 'R.java' >> "$OUT/sources.txt"
# shellcheck disable=SC2046
kotlinc $(cat "$OUT/sources.txt") -cp "$ANDROID_JAR" -d "$OUT/classes" -jvm-target 1.8

echo "== d8 =="
"$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
mkdir -p "$OUT/dex"
"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB"

echo "== package =="
cp "$OUT/app-base.apk" "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && zip -q "../app-unsigned.apk" classes.dex)
"$ZIPALIGN" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== sign =="
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --out "$OUT/app.apk" "$OUT/app-aligned.apk"

echo "== verify =="
"$APKSIGNER" verify --print-certs "$OUT/app.apk" | head -5
unzip -l "$OUT/app.apk" | grep -E "assets/|classes.dex" || true
echo "BUILD OK: $OUT/app.apk"

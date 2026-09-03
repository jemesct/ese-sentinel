#!/bin/bash
# Builds ese-sentinel.apk without Gradle, using the Android SDK directly.
set -euo pipefail
cd "$(dirname "$0")"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "== aapt2: manifest -> base apk"
"$BT/aapt2" link -o "$OUT/base.apk" \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM" \
  --min-sdk-version 33 --target-sdk-version 36

echo "== javac"
javac --release 17 -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  src/pro/sparkworks/esewatch/*.java

echo "== d8: dex"
"$BT/d8" --release --lib "$PLATFORM" \
  --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

echo "== package dex into apk"
cd "$OUT" && zip -q base.apk classes.dex && cd ..

echo "== zipalign"
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign"
KEYSTORE=sentinel.keystore
: "${KEYSTORE_PASS:?set KEYSTORE_PASS before running, e.g. KEYSTORE_PASS=... ./build.sh}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias sentinel \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -dname "CN=eSE Sentinel, O=sparkworks.pro"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias sentinel \
  --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" \
  --out ese-sentinel.apk "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs ese-sentinel.apk | head -3
echo "== done: $(pwd)/ese-sentinel.apk"

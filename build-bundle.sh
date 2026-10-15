#!/bin/bash
# AAB packaging for Google Play: aapt2 (proto format) + bundletool + jarsigner.
# Run build-manual.sh first — this reuses its out/dex/classes.dex.
# Output: <repo>/ChopChopFlashlight.aab (signed).
set -e
# Repo root: this script lives at the repo root, so all repo paths derive
# from it instead of a hardcoded checkout location.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$ROOT/manual"
SDK="$HOME/workspace/tooling/android-sdk"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
OUT="$PROJ/out-bundle"
# Signing config: created by ./install.sh, stored OUTSIDE the repo
# (default ~/.config/chop-chop-flashlight/config.env, 0600).
# Passwords are never hardcoded here. Build scripts fail with a pointer
# to ./install.sh if the config is missing.
CONFIG_FILE="${CHOPCHOP_CONFIG_FILE:-$HOME/.config/chop-chop-flashlight/config.env}"
if [ -f "$CONFIG_FILE" ]; then
  # shellcheck disable=SC1090
  . "$CONFIG_FILE"
fi
KEYSTORE="${CHOPCHOP_KEYSTORE_PATH:-$PROJ/release.keystore}"
KS_ALIAS="${CHOPCHOP_KEYSTORE_ALIAS:-chopchop}"
KS_PASS="${CHOPCHOP_KEYSTORE_PASSWORD:-}"
KEY_PASS="${CHOPCHOP_KEY_PASSWORD:-$KS_PASS}"
if [ -z "$KS_PASS" ]; then
  echo "No signing config found. Run ./install.sh first." >&2
  exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "Keystore not found at $KEYSTORE. Run ./install.sh first." >&2
  exit 1
fi
BUNDLETOOL="$HOME/workspace/tooling/bundletool.jar"
AAB="$ROOT/ChopChopFlashlight.aab"

for cand in "$HOME/workspace/tooling/jdk/jdk-17.0.20.1+1" "$HOME/workspace/tooling/jdk" \
           /usr/lib/jvm/java-17-openjdk-amd64; do
  if [ -x "$cand/bin/javac" ]; then
    JAVA_HOME="$cand"
    break
  fi
done
if [ -z "${JAVA_HOME:-}" ]; then
  echo "No JDK found" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -f "$PROJ/out/dex/classes.dex" ]; then
  echo "Run build-manual.sh first (need out/dex/classes.dex)" >&2
  exit 1
fi
if [ ! -f "$BUNDLETOOL" ]; then
  echo "bundletool.jar missing at $BUNDLETOOL" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/base/manifest" "$OUT/base/dex"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled_res.zip"

echo "== aapt2 link (proto) =="
"$BT/aapt2" link --proto-format -o "$OUT/proto.apk" -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --java "$OUT/gen" "$OUT/compiled_res.zip"

echo "== assemble base module =="
cd "$OUT"
unzip -q -o proto.apk -d protox
cp protox/AndroidManifest.xml base/manifest/
cp -r protox/res base/
cp protox/resources.pb base/
cp "$PROJ/out/dex/classes.dex" base/dex/
cd base && zip -q -r ../base.zip manifest dex res resources.pb && cd ..

echo "== bundletool build-bundle =="
java -jar "$BUNDLETOOL" build-bundle --modules="$OUT/base.zip" \
  --output="$OUT/app.aab" --overwrite

echo "== jarsigner (v1, for Play) =="
cp "$OUT/app.aab" "$AAB"
jarsigner -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KEY_PASS" \
  "$AAB" "$KS_ALIAS" 2>/dev/null

echo "== validate =="
java -jar "$BUNDLETOOL" validate --bundle="$AAB" > /dev/null
jarsigner -verify "$AAB" | tail -1
ls -la "$AAB"
echo "BUNDLE_BUILD_DONE"

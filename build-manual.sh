#!/bin/bash
# Manual APK build: javac + d8 + aapt2 + apksigner, no Gradle needed.
# (Gradle can't download dependencies in this sandbox: the egress proxy
# blocks Java's direct TCP. Everything here uses only the local SDK.)
set -e
# Repo root: this script lives at the repo root, so all repo paths derive
# from it instead of a hardcoded checkout location.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$ROOT/manual"
SDK="$HOME/workspace/tooling/android-sdk"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
OUT="$PROJ/out"
# Release key lives OUTSIDE $OUT so it survives the rm -rf below and stays
# stable across builds (Android refuses to install an update signed with a
# different key). Generated 2026-09-29 for the Play Store run-up; the old
# debug.keystore is retired (v1.4+ will NOT install over v1.3-signed builds).
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
APK="$ROOT/ChopChopFlashlight.apk"

# Prefer the workspace Temurin JDK; this VM has no system JDK.
for cand in "$HOME/workspace/tooling/jdk" "$HOME/workspace/tooling/jdk/jdk-17.0.20.1+1" \
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
JAVAC="$JAVA_HOME/bin/javac"
export PATH="$JAVA_HOME/bin:$PATH" # d8/apksigner are scripts that need java on PATH

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled_res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/app.unsigned.apk" -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --java "$OUT/gen" "$OUT/compiled_res.zip"

echo "== javac =="
find "$PROJ/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 --release 17 -cp "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 =="
"$BT/d8" --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "== package dex =="
python3 - "$OUT/app.unsigned.apk" "$OUT/dex/classes.dex" "$OUT/app.withdex.apk" <<'EOF'
import sys, zipfile
src, dexf, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dexf, 'classes.dex')
EOF

echo "== zipalign + sign =="
"$BT/zipalign" -f 4 "$OUT/app.withdex.apk" "$OUT/app.aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass pass:"$KS_PASS" --key-pass pass:"$KEY_PASS" \
  --out "$APK" "$OUT/app.aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -4
ls -la "$APK"
echo "MANUAL_BUILD_DONE"

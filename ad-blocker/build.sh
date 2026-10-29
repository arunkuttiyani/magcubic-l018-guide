#!/usr/bin/env bash
# Build Simple Ad Blocker without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs: JDK 17+ and the Android SDK packages "build-tools;34.0.0" and "platforms;android-34".
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for guess in /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools \
               "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$guess/build-tools/34.0.0" ] && SDK="$guess" && break
  done
fi
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
if [ ! -x "$BT/aapt2" ] || [ ! -f "$JAR" ]; then
  echo "Android SDK not found. Set ANDROID_HOME, then run:" >&2
  echo "  sdkmanager \"build-tools;34.0.0\" \"platforms;android-34\"" >&2
  exit 1
fi

# The ~580,000-domain blocklist is generated, not committed: it merges several third-party lists
# under their own licences. Delete assets/blocklist.bin to fetch fresh copies on the next build.
[ -f assets/blocklist.bin ] || python3 -I tools/build_blocklist.py

rm -rf build && mkdir -p build/gen build/classes build/res
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 21 --target-sdk-version 28 -A assets -o build/unsigned.apk build/res/res.zip
javac -nowarn -Xlint:-options --release 8 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')
# d8 is fed a jar: it mis-handles loose .class files from newer JDKs.
(cd build/classes && jar cf ../classes.jar .)
"$BT/d8" --release --min-api 21 --lib "$JAR" --output build build/classes.jar
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

# Local signing key, generated once and reused so later builds install over the top.
# It is a throwaway key for side-loading; it is git-ignored and never needs to leave your machine.
if [ ! -f ad-blocker.keystore ]; then
  keytool -genkeypair -keystore ad-blocker.keystore -alias lite \
      -storepass litehome -keypass litehome -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Simple Ad Blocker" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks ad-blocker.keystore --ks-pass pass:litehome --key-pass pass:litehome \
    --out SimpleAdBlocker.apk build/aligned.apk
rm -f SimpleAdBlocker.apk.idsig
ls -lh SimpleAdBlocker.apk

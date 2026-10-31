#!/bin/sh
# 构建「屏幕时长修正」Xposed 模块（无 Gradle，纯 javac + d8 + aapt2 + apksigner）
set -e

SDK=/root/android-sdk
AJ="$SDK/platforms/android-37.0/android.jar"
XP=$(find /root/.gradle/caches -path '*de.robv.android.xposed/api/82*' -name 'api-82.jar' | head -1)
OUT=build
KS=eta.jks

[ -n "$XP" ] || { echo "xposed api-82.jar not found"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "== core tests"
rm -rf /tmp/coretest && mkdir -p /tmp/coretest
javac -encoding UTF-8 -d /tmp/coretest src/com/eta/lab/screentime/Cfg.java tools/CoreTest.java
java -cp /tmp/coretest CoreTest

echo "== javac"
javac --release 8 -encoding UTF-8 -cp "$AJ:$XP" -d "$OUT/classes" $(find src -name '*.java')

echo "== jar + d8"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
d8 --min-api 24 --lib "$AJ" --output "$OUT" "$OUT/classes.jar"

echo "== aapt2"
aapt2 compile --dir res -o "$OUT/res.zip"
aapt2 link -o "$OUT/base.apk" --manifest AndroidManifest.xml -I "$AJ" \
    --min-sdk-version 24 --target-sdk-version 35 "$OUT/res.zip"

echo "== package"
mkdir -p "$OUT/assets"
echo "com.eta.lab.screentime.HookEntry" > "$OUT/assets/xposed_init"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex && zip -q -r unsigned.apk assets)

echo "== sign"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias eta -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass eta12345 -keypass eta12345 \
        -dname "CN=eta, OU=lab, O=eta, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:eta12345 --key-pass pass:eta12345 \
    --min-sdk-version 24 --out "$OUT/screentime.apk" "$OUT/unsigned.apk"
apksigner verify --print-certs "$OUT/screentime.apk" | head -5
ls -l "$OUT/screentime.apk"
echo "== done"

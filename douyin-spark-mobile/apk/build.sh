#!/bin/bash
# 手工构建链:图标 → aapt2 → javac → d8 → 打包 → zipalign → apksigner
# 用法:在 Git Bash 中执行  bash build.sh
#
# 工具链位置可用环境变量覆盖(便于别人 clone 后构建):
#   ANDROID_SDK=/path/to/android-sdk
#   JDK_HOME=/path/to/jdk-17
#   ANDROID_BUILD_TOOLS=34.0.0
set -e

SDK="${ANDROID_SDK:-D:/AI/ZCode/build-env/sdk}"
JDK="${JDK_HOME:-D:/AI/ZCode/build-env/jdk-17.0.20.1+1}"
BT_VERSION="${ANDROID_BUILD_TOOLS:-34.0.0}"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM="$SDK/platforms/android-34/android.jar"

AAPT2="$BT/aapt2.exe"
JAVAC="$JDK/bin/javac.exe"
D8="$BT/d8.bat"
JAR="$JDK/bin/jar.exe"
ZIPALIGN="$BT/zipalign.exe"
KEYTOOL="$JDK/bin/keytool.exe"
APKSIGNER="$BT/apksigner.bat"

# d8.bat / apksigner.bat 内部需要 java
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$BT:$PATH"

cd "$(dirname "$0")"

[ -f "$PLATFORM" ] || { echo "❌ 找不到 $PLATFORM(用 ANDROID_SDK 指定 SDK 路径)"; exit 1; }

rm -rf build
mkdir -p build/gen build/obj build/dex build/apk

echo "[1/7] 生成图标(res/icon/icon-source.png;没有就用内置占位图)"
PY=""
for c in python python3 py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -n "$PY" ]; then
    "$PY" make_icons.py || echo "  ⚠️ 图标生成失败,沿用 res/mipmap-* 中已有的图标"
else
    echo "  ⚠️ 未找到 python,跳过图标生成(沿用已有 res/mipmap-*)"
fi

echo "[2/7] aapt2 编译资源与生成 R.java"
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/apk/base.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml --java build/gen build/res.zip

echo "[3/7] javac 编译"
find src build/gen -name "*.java" > build/sources.txt
"$JAVAC" -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$PLATFORM" \
    -d build/obj @build/sources.txt

echo "[4/7] d8 转 dex"
find build/obj -name "*.class" > build/classes.txt
"$D8" --release --lib "$PLATFORM" --output build/dex @build/classes.txt

echo "[5/7] 打入 classes.dex"
cp build/dex/classes.dex build/apk/
cd build/apk && "$JAR" -uf base.apk classes.dex && cd ../..

echo "[6/7] zipalign"
"$ZIPALIGN" -f 4 build/apk/base.apk build/apk/aligned.apk

echo "[7/7] 签名(缺 debug.keystore 会自动生成;正式发布请换成你自己的签名)"
if [ ! -f debug.keystore ]; then
    "$KEYTOOL" -genkeypair -keystore debug.keystore \
        -alias androiddebugkey -storepass android -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SparkKeeper,O=Local,C=CN"
fi
"$APKSIGNER" sign --ks debug.keystore --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out ../SparkKeeper.apk build/apk/aligned.apk

echo ""
echo "✅ 构建完成: $(cd .. && pwd)/SparkKeeper.apk"
ls -la ../SparkKeeper.apk

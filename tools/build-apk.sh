#!/usr/bin/env bash
# 手工构建 APK（无需 Gradle）：aapt2 编译资源 -> javac 编译 Java -> d8 转 dex -> 打包对齐签名
# 前置：JDK 17+、Android SDK（build-tools 35.0.0 与 platforms/android-35）
# 用法：bash tools/build-apk.sh                 （产物：dist/VideoDL-<版本>.apk）
#       VERSION_NAME=1.1 VERSION_CODE=2 bash tools/build-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT="$ROOT/build"
DIST="$ROOT/dist"

# ── 版本参数（可被环境变量覆盖；改版本号只需改这里，不用动 Manifest）──
VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"
APK_NAME="VideoDL-${VERSION_NAME}.apk"

test -f "$PLATFORM" || { echo "找不到 android.jar，请检查 ANDROID_HOME 或 SDK 路径"; exit 1; }
test -f "$BT/aapt2.exe" -o -f "$BT/aapt2" || { echo "找不到 build-tools 35.0.0"; exit 1; }

# 清理上次的产物。build/test-classes 是桌面自测目录，可能被其他进程占用：
# 单独删、失败忽略（不影响正式构建，测试目录本来就不参与 APK 产物）。
rm -rf "$DIST" 2>/dev/null || true
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/classes.jar" \
      "$OUT/sources.txt" "$OUT/res.zip" "$OUT/base.apk" "$OUT/aligned.apk" 2>/dev/null || true
rm -rf "$OUT/test-classes" 2>/dev/null || true
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "== 1/6 aapt2 编译资源 =="
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 链接（生成 R.java 与资源 APK）=="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -R "$OUT/res.zip" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay

echo "== 3/6 javac 编译 Java =="
# javac 是 Windows 原生程序：路径转成 Windows 格式，含空格要加引号（@argfile 规则）
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' | while read -r f; do
  echo "\"$(cygpath -m "$f")\""
done > "$OUT/sources.txt"
javac --release 8 -encoding UTF-8 -Xlint:-options -classpath "$(cygpath -m "$PLATFORM")" \
  -d "$(cygpath -m "$OUT/classes")" "@$(cygpath -m "$OUT/sources.txt")"

echo "== 4/6 d8 转 dex =="
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8.bat" --release --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar"

echo "== 5/6 打包 classes.dex =="
(cd "$OUT/dex" && "$BT/aapt" add "$OUT/base.apk" classes.dex)

echo "== 6/6 对齐 + 签名 =="
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=VideoDL Debug,O=VideoDL,C=CN" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" sign --ks "$ROOT/debug.keystore" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --out "$DIST/$APK_NAME" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$DIST/$APK_NAME" && echo "签名校验通过"

ls -la "$DIST"
echo "完成：$DIST/$APK_NAME"

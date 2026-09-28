#!/usr/bin/env bash
# 桌面端自测：把 app 的纯 Java 解析层（parser/ + net/Http）与 tests/*.java 一起编译后运行。
# android.jar 只用来提供 org.json 与 android.* 的编译符号（Line2Loader 不在运行时触达）。
#
# 用法:
#   bash tools/run-tests.sh                          # 只编译，不运行
#   bash tools/run-tests.sh ParserSelfTest "分享文案"  # 编译并运行指定测试
# 可用测试: ParserSelfTest / BiliSelfTest / KsSelfTest / GalleryTest / E2ETest / FullDL
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
PLATFORM="$SDK/platforms/android-35/android.jar"
# 真实的 org.json（android.jar 里的是 Stub 实现，运行时会抛 "Stub!"）
JSON_JAR="$ROOT/tools/lib/json-20240303.jar"
OUT="$ROOT/build/test-classes"

test -f "$PLATFORM" || { echo "找不到 android.jar，请检查 ANDROID_HOME 或 SDK 路径"; exit 1; }
test -f "$JSON_JAR" || { echo "找不到 $JSON_JAR（运行时用的 org.json），请勿删除 tools/lib"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

# 源码清单：解析层 + 网络层 + 测试（net/Line2Loader 依赖 android.*，仅为通过编译）
find "$ROOT/app/src/main/java/com/videodl/app/parser" \
     "$ROOT/app/src/main/java/com/videodl/app/net" \
     "$ROOT/tests" -name '*.java' | while read -r f; do
  echo "\"$(cygpath -m "$f")\""
done > "$OUT/sources.txt"

echo "== 编译 解析层 + 测试 =="
javac --release 8 -encoding UTF-8 -Xlint:-options \
  -classpath "$(cygpath -m "$PLATFORM");$(cygpath -m "$JSON_JAR")" \
  -d "$(cygpath -m "$OUT")" "@$(cygpath -m "$OUT/sources.txt")"
echo "编译通过：$OUT"

if [ $# -eq 0 ]; then
  echo "提示：追加 '类名 [参数...]' 运行测试，例如 bash tools/run-tests.sh ParserSelfTest"
  exit 0
fi

echo "== 运行 $1 =="
java -cp "$(cygpath -m "$OUT");$(cygpath -m "$JSON_JAR");$(cygpath -m "$PLATFORM")" "$@"

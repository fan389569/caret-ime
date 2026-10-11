#!/usr/bin/env bash
# 不用 Gradle：javac 直接对着 IntelliJ IDEA 自带的 lib/*.jar 编译，打成插件 jar。
#
# 需要：
#   - JDK 21（可用 JAVA_HOME 指定，否则用 PATH 里的 javac/jar）
#   - 环境变量 IDEA_HOME 指向 IntelliJ IDEA 2025.2 安装目录（含 lib/*.jar）
#
# 可选：
#   - 设置 PLUGIN_DIR 时，构建后顺带把 jar 装进该目录的 lib/ 下（目录需为插件专属目录）
set -e
cd "$(dirname "$0")"

: "${IDEA_HOME:?请设置 IDEA_HOME 指向 IntelliJ IDEA 安装目录（含 lib/*.jar）}"

JAVAC="javac"; JAR="jar"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
fi

NAME="CaretIME"

rm -rf build
mkdir -p build/classes
"$JAVAC" --release 21 -encoding UTF-8 -cp "$IDEA_HOME/lib/*" -d build/classes $(find src -name '*.java')
cp -r resources/META-INF build/classes/
"$JAR" cf "build/$NAME.jar" -C build/classes .
echo "已生成 build/$NAME.jar"

if [ -n "$PLUGIN_DIR" ]; then
  mkdir -p "$PLUGIN_DIR/lib"
  cp "build/$NAME.jar" "$PLUGIN_DIR/lib/"
  echo "已安装到 $PLUGIN_DIR/lib/$NAME.jar —— 重启 IDEA 生效"
  echo "（如果该目录里还有旧名字的 jar，请手动删掉，避免重复加载）"
fi

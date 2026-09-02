#!/usr/bin/env bash
# Package a self-contained Linux aarch64 CLI distribution:
# jars + native libs + bundled jlink JRE. Extract and run — no Java needed.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f /opt/tenv.sh ]; then . /opt/tenv.sh; fi
JAVA_HOME=${JAVA_HOME:-/opt/jdk17}
BUILD_DIR=build-native-desktop
INSTALL=cli/build/install/cli
VERSION=$(git describe --tags 2>/dev/null || echo 0.3.0)
OUT="pocketllm-$VERSION-linux-aarch64"
STAGE="build-cli-dist/$OUT"

[ -f "$INSTALL/bin/pocketllm" ] || { echo "missing CLI build — run: JAVA_HOME=$JAVA_HOME ./gradlew :cli:installDist"; exit 1; }
[ -f "$BUILD_DIR/libpocketllm.so" ] || { echo "missing native build — run: ./build-native-desktop.sh"; exit 1; }

rm -rf build-cli-dist && mkdir -p "$STAGE/bin"
cp -r "$INSTALL/lib" "$STAGE/lib"

cp -L "$BUILD_DIR/libpocketllm.so" "$STAGE/lib/"
for f in "$BUILD_DIR"/bin/lib*.so.*; do cp -L "$f" "$STAGE/lib/"; done

MODULES=$("$JAVA_HOME/bin/jdeps" --multi-release 17 --print-module-deps \
  --ignore-missing-deps "$STAGE"/lib/*.jar 2>/dev/null | tail -1 | tr -d ' ')
case "$MODULES" in
  *java.base*) ;;
  *) MODULES="java.base,java.logging,java.naming,java.management,java.xml,jdk.unsupported,jdk.crypto.ec" ;;
esac
echo "jlink modules: $MODULES"

"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages --compress=2 \
  --output "$STAGE/runtime"

cat > "$STAGE/bin/pocketllm" <<'WEOF'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export POCKETLLM_NATIVE_LIB="$DIR/lib/libpocketllm.so"
exec "$DIR/runtime/bin/java" -Xss2m -cp "$DIR/lib/*" com.pocketllm.cli.MainKt "$@"
WEOF
chmod +x "$STAGE/bin/pocketllm"

tar -C build-cli-dist -czf "$OUT.tar.gz" "$OUT"
echo "=== Built: $PWD/$OUT.tar.gz ($(du -h "$OUT.tar.gz" | cut -f1)) ==="

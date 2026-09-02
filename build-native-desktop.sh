#!/usr/bin/env bash
# Build libpocketllm.so for desktop Linux (proot/JVM CLI use).
# Uses the system gcc toolchain; the app build (build-native.sh) is untouched.
# CLI usage: POCKETLLM_NATIVE_LIB=$PWD/build-native-desktop/libpocketllm.so
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f /opt/tenv.sh ]; then
    . /opt/tenv.sh
fi

BUILD_DIR="build-native-desktop"
NPROCS=$(nproc 2>/dev/null || echo 4)

echo "=== Building desktop native library (${NPROCS} jobs) ==="
cmake -S app/src/main/cpp -B "$BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DGGML_OPENMP=OFF \
    -DGGML_VULKAN=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DGGML_NATIVE=OFF \
    -DLLAMA_CURL=OFF

cmake --build "$BUILD_DIR" -j "$NPROCS" --target pocketllm
echo "=== Done: $SCRIPT_DIR/$BUILD_DIR/libpocketllm.so ==="

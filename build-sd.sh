#!/usr/bin/env bash
# Builds libsdcpp.so - the on-device Stable Diffusion engine + JNI bridge.
#
# Kept in its own CMake project (and its own build directory) because
# stable-diffusion.cpp vendors its own ggml while libpocketllm.so links
# llama.cpp's; two ggml target sets in one project collide on target names.
set -euo pipefail

cd "$(dirname "$0")"
if [ -f /opt/tenv.sh ]; then
    . /opt/tenv.sh
fi

BUILD_DIR="build-sd"
NPROCS="${POCKETLLM_BUILD_JOBS:-$(nproc 2>/dev/null || echo 4)}"

# stable-diffusion.cpp is fetched on demand rather than vendored: the tree is
# ~300 MB of source + assets, which does not belong in the repository. Pin the
# revision here so builds stay reproducible.
SD_DIR="third_party/sd"
SD_REPO="${SD_REPO:-https://github.com/leejet/stable-diffusion.cpp}"
if [ ! -f "$SD_DIR/include/stable-diffusion.h" ]; then
    echo "=== Fetching stable-diffusion.cpp ==="
    rm -rf "$SD_DIR"
    git clone --depth 1 --recurse-submodules --shallow-submodules "$SD_REPO" "$SD_DIR"
fi

echo "=== Configuring libsdcpp (${NPROCS} jobs) ==="
cmake -S app/src/main/cpp/sd-jni -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE=/opt/native-toolchain.cmake \
    -DCMAKE_FIND_ROOT_PATH="/opt/tusr;/usr" \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON

echo "=== Building ==="
cmake --build "$BUILD_DIR" -j"$NPROCS"

JNI_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$BUILD_DIR/libsdcpp.so" "$JNI_DIR/libsdcpp.so"
echo "=== Copied libsdcpp.so ==="

# Same NEEDED/SONAME hygiene as build-native.sh: a dependency recorded as an
# absolute build-machine path will not resolve on the device.
NEEDED_TMP="$(mktemp)"
for f in "$JNI_DIR"/libsdcpp.so; do
    readelf -d -W "$f" 2>/dev/null | awk '/NEEDED/ {gsub(/[][]/,"",$5); print $5}' > "$NEEDED_TMP" || true
    while read -r dep; do
        [ -n "$dep" ] || continue
        case "$dep" in
            /*) echo "  Rewriting NEEDED $dep -> $(basename "$dep")"
                patchelf --replace-needed "$dep" "$(basename "$dep")" "$f" ;;
        esac
    done < "$NEEDED_TMP"
done
rm -f "$NEEDED_TMP"

echo "=== libsdcpp.so ready ($(stat -c%s "$JNI_DIR/libsdcpp.so") bytes) ==="

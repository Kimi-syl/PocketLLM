#!/usr/bin/env bash
# PocketLLM native build script
# Builds libpocketllm.so and the debug APK from Termux/proot (no Android Studio needed)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Source Termux environment
if [ -f /opt/tenv.sh ]; then
    . /opt/tenv.sh
fi

BUILD_DIR="build-native"
NPROCS=${POCKETLLM_BUILD_JOBS:-$(nproc 2>/dev/null || echo 4)}

echo "=== Building native libraries (${NPROCS} jobs) ==="
cmake -S app/src/main/cpp -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE=/opt/native-toolchain.cmake \
    -DCMAKE_FIND_ROOT_PATH="/opt/tusr;/usr" \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DCMAKE_PREFIX_PATH="$SCRIPT_DIR/vulkan-host" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DGGML_OPENMP=OFF \
    -DGGML_VULKAN=ON \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DGGML_NATIVE=OFF \
    -DLLAMA_CURL=OFF \
    -DBUILD_SHARED_LIBS=OFF

cmake --build "$BUILD_DIR" -j"$NPROCS"

echo "=== Copying .so files to jniLibs ==="
JNI_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"

for lib in "$BUILD_DIR"/lib*.so; do
    [ -f "$lib" ] || continue
    base=$(basename "$lib")
    cp "$lib" "$JNI_DIR/$base"
    echo "  Copied $base"
done

# Ensure libc++_shared.so is present
if [ ! -f "$JNI_DIR/libc++_shared.so" ] && [ -f /opt/tusr/lib/libc++_shared.so ]; then
    cp /opt/tusr/lib/libc++_shared.so "$JNI_DIR/"
    echo "  Copied libc++_shared.so"
fi

# Patch versioned SONAMEs (libllama.so.0 → libllama.so)
echo "=== Patching SONAMEs ==="
python3 - <<'PYEOF'
import struct, os, glob

def patch_soname(path):
    with open(path, 'rb') as f:
        data = bytearray(f.read())
    # Find and fix NEEDED entries with .so.X suffix
    for ext in ['.so.0', '.so.1']:
        target = ext.encode()
        replacement = ext[:-2].encode()  # .so
        idx = 0
        patched = False
        while True:
            idx = data.find(target, idx)
            if idx == -1:
                break
            # Replace .so.0 with .so
            data[idx:idx+len(target)] = replacement + b'\x00' * (len(target) - len(replacement))
            patched = True
        if patched:
            with open(path, 'wb') as f:
                f.write(data)
            print(f"  Patched {os.path.basename(path)}")

for f in glob.glob('app/src/main/jniLibs/arm64-v8a/lib*.so'):
    patch_soname(f)

# The Vulkan link records the host sysroot path for libvulkan; rewrite it to
# the bare soname Android's loader expects.
import glob as _g
for f in _g.glob('app/src/main/jniLibs/arm64-v8a/lib*.so'):
    with open(f, 'rb') as fh:
        d = bytearray(fh.read())
    bad = b'/opt/tusr/usr/lib/libvulkan.so\x00'
    if bad in d:
        i = d.find(bad)
        rep = b'libvulkan.so\x00'
        d[i:i+len(bad)] = rep + b'\x00' * (len(bad) - len(rep))
        with open(f, 'wb') as fh:
            fh.write(d)
        print(f'  Patched libvulkan NEEDED in {os.path.basename(f)}')
PYEOF

echo "=== Building APK ==="
JAVA_HOME=${JAVA_HOME:-/opt/jdk17} ./gradlew --no-daemon :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo "=== Done: $APK ($SIZE) ==="
    # Name the release artifact after the app version (pocketllm-<version>.apk)
    VERSION=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
    NAMED_APK="pocketllm-${VERSION}.apk"
    cp "$APK" "$NAMED_APK"
    echo "  Copied to $PWD/$NAMED_APK"
    if [ -d /sdcard/Download ]; then
        cp "$APK" "/sdcard/Download/$NAMED_APK"
        echo "  Also copied to /sdcard/Download/$NAMED_APK"
    fi
else
    echo "=== Build failed: APK not found ==="
    exit 1
fi

# PocketLLM

Run GGUF LLMs locally on Android with llama.cpp and expose them through an OpenAI-compatible API for on-device apps and LAN clients.

## Features

- Native **llama.cpp** inference via JNI/NDK (**arm64-v8a** build target)
- **OpenAI-compatible API**
  - `GET /v1/models`
  - `POST /v1/chat/completions` (streaming SSE + JSON)
  - `POST /v1/completions` (streaming SSE + JSON)
  - `GET /health`
- In-app **API key management** (create/revoke/enable/disable, last-used tracking)
- **Hugging Face model discovery** and GGUF browsing/download
- **Resumable segmented downloader** with retry support and persisted metadata
- Built-in **chat screen** for local testing
- Optional **web search augmentation** (DuckDuckGo, Brave, Tavily, Bing, Firecrawl)
- Optional **auto-speak** responses using Android TTS
- **Usage logging and analytics** in-app
- Optional **HTTPS** with generated self-signed certificate and fingerprint display

## Architecture

```
Compose UI + AppViewModel
├─ ModelRepository (HF search/download)
├─ ApiKeyRepository (local API key persistence)
├─ SettingsRepository (server, search, TTS, TLS settings)
├─ UsageRepository (JSONL usage logs)
├─ ApiServer (Ktor OpenAI-compatible routes)
└─ LlamaEngine → LlamaBridge (JNI) → llama_jni.cpp
```

Generation is serialized through a dedicated single-thread path with synchronization, so one inference runs at a time.

## Requirements

- Android Studio (recent stable)
- Android SDK 35
- Java 17
- Android NDK + CMake (3.22.1)
- Device/emulator API 26+

## Build

```bash
# from repo root (needed on fresh clones because llama.cpp is not committed)
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

./gradlew :app:assembleDebug
```

Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Quick start

1. Download the latest prebuilt APK from **Releases**: https://github.com/Kimi-syl/PocketLLM/releases/latest
2. Install the APK on your device.
3. Open **Models** tab, search/download a GGUF model, then load it.
4. Open **Server** tab and start the server.
5. Open **Keys** tab and create an API key (if key auth is enabled).
6. Point your OpenAI client to the phone.

Example:

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: ******" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "your-model-name",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": true
  }'
```

For on-device clients, use `http://127.0.0.1:8080/v1`.

## Security notes

- Server binds to `0.0.0.0`; LAN clients can reach it when enabled.
- Keep **Require API key** on for non-local testing.
- API keys are stored in app-private storage.
- HTTPS is optional and uses a self-signed certificate (clients must trust/allow it, e.g. `curl -k`).
- Cleartext HTTP remains available for trusted local/dev networks.

## Known limitations

- One loaded model/inference stream at a time (requests serialize)
- Long prompts/conversations may be truncated to fit context window
- No foreground service yet, so long operations may be interrupted if app/process is killed
- Current packaged ABI target is arm64-v8a

## Building on-device (Termux + proot, no Android Studio)

PocketLLM can be compiled directly on an ARM64 Android phone from Termux/proot, without Android Studio.

Typical toolchain locations:

| Path | Purpose |
|---|---|
| `/opt/tusr` | Termux clang/lld clone (avoids proot self-exec shim issues) |
| `/opt/jdk17` | JDK 17 for Gradle/AGP |
| `/opt/gradle` | Gradle runtime |
| `/opt/android-sdk` | Android cmdline-tools + SDK/platform packages |
| `/opt/aapt2arm` | static aarch64 aapt2 binary |

Example rebuild flow:

```bash
. /opt/tenv.sh
cd ~/pocketllm
cmake -S app/src/main/cpp -B build-native -DCMAKE_TOOLCHAIN_FILE=/opt/native-toolchain.cmake \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DGGML_OPENMP=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DGGML_NATIVE=OFF -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build build-native -j6
cp build-native/libpocketllm.so app/src/main/jniLibs/arm64-v8a/
cp /opt/tusr/lib/libc++_shared.so app/src/main/jniLibs/arm64-v8a/
JAVA_HOME=/opt/jdk17 ./gradlew --no-daemon :app:assembleDebug
```

## Roadmap

- Foreground service for more robust background download/inference
- GPU offload variants (Vulkan/OpenCL)
- Expanded per-key analytics
- Better model metadata/RAM estimation
- Service discovery (mDNS)

# PocketLLM

Run LLMs fully offline on your Android phone with llama.cpp — and expose them through an
**OpenAI-compatible HTTP API protected by API keys**, so any app on the device (or on your
Wi-Fi network) can use your phone as a tiny local AI server.

Think PocketPal, but it *serves* an API instead of just chatting.

## Features

- **llama.cpp inference** compiled natively via NDK (arm64-v8a; x86_64 for emulator)
- **OpenAI-compatible endpoints**
  - `GET  /v1/models`
  - `POST /v1/chat/completions` (streaming SSE + JSON)
  - `POST /v1/completions` (streaming SSE + JSON)
  - `GET  /health`
- **API keys**: generate/revoke/toggle keys in-app; requests authenticated via
  `Authorization: Bearer sk-…` (can be disabled for localhost-only testing)
- **Hugging Face integration**: search GGUF models by downloads, browse a repo's `.gguf`
  files with sizes, one-tap download
- **Resumable segmented downloader**: up to 4 parallel range requests, writes directly into
  a preallocated file at final offsets (no concatenation pass), survives process death via
  an ETag-validated metadata sidecar, cancel button included
- **Chat tester** screen streaming tokens from the loaded model
- **Server dashboard**: start/stop, port, context size, request log, LAN endpoint URLs
- Correct **chat template application** using `llama_chat_apply_template` (template is read
  from GGUF metadata — Qwen, Llama 3, Mistral, Gemma, etc.)
- UTF-8-safe token streaming (multi-byte characters never split mid-chunk)
- big.LITTLE-aware thread count (threads = big cores)

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Compose UI (Models / Chat / Server / Keys)              │
│                 AppViewModel                            │
├───────────────────┬─────────────────┬───────────────────┤
│ ModelRepository   │ ApiKeyRepository│ SettingsRepository│
│ (HF search +      │ (sk-… keys,     │ (port, ctx, token)│
│  resumable dl)    │  JSON persisted)│                   │
├───────────────────┴─────────────────┼───────────────────┤
│ Ktor CIO server (OpenAI routes+auth)│ LlamaEngine       │
├─────────────────────────────────────┼───────────────────┤
│                                     │ LlamaBridge (JNI) │
│                                     │ llama_jni.cpp     │
│                                     └──► libllama.so    │
└─────────────────────────────────────┴───────────────────┘
```

Generation runs on a dedicated single thread; all entry points serialize through one mutex,
so the chat UI and the HTTP API can share the loaded model safely.

## Building

Requirements: Android Studio (Ladybug or newer), SDK 35, NDK, CMake 3.22.1.

```bash
# from the repo root
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

# then open the project in Android Studio and run
./gradlew :app:assembleDebug
```

The vendored `app/src/main/cpp/llama.cpp` is already included if you received this project
as-is; the clone step is only needed for a fresh checkout.

Install on device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Using the API

1. Models tab → Hugging Face → search (e.g. "Qwen2.5 3B") → download a `Q4_K_M` GGUF
2. On device tab → Load
3. Server tab → Start server
4. Keys tab → create a key
5. From any OpenAI client pointed at the phone:

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-YOUR-KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5-3b-instruct",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": true
  }'
```

On-device apps can use `http://127.0.0.1:8080/v1`. The response format (including SSE
chunk shape and `[DONE]` sentinel) matches OpenAI closely enough for standard SDKs.

## Performance notes

| Setting | Guidance |
|---|---|
| Quantization | `Q4_K_M` is the sweet spot on mobile |
| Model size | ≤3B params on 6 GB devices, ≤8B on 12 GB+ |
| Context | 2048 default; each doubling costs KV-cache RAM |
| Threads | auto-set to big cores; more threads ≠ faster on little cores |

Native-side tuning already applied: `n_batch=1024 / n_ubatch=512` prefill pipeline,
flash-attention left on AUTO so ggml picks the best kernel per backend.

## Security

- The server binds `0.0.0.0`, so anything on your Wi-Fi can reach it. Keep
  **Require API key** enabled when not testing.
- Keys are stored in app-private storage (`filesDir/api_keys.json`).
- An optional Hugging Face token (for gated repos) is stored locally and only sent to
  huggingface.co.
- There is no TLS — treat this like a dev tool on trusted networks.

## Roadmap

- Foreground service so downloads/inference survive backgrounding
- Vulkan/OpenCL GPU offload builds of llama.cpp
- Per-key usage stats surfaced in the UI
- GGUF metadata reader for pre-load RAM estimation
- mDNS advertisement (`_llm._tcp`) for zero-config client discovery

## Known limitations

- One model loaded at a time (requests queue on a mutex)
- Long conversations are truncated front-first when exceeding context
- Downloads require the screen/app to stay alive (no foreground service yet)

## Building on-device (Termux + proot, no Android Studio)

This project builds entirely on an ARM64 phone. The toolchain lives in `/opt`:

| Path | Purpose |
|---|---|
| `/opt/tusr` | Termux clang/lld clone (bypasses the proot self-exec shim) |
| `/opt/jdk17` | Temurin JDK 17 (Gradle/AGP runtime) |
| `/opt/gradle` | Gradle 8.10.2 |
| `/opt/android-sdk` | cmdline-tools + `platforms;android-35` |
| `/opt/aapt2arm` | static aarch64 aapt2 (lzhiyong/android-sdk-tools) |

Rebuild native engine + APK:

```bash
. /opt/tenv.sh
cd ~/pocketllm   # or wherever the project lives
cmake -S app/src/main/cpp -B build-native -DCMAKE_TOOLCHAIN_FILE=/opt/native-toolchain.cmake \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DGGML_OPENMP=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DGGML_NATIVE=OFF -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build build-native -j6
cp build-native/libpocketllm.so app/src/main/jniLibs/arm64-v8a/
cp /opt/tusr/lib/libc++_shared.so app/src/main/jniLibs/arm64-v8a/
JAVA_HOME=/opt/jdk17 ./gradlew --no-daemon :app:assembleDebug
```

Install from Termux (host session): `termux-open ~/PocketLLM.apk`

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

1. Open **Models** tab, search/download a GGUF model, then load it.
2. Open **Server** tab and start the server.
3. Open **Keys** tab and create an API key (if key auth is enabled).
4. Point your OpenAI client to the phone.

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

## Roadmap

- Foreground service for more robust background download/inference
- GPU offload variants (Vulkan/OpenCL)
- Expanded per-key analytics
- Better model metadata/RAM estimation
- Service discovery (mDNS)

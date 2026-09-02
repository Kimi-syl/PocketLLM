# PocketLLM

Run GGUF LLMs locally on Android with llama.cpp and expose them through an OpenAI-compatible API for on-device apps and LAN clients. A shared, platform-neutral **core** module also powers a desktop JVM **CLI** — the same engine, agent loop, and OpenAI-compatible server on your phone and in Termux/proot or on Linux.

## Features

- Native **llama.cpp** inference via JNI/NDK with **Vulkan GPU offload** (**arm64-v8a** build target)
- **OpenAI-compatible API**
  - `GET /v1/models`
  - `POST /v1/chat/completions` (streaming SSE + JSON)
  - `POST /v1/completions` (streaming SSE + JSON)
  - `GET /health`
- In-app **API key management** (create/revoke/enable/disable, last-used tracking)
- **Hugging Face model discovery** and GGUF browsing/download
- **Resumable segmented downloader** with retry support and persisted metadata
- Built-in **chat screen** with **Markdown + LaTeX** rendering
- **Agent mode with grammar-constrained tool calls** (v0.3.0)
  - GBNF grammar applied at sampling time: the model can only emit *one valid* `TOOL: <name> ARGS: key=value;...` line for tools you have enabled — no more malformed tool calls from small models
  - `ToolRouter` heuristics decide when tool use is likely (URLs, arithmetic, search/time cues) and only then constrain generation — zero extra inference cost
  - Repeat-call **loop detection**: identical tool calls are refused and the model is pushed to answer from the result it already has
  - Built-in tools: `web_search`, `read_url`, `calculate`, `datetime` (+ file sandbox & code tools on Android)
- **Desktop CLI** (JVM, v0.3.0) — same core engine on your computer
  - `pocketllm chat` — interactive REPL with history (`/reset`, `/state`)
  - `pocketllm serve` — OpenAI-compatible server (`/v1/chat/completions`, streaming + JSON)
  - `pocketllm agent` — one-shot tool-using agent for scripting
- Optional **web search augmentation** (DuckDuckGo, Brave, Tavily, Bing, Firecrawl)
- Optional **auto-speak** responses using Android TTS
- **Usage logging and analytics** in-app, plus a **Logs tab** with on-device export
- Optional **HTTPS** with generated self-signed certificate and fingerprint display

## Architecture

```
├─ app (Android)                ┆  ┌────────────────────────────┐
│  Compose UI + AppViewModel    ┆  │ core (platform-neutral JVM)│
│  ├─ ModelRepository           ┆  │ ├─ agent/ AgentLoop, tools,│
│  ├─ ApiKeyRepository          ┆  │ │  ToolGrammarBuilder,     │
│  ├─ SettingsRepository        ┆  │ │  ToolRouter              │
│  ├─ UsageRepository           ┆  │ ├─ llm/ LlamaEngine (JNI), │
│  ├─ ApiServer (Ktor, TLS)     ┆  │ │  ChatEngine, CpuInfo     │
│  └─ ServerLog → PLog sink     ┆  │ ├─ server/ OpenAI types    │
└─ cli (desktop JVM)            ┆  │ ├─ hf/, util/              │
   chat / serve / agent         ┆  │ └─ PLog logging facade     │
                                ┆  └────────────────────────────┘

LlamaEngine → LlamaBridge (JNI) → llama_jni.cpp → llama.cpp
```

Generation is serialized through a dedicated single-thread path with synchronization, so one inference runs at a time. Android and the CLI share the same `core` module; the app registers `ServerLog` as the `PLog` sink so core logs land in the in-app Logs tab.

## Requirements

- Android Studio (recent stable)
- Android SDK 35
- Java 17
- Android NDK + CMake (3.22.1)
- Device/emulator API 26+

## Build (Android)

```bash
# from repo root (needed on fresh clones because llama.cpp is not committed)
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

./gradlew :app:assembleDebug
```

Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Desktop CLI (Linux aarch64)

Download the self-contained binary from **Releases** (no Java install needed):

https://github.com/Kimi-syl/PocketLLM/releases/latest

```bash
tar xzf pocketllm-*-linux-aarch64.tar.gz
cd pocketllm-*-linux-aarch64
./bin/pocketllm chat --model /path/to/model.gguf
```

## Build the CLI from source

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

# build the CLI distribution
./gradlew :cli:installDist

# build libpocketllm.so for desktop (gcc/g++ + JDK JNI headers)
JAVA_HOME=/path/to/jdk17 ./build-native-desktop.sh

# point the CLI at the native library
export POCKETLLM_NATIVE_LIB=$PWD/build-native-desktop/libpocketllm.so

BIN=cli/build/install/cli/bin/pocketllm
$BIN chat  --model /path/to/model.gguf --threads 4
$BIN serve --port 8080 --model /path/to/model.gguf
$BIN agent --model /path/to/model.gguf "What is 23*7+4?"
```

`serve` speaks the same OpenAI wire format as the Android app:

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello!"}],"stream":true}'
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

- Server binds to `0.0.0.0`; LAN clients can reach it when enabled. The CLI `serve` command binds to `127.0.0.1` by default and has no auth — local use only.
- Keep **Require API key** on for non-local testing.
- API keys are stored in app-private storage.
- HTTPS is optional and uses a self-signed certificate (clients must trust/allow it, e.g. `curl -k`).
- Cleartext HTTP remains available for trusted local/dev networks.

## Known limitations

- One loaded model/inference stream at a time (requests serialize)
- Long prompts/conversations may be truncated to fit context window
- No foreground service yet, so long operations may be interrupted if app/process is killed
- Current packaged ABI target is arm64-v8a
- Grammar-constrained tool calls guarantee the tool-call *format*; argument *content* quality depends on the model — sub-500M models may copy few-shot example values instead of using your numbers. 1B+ models behave much better.

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
./build-native.sh        # Android .so (clang, arm64) → jniLibs → APK
JAVA_HOME=/opt/jdk17 ./gradlew --no-daemon :app:assembleDebug

./build-native-desktop.sh   # desktop .so (gcc) for the CLI
JAVA_HOME=/opt/jdk17 ./gradlew --no-daemon :cli:installDist
```

## Roadmap

- Foreground service for more robust background download/inference
- GPU offload variants (Vulkan/OpenCL)
- Expanded per-key analytics
- Better model metadata/RAM estimation
- Service discovery (mDNS)
- Kotlin Multiplatform core (JVM + Android + iOS targets) with a SwiftUI app scaffold

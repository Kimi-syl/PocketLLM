# PocketLLM

Run GGUF LLMs locally on Android with llama.cpp and expose them through an OpenAI-compatible API for on-device apps and LAN clients. A shared, platform-neutral **core** module also powers a desktop JVM **CLI** — the same engine, agent loop, and OpenAI-compatible server on your phone and in Termux/proot or on Linux. An **iOS** app runs the same models through MLX on Apple Silicon.

## Features

### Inference

- Native **llama.cpp** inference via JNI/NDK with **Vulkan GPU offload** (**arm64-v8a** build target)
- **CPU kernel dispatch**: two arm64 builds ship in the APK — an ARMv8-A baseline and an `armv8.2-a+dotprod+fp16` variant. The app reads `/proc/cpuinfo` and loads the optimised one only when the device reports both DotProd and FP16, so it can never SIGILL on an A53-class core. The Settings card shows which build is actually loaded.
- **Vision (mmproj)**: llama.cpp's `libmtmd` is built and linked, exposing `loadVision` / `hasVision` / `visionDescribe`. A text GGUF plus a matching `mmproj` file gains image input. *The bridge is built; the UI does not yet offer it.*
- **OpenAI-compatible API**
  - `GET /v1/models`
  - `POST /v1/chat/completions` (streaming SSE + JSON)
  - `POST /v1/completions` (streaming SSE + JSON)
  - `GET /health`
- In-app **API key management** (create/revoke/enable/disable, last-used tracking)
- Optional **web search augmentation** (DuckDuckGo, Brave, Tavily, Bing, Firecrawl)
- Optional **auto-speak** responses using Android TTS, plus cloud **voice cloning** through the Fish Audio API

### Agent mode

- **Grammar-constrained tool calls** (GBNF applied at sampling time): the model can only emit *one valid* `TOOL: <name> ARGS: key=value;...` line for the tools you enable
- `ToolRouter` heuristics decide when tool use is likely and only then constrain generation — zero extra cost when unused
- Repeat-call **loop detection**: identical calls are refused and the model is pushed to answer from the result it already has
- Built-in tools: `web_search`, `read_url`, `calculate`, `datetime`, plus file sandbox, code tools and **UI automation** (accessibility service drives other apps)
- **Local memory**: SQLite-backed remember/forget/search, injected into the prompt
- **MCP** client: real JSON-RPC 2.0 over streamable HTTP (`initialize` → `tools/list` / `tools/call`), with `Mcp-Session-Id` and Bearer auth

### Image generation

- **Stable Diffusion** through a vendored `stable-diffusion.cpp`, built as a separate shared library so its bundled ggml cannot collide with llama.cpp's
- **Four independent slots** — text encoder (`llm`), diffusion model, VAE, and optional **LoRAs** with per-LoRA strength (`path|multiplier;...`)
- **Quantisation**: F16, Q8_0, Q5_K_M or **Q4_K_M** (default), applied by llama.cpp's own `llama_model_quantize` rather than a reimplementation
- **Safetensors → GGUF conversion on device**: a torch-free Python converter runs in the bundled PRoot sandbox. Checkpoints convert themselves on first sight and the result is cached, so the step is invisible after the first load
- Multi-file architectures (Qwen-Image, Flux, Anima, SD3) are supported; filenames are routed to the right slot automatically

### iOS

- **MLX** backend on Apple Silicon via `mlx-swift-lm`, with **TTFT and tokens/sec** reported per reply
- **Vision-language models**: `MLXVLM` gives photo analysis; the app reads `config.json` for the keys that only appear on multimodal checkpoints and only offers the attachment when the loaded model genuinely declares vision support
- Per-model **details panel** with detected capabilities and evidence
- Persistent **Logs** tab, mirrored to disk so an entry survives a crash

### Platform

- **Desktop CLI** (JVM) — `chat`, `serve`, `agent` against the same core
- **Python sandbox** on Android: PRoot + Alpine rootfs with a real `apk`-based environment
- **Hugging Face discovery** and browsing; GGUF downloads limited to `.gguf` so nothing unloadable is offered
- **Resumable segmented downloader** with retry support and persisted metadata
- Built-in **chat screen** with **Markdown + LaTeX** rendering
- **Usage logging and analytics**, plus a **Logs tab** with on-device export

## Architecture

```
├─ app (Android)                ┆  ┌────────────────────────────┐
│  Compose UI + AppViewModel    ┆  │ core (platform-neutral JVM)│
│  ├─ ModelRepository           ┆  │ ├─ agent/ AgentLoop, tools,│
│  ├─ ApiKeyRepository          ┆  │ │  ToolGrammarBuilder,     │
│  ├─ SettingsRepository        ┆  │ │  ToolRouter              │
│  ├─ UsageRepository           ┆  │ ├─ llm/ LlamaEngine (JNI), │
│  ├─ ApiServer (Ktor, TLS)     ┆  │ │  ChatEngine, CpuInfo     │
│  ├─ img/ SdBridge,            ┆  │ ├─ server/ OpenAI types    │
│  │  ImageGenManager           ┆  │ ├─ hf/, util/              │
│  └─ ServerLog → PLog sink     ┆  │ └─ PLog logging facade     │
└─ cli (desktop JVM)            ┆  └────────────────────────────┘
   chat / serve / agent         ┆
├─ ios-app (SwiftUI)            ┆  ┌────────────────────────────┐
│  MLXEngine / LocalEngine      ┆  │ third_party/               │
│  VLMEngine / SdEngine         ┆  │ └─ sd  (stable-diffusion)  │
│  ModelInspector / LogStore    ┆  └────────────────────────────┘
└─ cli (desktop JVM)

LlamaEngine → LlamaBridge (JNI) → llama_jni.cpp → llama.cpp (+ libmtmd)
SdEngine    → SdBridge (JNI/ObjC++) → sd_jni.cpp / SdBridge.mm → stable-diffusion.cpp
```

The two native engines are deliberately separate libraries: `stable-diffusion.cpp` vendors a patched ggml, and building it as its own `.so` keeps its symbols from colliding with llama.cpp's at load time.

Generation is serialized through a dedicated single-thread path, so one inference runs at a time. Android and the CLI share `core`; the app registers `ServerLog` as the `PLog` sink so core logs land in the in-app Logs tab.

## Requirements

- Android Studio (recent stable) or the on-device toolchain below
- Android SDK 35, Java 17, NDK + CMake 3.22.1
- Device/emulator API 26+
- iOS: Xcode 26 / Swift 6.2 (required by `mlx-swift-lm` 3.x), deployment target **iOS 17**

## Build (Android)

```bash
# from repo root (needed on fresh clones because llama.cpp is not committed)
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

./gradlew :app:assembleDebug
```

Image generation needs the second native library:

```bash
./build-sd.sh          # builds libsdcpp.so for the same ABI
```

Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Build (iOS)

```bash
cd ios-app
xcodegen generate
xcodebuild -scheme PocketLLM -destination 'generic/platform=iOS' build
```

The iOS build additionally compiles `stable-diffusion.cpp` for both the simulator and device slices, pinned alongside llama.cpp.

## Desktop CLI (Linux aarch64)

Download the self-contained binary from **Releases**:

https://github.com/Kimi-syl/PocketLLM/releases/latest

```bash
tar xzf pocketllm-*-linux-aarch64.tar.gz
cd pocketllm-*-linux-aarch64
./bin/pocketllm chat --model /path/to/model.gguf
```

## Build the CLI from source

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp

./gradlew :cli:installDist
JAVA_HOME=/path/to/jdk17 ./build-native-desktop.sh
export POCKETLLM_NATIVE_LIB=$PWD/build-native-desktop/libpocketllm.so

BIN=cli/build/install/cli/bin/pocketllm
$BIN chat  --model /path/to/model.gguf --threads 4
$BIN serve --port 8080 --model /path/to/model.gguf
$BIN agent --model /path/to/model.gguf "What is 23*7+4?"
```

`serve` speaks the same OpenAI wire format as the Android app.

## Quick start

1. Download the latest APK from **Releases**: https://github.com/Kimi-syl/PocketLLM/releases/latest
2. Open **Models**, search/download a GGUF model, then load it.
3. Open **Server** and start the server; open **Keys** and create an API key.
4. Point your OpenAI client at the phone.

```bash
curl http://<phone-ip>:8080/v1/chat/completions   -H "Authorization: ******" \
  -H "Content-Type: application/json" \
  -d '{"model":"your-model","messages":[{"role":"user","content":"Hello!"}],"stream":true}'
```

For on-device clients use `http://127.0.0.1:8080/v1`.

### Image generation

1. Settings → 進階設定 → **模型轉換**: choose a quantisation (Q4_K_M by default).
2. Put a checkpoint in the models folder — a single-file SD checkpoint, or a multi-file set. Conversion to GGUF runs automatically and is cached.
3. **圖片生成** tab: pick the model, set size/steps/cfg, generate.

Multi-file models need their components in the matching slots. Anima, for example, needs its diffusion model, the **Qwen Image VAE**, and **Qwen3-0.6B-Base** as the text encoder — a generic T5/CLIP encoder will not work.

## Security notes

- The Android server binds to `0.0.0.0`; LAN clients can reach it when enabled. The CLI `serve` binds to `127.0.0.1` and has no auth — local use only.
- Keep **Require API key** on for non-local testing.
- API keys are stored in app-private storage.
- HTTPS is optional and uses a self-signed certificate (clients must trust/allow it, e.g. `curl -k`).
- The persistent log is written to app-private storage in plain text, and survives crashes by design. Anything you would not want in a crash report should not be logged.

## Known limitations

- **APKs from CI are signed with a generated debug key**, so each build has a different signature and cannot upgrade a previous install in place. A release keystore is not yet configured.
- One loaded model/inference stream at a time (requests serialize)
- Long prompts/conversations may be truncated to fit the context window
- No foreground service yet, so long operations may be interrupted if the process is killed
- Packaged ABI target is **arm64-v8a**
- **x86_64 is built by CI only, and without the Python sandbox** — `libproot`/`libtalloc`/`libandroid-shmem` are arm64-only prebuilts with no source in-tree, and the bundled Alpine rootfs is aarch64
- On iOS, image generation runs with the text encoder pinned to the CPU (`te=cpu`, `*=cpu`), matching the engine's documented Anima configuration. Generation does not yet run on a background queue that reports per-step progress in the UI thread — long runs block the view.
- Grammar-constrained tool calls guarantee the tool-call *format*; argument *content* quality depends on the model — sub-500M models may copy few-shot example values instead of using your numbers

## Building on-device (Termux + proot, no Android Studio)

| Path | Purpose |
|---|---|
| `/opt/tusr` | Termux clang/lld clone (avoids proot self-exec shim issues) |
| `/opt/jdk17` | JDK 17 for Gradle/AGP |
| `/opt/gradle` | Gradle runtime |
| `/opt/android-sdk` | Android cmdline-tools + SDK/platform packages |
| `/opt/aapt2arm` | static aarch64 aapt2 binary |

```bash
. /opt/tenv.sh
cd ~/pocketllm
./build-native.sh        # llama.cpp + shims → jniLibs → APK
./build-sd.sh            # stable-diffusion.cpp → libsdcpp.so
JAVA_HOME=/opt/jdk17 ./gradlew --no-daemon :app:assembleRelease \
  -PpocketllmAbi=arm64-v8a -Pandroid.aapt2FromMavenOverride=/opt/aapt2arm/build-tools/aapt2

./build-native-desktop.sh   # desktop .so for the CLI
JAVA_HOME=/opt/jdk17 ./gradlew --no-daemon :cli:installDist
```

## Roadmap

- Sign releases with a real keystore, and wire the secrets the workflow needs
- Foreground service for robust background download/inference
- Surface the `libmtmd` bridge in the UI so `mmproj` models are selectable
- On-device model catalogue for image generation (so it works without side-loading)
- Move iOS image generation fully off the main actor with per-step progress
- Bump the pinned llama.cpp (currently `b10615`, ~814 commits behind `v0.6.0`)
- Service discovery (mDNS) and expanded per-key analytics

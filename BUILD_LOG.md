# PocketLLM — Build Log

A consolidated record of the engineering session that took PocketLLM from a working
Android on-device LLM app to a two-platform app with image generation, vision, and a
rebuilt iOS pipeline.

- **Repository:** `Kimi-syl/PocketLLM`
- **Commits in this log:** ~270 (full history), ~140 in this session
- **Platforms:** Android (arm64-v8a, x86_64) · iOS (arm64 device)
- **Engines:** llama.cpp `b10615` (`f280b269`) · stable-diffusion.cpp `3f8527a4` · MLX (`mlx-swift-lm` 3.32.3)
- **Log written:** 2026-10-18

---

## 1. Where things stand

### Android — working and shipped

| feature | state |
|---|---|
| On-device LLM inference (llama.cpp) | working |
| CPU kernel dispatch (DotProd/FP16 with runtime fallback) | working |
| Vulkan/OpenCL GPU offload with CPU fallback | working |
| Image generation (stable-diffusion.cpp, 7 slot dropdowns) | built, CPU-only |
| Vision via `libmtmd` (mmproj) | built and linked |
| LLM LoRA adapters | built and verified in the binary |
| Decision model (swap-based, `liquidAI/d1-3b` default) | built |
| Python sandbox (PRoot + Alpine, bundled) | working |
| MCP client, SQLite memory, voice, agent | working |
| safetensors → GGUF conversion, transparent + cached | working |
| Q4_K_M quantization via llama.cpp's own quantizer | working |

Latest APK: **`arm64-pocketllm-v0.6.1-rc7.apk`**
`sha256 336fbbba00bb3d0218bb2fedc22d31038428a4499f0dcac7e32bf5f1fa8deb62`

### iOS — builds, partially verified

| feature | state |
|---|---|
| LLM chat (llama.cpp, Metal offload) | working |
| MLX backend + model browser | working |
| TTFT / tokens-per-second metrics | built |
| Logs tab (persistent, crash-surviving) | working |
| Image generation (stable-diffusion.cpp) | runs on Metal — but ~10 s/step trips the GPU watchdog |
| Vision via `libmtmd` (mmproj) | built and linked — replies blank |

Latest artifact: `PocketLLM-device-unsigned` from run `39207506905`.

### Known-open

1. **iOS image generation** — no longer collides on ggml, no longer CPU-only (§2.13); the diffusion
   model now runs on Metal. It dies under the iOS GPU watchdog (`progress timeout`) because a step
   takes ~10 s. The engine's own log (auto-fit plan, segment count, CPU fallbacks) was being
   dropped and is now captured (§2.14); the placement fix follows from it.
2. **iOS vision** — the pipeline reaches the model, produces a single space. The native
   stage logs are not appearing at all, which is the current investigation.
3. **Android MTP / speculative decoding** — blocked: the API lives in `common/speculative.h`
   which the Android CMake does not build.
4. **iOS LoRA / decision model** — not started.
5. **Release signing** — every APK is signed with a generated debug key; no release key exists
   in the repo or as secrets.

---

## 2. Chronological record

### 2.1 Origins (2026-08-25 → 2026-09-02)

`40d6de9` initial release — on-device llama.cpp inference with an OpenAI-compatible API server.
Rapid UI work followed (Material You, navigation drawer, settings hub), then HTTPS with
self-signed TLS, selectable search engines, TTS, and a web-search-in-chat flow.

**v0.2.5** (`7d143a4`) added agent mode with 3 tools, Piper TTS, sessions and metrics.
**v0.2.6** (`80534bd`) expanded to 9 tools with a long-press tool picker.
**v0.2.7** (`b574af6`) fixed an agent-mode SIGABRT and added a `/logs` endpoint.

Crash-hardening cluster: `321c9ff` hard-capped token generation to prevent a 10M-token crash,
`b3bf5ee` auto-generates crash reports on uncaught exceptions, `92b53cf` wrapped `sendChat`
in try-catch so failures log instead of dying silently.

### 2.2 Core/CLI split and grammar-constrained agent (2026-09-02 → 2026-09-03)

**v0.3.0** (`cc59cb6`) introduced the shared `core` module plus a JVM CLI. **`d788300`**
added grammar-constrained tool calls, a tool router and loop detection — the GBNF work that
later became the Android agent's constraint switch.

KMP work (`442f4a1`, `2f248ee`) added the iOS framework and SwiftUI scaffold. `0b7e82b` and
`222a89f` made the common code Kotlin/Native-clean.

### 2.3 iOS application (2026-09-03 → 2026-09-22)

`018f0ba` brought on-device inference to iOS: `LlamaBridge.mm` over llama.cpp, a local engine,
and CI. Several ObjC++ interop fixes followed immediately — `a2653da` (an `NSError**` imports
as `throws`, so the extra argument had to go), `71da078` (capture `self` in blocks, `std::max`
types), `7713338` (`extern "C"` for the C functions), `3441516` (link Accelerate for vDSP).

Model library and MLX arrived in `79299be` and `546577e`; `9f6c42f` added Metal GPU offload
and markdown rendering. `6952f82` added whole-hub MLX search and a sort dropdown.

### 2.4 GPU bring-up on Android (2026-09-03 → 2026-09-07)

**v0.3.3** (`dec7a62`) added Vulkan GPU offload; **v0.3.4** (`34bef55`) added ggml log capture
and `backendInfo` in the UI; **v0.3.5** (`52e1d18`) added the OpenCL backend for Adreno via a
dlopen shim; **v0.3.6**/**v0.3.7** added diagnostics and switched to
`android_load_sphal_library`.

Then the Turnip/Vulkan crash campaign:

- `ac325f0` integrated the Turnip driver
- `11cde55` exported all direct link references to fix a load crash
- `e3ae2d9` gated Turnip behind an opt-in flag to stop a native crash loop
- `5bee10d` made the Turnip probe **fork-isolated** so a bad driver cannot take the app down
- `a6407ae` fixed `libvkshim.so` not found by using bare sonames in `DT_NEEDED`
- `76d5aa2` detected GPU vendor and refused Turnip on non-Adreno

### 2.5 Companion, VRM and Live2D (2026-09-11 → 2026-09-24)

Floating companion (`215eb11`, `8e6a998`), long-term memory and personality (`db77434`),
proactive check-ins (`0211cc6`), voice input (`e5317f8`), web-grounded answers (`5c67de4`),
reminders and mood check-ins. **v0.4.0** (`607e91f`) added Live2D and VRM characters with
settings tabs.

### 2.6 Accessibility agent and the Vulkan shim (2026-09-28 → 2026-10-04)

`806c70c` added the accessibility agent (an "eyes/hands/ReAct loop") plus GPU-to-CPU
auto-fallback. `8013641` brought the full UI, companion and bilingual support.

The `vulkan_shim.c` hardening ran through this stretch and is worth recording in detail
because the final guard is what makes GPU failure survivable:

| commit | change |
|---|---|
| `3b83ab3` | zero the feature chain when `vkGetPhysicalDeviceFeatures2` is unresolved |
| `1e3ffa7` | bound the features2 fallback by `sType`, not a fixed 4096 |
| `c9456d2` | KHR alias fallback for physical-device features2 + instance tracking |
| `702aa48` | cache the GPU probe verdict; refuse `vkCreateDevice` on broken drivers |
| `c602792` | `no_host` for CPU loads + full-path GPU probe |
| `d0508e4` | **rebuild `libpocketllm.so` — every JNI fix since v0.3.9 had been inert** |
| `b6e1a9b` | state-race fix, Turnip fork isolation, 32 MB probe buffer |

The `d0508e4` entry is the important one: `vulkan_shim.c` was never in the Gradle build, and
`build-native.sh` copied a prebuilt `libvkshim.so` from `vk-host/`. Every shim fix was correct
and none of it reached the device.

Two root causes were established here that recur later:

- **Vulkan host-buffer allocation** — llama.cpp prefers "pinned memory" via a Vulkan buffer
  (`vkCreateBuffer` → `vkAllocateMemory` → `mapMemory`) whenever Vulkan is registered, so even
  `gpuLayers=0` routed real tensors through it. Fixed with `no_host = true` and
  `GGML_DISABLE_VULKAN=1` when the probe finds a broken driver.
- **KHR fallback correctness** — `VK_KHR_get_physical_device_properties2` aliases are pure
  spec aliases (same enum values and layout), so no numeric `sType` conversion is needed.
  Mali-G68 Valhall does support `VK_KHR_shader_float16_int8`; the earlier `fp16: 0` was an
  artifact of the broken features query.

### 2.7 Feature build-out (2026-10-04 → 2026-10-05)

`77d0eaa` added the CPU report, strict JSON grammar, memory store and voice cloning.
`047e632` added the MCP client, a safetensors reader, a real PRoot sandbox, assistant role
handling and offline voice. `ac077fa` bundled PRoot + an Alpine rootfs so the Python sandbox
works out of the box. `6c09560` added on-device safetensors → GGUF conversion for Llama-family
≤1B.

**The converter decision** is worth recording: llama.cpp's `convert_hf_to_gguf.py` is
impossible on-device (20k lines, 1213 torch references, no musl/aarch64 torch wheels), so
`assets/convert/convert_llama.py` is a torch-free numpy converter scoped to Llama/Mistral
≤1B. It was validated by round-tripping a synthetic BF16 checkpoint through the official
`gguf` reader, which caught a real bug where 1-D RMS-norm tensors were skipped.

`5519d65` fixed five device-level bugs: accessibility zombie state (MIUI/P20HD), package
visibility on Android 11+, the assistant-settings jump, SELinux `chown` during rootfs
extraction (replaced external `tar` with a pure-Kotlin extractor), and CPU baseline safety.

`350d92c` shipped the DotProd/FP16 kernel build with runtime CPU dispatch — verified by
disassembly (`libpocketllm.so` 0 SDOT/UDOT, `libpocketllm_v82.so` 1045).

`702cbc4` added on-device image generation via stable-diffusion.cpp.

### 2.8 CI bring-up for x86_64 (2026-10-06)

`2d6e29a` added the x86_64 target and a pre-release workflow. The x86_64 build cannot be
produced in the dev container — `/opt/native-toolchain.cmake` targets the aarch64 sysroot at
`/opt/tusr`, and the NDK's host compiler binaries do not run under proot — so it moved to CI.
Thirteen blockers were cleared, and **nine of them were the same class**: dev-container state
leaking into shared build config.

| # | failure | fix |
|---|---|---|
| 1 | `setup-android` installing the removed `tools` package | dropped it, use the runner SDK |
| 2 | `llama.cpp` missing (gitignored nested clone) | fetch + pin `f280b269` |
| 3 | `libvkshim.so` incompatible with `elf_x86_64` | exclude the arm64 shim from the x86_64 link |
| 4 | undefined `vulkan_shim_*` | compile the shim sources into x86_64 |
| 5 | `'CL/cl.h' file not found` | add the in-tree OpenCL include path |
| 6 | `aapt2` path `/opt/aapt2arm/…` | pass the override only where that binary exists |
| 7 | arm64 step had no toolchain | select the NDK toolchain for both ABIs |
| 8 | `Could NOT find Vulkan (missing: glslc)` | install `glslc` |
| 9 | non-existent `/opt/tusr/usr/include` | conditional `Vulkan_INCLUDE_DIR` |
| 10 | `vulkan/vulkan.hpp` not found | stage the Vulkan C++ headers |
| 11 | glibc `bits/wordsize.h` leak | stage only `vulkan/`, not `/usr/include` |
| 12 | `spirv/unified1/spirv.hpp` | stage SPIR-V headers alongside |
| 13 | debug keystore absent | generate it on demand |

A matrix restructure (`fafcf22`) then made each ABI its own job with its own filesystem, and
`0e143d0` asserted each APK really is that ABI before signing.

### 2.9 The iOS MLX upgrade (2026-10-09 → 2026-10-10)

`mlx-swift-examples` pinned `mlx-swift` to `0.29.x`, which blocked every newer architecture.
The fix was to move to `ml-explore/mlx-swift-lm`, the maintained home of the LLM layer. The
upgrade surfaced a chain of constraints, one per layer:

| failure | cause |
|---|---|
| `mlx-swift` range conflict | `mlx-swift-examples` pinned `0.29.x` |
| `incompatible tools version (6.2.0)` | `mlx-swift-lm` 3.x needs Swift 6.2 |
| `stdio.h file not found` | stale CMake cache + a prefix `restore-keys` resurrecting it |
| `module 'MLX' has minimum deployment target iOS 17.0` | app targeted iOS 16 |
| missing arguments `from:`/`using:` | 3.x changed `loadContainer` |
| `no such module 'MLXLMTokenizers'` | `TokenizersLoader` is macro-generated, not a product |
| `Macro must be enabled` | `MLXHuggingFaceMacros` is a target, not a linkable product |
| `any Tokenizer does not conform to Tokenizer` | the macro also wraps with `#adaptHuggingFaceTokenizer` |

The resolution was to inline the macro expansion as a hand-written `TokenizerBridge`
implementing all nine `MLXLMCommon.Tokenizer` requirements. The pattern here — the 3.x API
expects callers to compose the pieces rather than import convenience macros — repeated four
times.

Also in this stretch: the `qwen3_5` → `qwen3` normalizer was removed after the configs were
compared directly. Qwen3.5 is a **hybrid linear-attention multimodal** architecture
(`layer_types`, `linear_conv_kernel_dim`, `head_dim: 256`), not a Qwen3 variant, so mapping
it down could only ever produce shape errors or garbage.

### 2.10 iOS image generation and the ggml collision (2026-10-11 → 2026-10-17)

`73df8e1` added image generation to iOS via stable-diffusion.cpp, mirroring the Android
bridge. Five ObjC++ interop bugs followed, each found by the compiler: readonly `SdImage`
properties with KVC, `free(images)` instead of `free_sd_images`, `NSData` vs `Data`, a missing
bridging-header import, and the `NSError**`/`throws` rule.

Then a long debugging campaign on the actual generation crash, with **three wrong theories
before the evidence**:

| theory | verdict |
|---|---|
| VAE-decode OOM | wrong — died at 656 MB of 7643 MB |
| wrong text encoder / quantization | wrong — files were correct, Q3→Q5 changed nothing |
| flash attention on a CPU-only build | wrong — same crash after removing it |

The `.ips` report settled it:

```
SIGABRT · ggml_abort
  ggml_new_tensor_impl
  ggml_backend_sched_reserve_size
  AnimaConditioner::get_learned_condition
```

And `docs/anima.md` showed Anima is a **three-file** model: the diffusion model, a
**Qwen3-0.6B-Base** text encoder in the `llm` slot, and the **Qwen Image VAE**. The `te=cpu`
backend assignment (from `common.cpp`'s `prepend_backend_assignment`) then moved the failure
forward into compute.

`llvm-nm` on the shipped `.ipa` finally produced the real cause:

```
_ggml_new_tensor_impl     defined more than once
_ggml_new_object          defined more than once
```

**Two ggml implementations in one iOS binary.** Android never had this because `libsdcpp.so`
is a separate shared library with `-fvisibility=hidden`. The attempted fix
(`SD_BUILD_SHARED_GGML_LIB=ON`) was verified to produce a loadable, dylib-containing `.ipa`
— after three packaging bugs of its own — but it made things **worse**: two ggml-base
*instances* means two backend registries and two Metal singletons, so `llama_context` creation
died in `ggml_metal_buffer_is_shared`. It was reverted in `ea2133f`.

The remaining correct fix is to rename sd's ggml symbols so one process has one ggml
namespace (`llvm-objcopy --redefine-syms`), which is a real change rather than a build flag.

### 2.11 iOS vision via libmtmd (2026-10-16 → 2026-10-18)

`libmtmd` is vendored at `tools/mtmd` and builds cleanly once `MTMD_VIDEO=OFF` is set — the
video helper pulls in `vendor/sheredom/subprocess.h`, which needs `posix_spawn_file_actions_*`
APIs absent from Android's bionic.

The iOS workflow was simplified (`87e05f9`) to drop the simulator slice, which removed ~half
the build time. That exposed **three things the simulator step had been doing for the device
path** — all invisible from the step's name:

1. the header copy (caught before deleting)
2. the `ci-llama` git checkout
3. the `mkdir` for the header directory

Vision then progressed through a series of one-line faults, each hiding the next:

| symptom | cause |
|---|---|
| `tokenize failed` | `mtmd_input_text.text_len` left uninitialised |
| `failed to evaluate the image prompt` | dirty KV cache + a 2048 context too small for a 1280 px image |
| app freeze, then watchdog kill | the synchronous native call ran on the main thread |
| a single space, then nothing | the chat template was never applied |

The current open issue is that the native stage logs never appear, which is the subject of
the last three commits.

### 2.12 Model search rework (2026-10-14 → 2026-10-17)

- `56f9a00` added the `image-text-to-text` category and optional safetensors listing
- `019a90d` merged text and vision search, dropping VLM as a separate kind
- `8f61b95` made iOS send both `mlx` and `transformers` Hub tags

The governing constraint: **the Hub ANDs repeated filters, and a repository carries exactly
one `pipeline_tag`**, so `text-generation` and `image-text-to-text` cannot be combined in one
request. The union is two requests merged and re-sorted.

### 2.13 iOS image generation on Metal via an engine hand-off (2026-10-18 → 2026-10-19)

The ggml-collision fix (§2.10) left image generation working but **CPU-only**: a single photo
took ~7 minutes on an M2 iPad. Metal stayed off because two live ggml-Metal copies abort llama
at `llama_context` creation (`ggml_metal_buffer_is_shared`), and the one-ggml rename
(`llvm-objcopy --redefine-syms`) is a real change, not a flag.

The chosen route avoids both: **only one engine holds the GPU at a time**. `ComputeCoordinator`
is a lock-guarded registry of teardown closures that the per-tab `StateObject`s — which never
see each other — register into. `SdEngine.load` frees the LLM first; `LocalEngine.load` frees
the image engine first. The key correction over a naive "deinit the context" is that
`unload()` also calls a new `LlamaBackendFree()` (`llama_backend_free`, refcounted against
`LlamaGlobalInit`): freeing the context releases the model's buffers but leaves llama's
ggml-Metal *device* alive, so without it the GPU would never actually have been handed over.

Build-side (`ios.yml`):

| change | reason |
|---|---|
| `SD_METAL=ON`, `GGML_METAL=ON`, `GGML_METAL_EMBED_LIBRARY=ON` | diffusion model + VAE on the GPU; embedded shaders, same as llama |
| `CMAKE_CXX_VISIBILITY_PRESET=hidden` (+ C, inlines) | this dylib is built static-ggml, so `GGML_API` is plain `extern` and the preset hides it — the dylib's ggml cannot interpose on llama's |
| `te=cpu` kept, `params_backend` left default | the text encoder's graph reservation still aborts on the GPU; only diffusion/VAE move to Metal |

CI run `39207506905` is green (14m47s). The verify step now also asserts the dylib has the
Metal backend, still exports the SD API, and does not export ggml — the last one caught its own
first false positive: the embed step emits `ggml_metallib_start`/`_end` with default visibility
on purpose (shader-source pointers, not the code symbols whose duplication broke generation),
so they are excluded from the check.

**Not verified:** whether the hand-off actually prevents the crash at runtime. If llama's
context creation still dies alongside SD's Metal, the fallback is the one-ggml rename in §6.

### 2.14 Image generation on Metal, round two: the watchdog (2026-10-19)

Device run of the §2.13 build answered the open question: **the hand-off works.** The
ggml-Metal collision is gone and stable-diffusion.cpp executes the diffusion model on the GPU.
One 512×512, 10-step image went from ~39 s/step (CPU) to ~10 s/step. But it still fails, and now
for a different reason.

| run (device log) | sampling | outcome |
|---|---|---|
| 2026-10-19 00:01 (CPU build) | ~39 s/step | succeeded, 422 s total |
| 2026-10-19 14:48 (Metal build) | 15 s, then a 3.5 min gap at step 3 | killed |
| 2026-10-19 15:05 (Metal build) | ~10 s/step | killed at step 7/10 |

The kill is `gpuEvent-...txt`: `bug_type 284`, `restart_reason_desc = "progress timeout"`. That is
the iOS GPU watchdog, not a memory or CPU-time kill — a Metal command buffer that makes no
progress for ~10 s gets the process terminated. At 10 s/step the per-step graph sits right on
that budget, so the crash lands mid-sampling (step 3, then step 7) and is timing-dependent, not
deterministic. The step time has to come down well under the budget, not the watchdog raised.

Where the time actually goes is not yet known, because the engine's own explanation never
reached anyone. Two independent defects hid it:

- `setLogFile:` ran **after** `new_sd_ctx`, so anything the engine logged while creating the
  context — and the first Metal graph — went nowhere.
- Even the lines that were written were raw (`ggml_runner` text, no timestamp), and
  `LogStore.loadFromDisk` drops any line that does not start with `yyyy-MM-dd HH:mm:ss.SSS`, so
  they vanished on the next launch and never appeared in an export.

Fixed in `SdBridge`:

| change | reason |
|---|---|
| `+setLogPath:` (class method), called in `SdEngine.load` **before** `new_sd_ctx` | the file is open before the call that aborts |
| `sd_log_cb` re-formats every line to LogStore's exact shape (`timestamp  [native]  [sd/LEVEL] …`) | parses back on reload; no second timestamp (logNative adds its own) |
| hand-rolled `gettimeofday`/`strftime` timestamp, not `NSDateFormatter` | a formatter per line is measurable next to a run already racing the watchdog |
| DEBUG/VERBOSE go to the file only; INFO+ also mirror to the live tab | the sampler can emit them in bursts; a main-actor hop for each is too much |

With the callback installed, `sd_log_dispatch` forwards **every** level (it has no filter), so the
file now gets the auto-fit plan (`auto-fit plan:` / `components (params: compute device -> RAM ->
…)`), any ggml "unsupported op" fallbacks, and ggml_runner's
`compute buffer size: … MB(VRAM) on … (peak across N segment)`. Those three lines are what
separate "the step is slow because diffusion weights are staged from RAM/disk" from "the step is
slow because part of the graph runs on the CPU", and the next placement fix depends on which it
is.

**Not verified:** whether the gathered log explains the 10 s/step — it needs a device run of this
build. `diffusion_flash_attn` was deliberately **not** enabled: `performance.md` says flash
attention *slows down* most non-CUDA backends (Metal included), which is the wrong direction when
the goal is to get under a time watchdog.

---


## 3. Recurring failure patterns

These account for the large majority of the session's bugs and are worth stating explicitly.

**1. A step runs, reports success, and does its work on the wrong thing.**

- The dylib embed wrote to the **simulator** bundle (`ios-app/build` vs `ios-app/build-device`)
- The header copy had no `mkdir` because it lived in a deleted step
- The `ci-llama` checkout lived inside the simulator step
- The ggml duplicate guard read a **stale cached** app and reported `0` while the binary had 1299 ggml symbols
- The embed ran **after** the `.ipa` was packaged

Every one reported success. Only opening the artifact (`llvm-nm`, `unzip -l`) caught them.

**2. `|| true` and `2>/dev/null` hiding real failures.** Three separate silent failures came
from writing these to make a step "not fail" — the sd-libs collection, the mtmd header copy,
and the dylib embed.

**3. Shell quoting destroying script content.** Four distinct traps, each costing a build:
`${…}` expansion, `${{ }}` in workflows, backticks in a commit message, and `\n` collapsing
into a literal newline inside a C string. Writing scripts to a file with `write_file` avoided
all of them.

**4. Text-level edits applied partially.** A fuzzy match that un-balanced `ChatView`, a struct
removal that left `private s` fragments, a `@Published` inserted inside a doc comment, a
`@Composable` annotation consumed by an adjacent patch, and two method declarations placed
outside their `@interface`/`@implementation`. Reading the result back caught every one —
after the first attempt.

**5. A synchronous native call from Swift blocking the main thread.** Three separate bridges
(`SdEngine`, `VLMEngine`, and the llama vision path) each needed the same treatment, and each
was written without it.

**6. Reasoning from symptoms instead of measuring.** The image-generation crash took three
wrong theories; `llvm-nm` found it in one command. The same held for the vision crash report
and for the ggml guard.

---

## 4. Verification status

| claim | evidence |
|---|---|
| Android CPU dispatch | disassembly: 0 vs 1045 SDOT/UDOT |
| Android `setLoras` linked | `nm -D libpocketllm.so` → 1 |
| Android vision symbols | 3 JNI entries + 314 `mtmd_*` symbols |
| iOS vision linked | 6 `mtmd_*` symbols in the shipped `.ipa` |
| iOS ggml duplication | `llvm-nm` on the shipped binary: 1299 defined, 9 duplicated |
| iOS dylib isolation + Metal | run `39207506905`: app binary 1201 ggml defs, 0 duplicated; dylib has the Metal backend, exports the SD API, ggml local |
| iOS SD runs on Metal | device log (Metal build): sampling 10 s/step vs 39 s/step CPU build, i.e. the diffusion model is on the GPU |
| iOS SD GPU watchdog | `gpuEvent-...` `bug_type 284`, `restart_reason_desc = "progress timeout"`, killed at step 3 and step 7 |
| iOS native SD log reaches the file | not yet run on device (this build); the format is asserted against `LogStore.loadFromDisk` |
| iOS simulator slice removed | workflow step list: 26 → 21 steps |
| iOS dylib packaging (reverted) | `Frameworks/` in the `.ipa`: 13 dylibs, `libggml.0.dylib` present |
| Converter correctness | round-trip through the official `gguf` reader |
| Android Kotlin compiles | `:app:compileReleaseKotlin` BUILD SUCCESSFUL |

**Not verified:** anything requiring a device — iOS image generation, iOS vision output,
Android vision output, and all iOS UI changes. Every iOS build in this log is
compile-and-link evidence only.

---

## 5. Build and release reference

### Local Android build

```bash
JAVA_HOME=/opt/jdk17 ./build-native.sh          # POCKETLLM_ABI=arm64-v8a default
JAVA_HOME=/opt/jdk17 ./build-sd.sh
JAVA_HOME=/opt/jdk17 ./gradlew :app:assembleRelease -PpocketllmAbi=arm64-v8a
```

`POCKETLLM_ARM_ARCH="armv8.2-a+dotprod+fp16"` builds the optimized kernel variant.
`POCKETLLM_BUILD_JOBS=2` avoids the `ggml-quants.c` OOM kill.

### CI

- `.github/workflows/android-prerelease.yml` — matrix per ABI (arm64 sealed, x86_64 active)
- `.github/workflows/ios.yml` — device only, 21 steps, macOS 26 + Xcode 26

### Pinned revisions

```
llama.cpp               f280b26983ad0fdb705a0d9ebf0503e76f2899b0   (tag b10615, 2026-08-25)
stable-diffusion.cpp    3f8527a4
mlx-swift-lm            3.32.3
mlx-swift               0.32.x
swift-transformers      1.2.x
```

`llama.cpp` is **814 commits behind `v0.6.0`** (2026-10-05). The pin appears in **two** places —
the local clone and `LLAMA_COMMIT` in both workflows — and they must move together.

### Artifacts

| artifact | location |
|---|---|
| `arm64-pocketllm-v0.6.1-rc7.apk` | `/sdcard/Download/`, `~/downloads/` |
| `PocketLLM-device-unsigned` | run `39207506905` artifacts (29.4 MB) |
| `prerelease-20261006-1309` | `arm64-` and `x86_64-pocketllm-v0.6.0-beta.apk` |

Note: the `v0.6.0` **git tag** exists but its release was deleted when the split-ABI pair
replaced the single dual-ABI APK.

---

## 6. Next steps, in priority order

1. **iOS vision logging** — determine whether `describeImage` is reached at all, or whether
   the native writer fails. The probe is `bridge: loading mmproj`, emitted from a call already
   confirmed to succeed.
2. **iOS image generation under the GPU watchdog** — §2.13 (hand-off) is proven; §2.14 (logging)
   now exposes the engine's own reasoning. Run the logging build once, then read the auto-fit plan
   and the `compute buffer … (peak across N segment)` line: if diffusion weights are being staged
   from RAM/disk, pin them to the compute device (`params_backend`, or `auto_fit=false`); if ops
   are falling back to the CPU, that is a kernel-coverage problem. Target: per-step time well under
   the ~10 s watchdog budget. Fallback if the timing cannot be met is to stop sampling on Metal.
   The one-ggml rename (`llvm-objcopy --redefine-syms`) is no longer needed — the hand-off removes
   the collision.
3. **Android MTP / speculative decoding** — add `common/` (`common.cpp`, `sampling.cpp`,
   `speculative.cpp`) to the native build, or implement draft-and-verify directly against
   `libllama`.
4. **iOS LoRA and the decision model** — mirror `setLoras` in `LlamaBridge.mm`; the decision
   model has the same singleton constraint as Android.
5. **Release signing** — add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD` as secrets and replace the generated debug keystore in the workflow. Until
   then every CI prerelease has a different signature and cannot upgrade in place.
6. **`llama.cpp` upgrade** — a re-vendor, not a merge: replace the tree wholesale and re-apply
   the deltas (`llama_jni.cpp`, the CMake flags, `LLAMA_COMMIT`).

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

Latest artifact: `PocketLLM-device-unsigned` from run `39472414846`, released as **v0.6.0**.

### Known-open

1. **iOS image generation** — no longer collides on ggml, no longer CPU-only (§2.13); the diffusion
   model now runs on Metal. The `n_cb = 4` split (§2.15) took effect and survives the watchdog at
   1024×768 for the first steps, but a step at that size is ~31–38 s and step 3 died with a Metal
   command-buffer error (status 5, `InnocentVictim`). All-in-one single-file checkpoints (SD1.5/SDXL)
   failed to load entirely — they were handed to `diffusion_model_path`, which strips the model
   prefix and misses the embedded text encoder; fixed in §2.16 by routing them to `model_path`.
   §2.16 also exposes the Metal backend's read-at-init knobs (n_cb, concurrency, fusion) and the
   engine's full sampler/scheduler tables so the 1024×768 fault can be iterated on-device.
2. **iOS vision** — the pipeline reaches the model, produces a single space. The native
   stage logs are not appearing at all, which is the current investigation.
3. **iOS tree browser and auto-configuration** (§2.21) — compiled only. On device: browse an image
   repo, check a quant plus the `split_files` folder, then confirm the bundle row and the slots.
4. **Android MTP / speculative decoding** — blocked: the API lives in `common/speculative.h`
   which the Android CMake does not build.
5. **iOS LoRA / decision model** — not started.
6. **Release signing** — every APK is signed with a generated debug key; no release key exists
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

### 2.15 Three fixes off the round-two log: command buffers, tab switch, VAE tiling (2026-10-19)

The §2.14 build's captured log finally made the 10 s/step legible, and it named a second,
unrelated failure the user hit separately: switching between a chat and the image tab killed the
app outright. Both are fixed here, plus the tunables the log showed were missing.

**1. The 10 s/step is one Metal command buffer, not slow math.** `ggml_runner` reports the
diffusion graph as a single segment (`executing segment 1/1`) and the step time is ~10 s, which is
exactly the watchdog's "no progress for ~10 s" budget: the whole step is encoded into *one*
command buffer, so the GPU gets one 10 s unit of work the watchdog refuses to let finish. SD's
vendored ggml-Metal backend is built with `n_cb = 1` — the split that would break the step into
several shorter, independently-committed buffers is tuned for llama.cpp's many tiny per-token
graphs, not a diffusion graph.

The backend already supports up to `GGML_METAL_MAX_COMMAND_BUFFERS` (8) and splits a graph into
`n_cb + 1` buffers (`n_nodes` spread as `MAX(64, 10%)` on the calling thread, the rest across the
helper threads). The only thing wrong is the hard-coded `1`. Because `third_party/sd` is fetched
fresh on every build (it is not a submodule), the change ships as a patch, applied to the `ci-sd`
clone in the workflow rather than kept in the tree:

| change | reason |
|---|---|
| `ios-app/patches/stable-diffusion-metal-command-buffers.patch` | new file; carries the fix without tracking the fetched SD checkout |
| `ggml_backend_metal_preferred_n_cb()` in `ggml-metal.cpp` (`getenv("GGML_METAL_N_CB")`, default 4, clamp 0..8, `GGML_LOG_INFO`) | one place to read the split; logs the value so a device run confirms it took |
| both `ggml_backend_metal_set_n_cb(backend, 1)` → `…, preferred_n_cb())` | the two init paths; 4 buffers means each is ~¼ of the step, under the watchdog |
| workflow: `patch -p1 -d ci-sd …` then `grep -q ggml_backend_metal_preferred_n_cb` | `patch`, not `git apply`: the target is inside the fetched ggml submodule; the grep fails the build if the patch silently did not land |

`n_cb` is deliberately not raised to 8: more buffers means more encoder overhead per step, and 4
already divides a ~10 s step into units the watchdog tolerates with margin. `n_main` (the calling
thread's `MAX(64, 10%)` slice) is left alone — for a graph this size the 10 % term dominates, so
the main buffer is short without touching the floor.

**2. The tab-switch crash is a second resource the hand-off never covered.** `ComputeCoordinator`
was built in §2.13 to move the GPU between llama.cpp and SD, but MLX and the VLM hold Metal
directly, and neither registered. Loading one of them while SD still owned several GB of VRAM
jetsam-killed the app on the switch. The coordinator is now a general registry of *text* engines
(`.llama`, `.mlx`, `.vlm`) with a single *image* engine, and any load starts by tearing down the
other class:

| change | reason |
|---|---|
| `ComputeCoordinator`: `enum Kind { llama, mlx, vlm }`; `registerTextEngine`, `registerImageEngine`, `freeTextEngines`, `freeImageEngine` | any number of text engines, exactly one image engine — the real shape, not llama-vs-SD |
| `LocalEngine` / `MLXEngine` / `VLMEngine` register as text engines; each `load` calls `freeImageEngine()` | loading a chat/vision model frees SD first |
| `SdEngine` registers the image teardown; `load` calls `freeTextEngines()` | and the reverse |
| `MLXEngine.unload()` (new): drop the container, `GPU.clearCache()` | MLX had no unload at all; without it "freeing" MLX freed nothing |

`registerImageEngine` closes over `MainActor.assumeIsolated { unload() }`, so the teardown runs on
the actor that owns the engine without the coordinator having to know about actors.

**3. VAE tiling and a size menu.** The log also showed the decoded-resolution path had no knob:
1024×768 peaked at ~3.1 GB resident, and VAE decode of a large latent is a separate memory spike
after sampling. `SdEngine` gained `vaeTiling` / `vaeTileSize`, threaded through `SdBridge` into
`sd_img_gen_params_t.vae_tiling_params` (`enabled`, `tile_size_w/h`, `target_overlap 0.5`), the
documented setting (`--vae-tiling --vae-tile-size 256x256 --vae-tile-overlap 0.5`). `ImageGenView`
exposes the toggle, a tile-size picker (128…512), and a size dropdown (`sizePresets`) that writes
the width/height fields directly — a preset is matched back to them, never stored alongside, so
the menu can't drift from what the engine reads.

**Not verified:** the `n_cb` fix and the coordinator both need a device run. The patch is confirmed
to apply cleanly against a pristine `ggml-metal.cpp` (`patch -p1`), and CI guards that it landed,
but the step-time drop and the absence of the tab-switch kill are device facts. `GGML_METAL_N_CB`
is read once at backend init; the default of 4 is what the app will use since a sideloaded build
has no environment to set it.

---

### 2.16 All-in-one checkpoints, the full sampler table, and Metal read-at-init knobs (2026-10-19)

The next device log (a 1024×768 run of the §2.15 build) confirmed the command-buffer split took
effect (`ggml_backend_metal_preferred_n_cb: command buffers = 4`) and was enough for the watchdog:
the first two steps ran for ~31 s and ~38 s without the process being killed. It failed on step 3
with `ggml_metal_synchronize: error: command buffer 3 failed with status 5` /
`kIOGPUCommandBufferCallbackErrorInnocentVictim` — a Metal *error*, not the watchdog, arriving
~20 s into a step, so neither the split nor the earlier fixes explain it. The same log opened with
a second, unrelated failure the user reported separately: an all-in-one checkpoint refused to load.
(§2.17 corrects the status-5 reading below: the GPU event log shows it *was* the watchdog.)

**1. All-in-one checkpoints (single-file SD1.5/SDXL) never loaded.** The `sd load failed` line is
preceded by hundreds of `Conditioner model tensor 'cond_stage_model.transformer.text_model.…' not in
model metadata`, then `model metadata validation failed`. The app mapped its main-model slot to
`diffusion_model_path`, and `diffusion_model_path` is loaded with the prefix `model.diffusion_model.`
(`diffusion_engine.cpp:733`) — a prefix-*strip* that imports the UNet alone. An all-in-one
checkpoint keeps its text encoder under `cond_stage_model.*`/`conditioner.*`, so the conditioner
builds against a file that contributes no tensors to it and the load is rejected. Only `model_path`
is read without a prefix and sees every component.

| change | reason |
|---|---|
| `SdEngine`: route the main model to `model_path` when no separate text encoder, CLIP-L/G or T5 is supplied, else to `diffusion_model_path` | one slot, both layouts: an all-in-one file is the whole checkpoint, a DiT-only file (Anima/Qwen) still gets the prefix strip |
| new slots `clipL` / `clipG` / `t5` in `SdEngine` and `ImageGenView` | SDXL's two encoders and SD3/Flux's T5 were reachable in the bridge but had no UI; `clipG` was hard-coded `nil` |
| `sd: metal … fullCheckpoint=…` stage log | the routing decision is otherwise invisible; the log says which path a load took |

**2. The sampler and scheduler tables were not exposed at all.** The iOS UI had no sampler,
scheduler or clip-skip control — `SdEngine` carried the values but nothing bound them, so every run
was `euler` / `discrete` / clip-skip 1. Android already listed the engine's own tables
(`sample_method_to_str` / `scheduler_to_str`); iOS did not.

| change | reason |
|---|---|
| `SdBridge`: `+sampleMethodNames` / `+schedulerNames` over `sample_method_to_str` / `scheduler_to_str`, bounded by `SAMPLE_METHOD_COUNT` / `SCHEDULER_COUNT` | the menu offers exactly what the build supports — lcm, tcd, ddim_trailing, the cfg-pp and sde variants included — and the index is the enum value |
| `ImageGenView`: sampler + scheduler pickers, clip-skip stepper | binds the values to the run |

**3. The Metal backend knobs are read-at-init, so they are now settable before `new_sd_ctx`.**
`GGML_METAL_N_CB`, `GGML_METAL_CONCURRENCY_DISABLE` and `GGML_METAL_FUSION_DISABLE` are `getenv`
calls in the backend, so a user setting has to exist in the environment when the context is built —
and the `n_cb` value was otherwise only reachable by editing the build patch's default.

| change | reason |
|---|---|
| `SdBridge`: `+configureMetalCommandBuffers:fusion:concurrency:` (`setenv`/`unsetenv`) | applied from `SdEngine.load` before the context; unsetting restores the backend default instead of leaving a stale value |
| `ImageGenView`: a collapsed "Metal 進階" group — n_cb (1…8), fusion, concurrency | `n_cb` divides a long step into shorter command buffers; concurrency/fusion are the two suspects for the status-5 error, so both can be turned off |

**Not verified:** which knob clears the status-5 fault. The error is `InnocentVictim` — the GPU was
reset by a fault elsewhere and the buffer was discarded — and it lands ~20 s into step 3 after two
clean steps, which fits either a resource ceiling at 1024×768 or the backend's multi-command-buffer
path (the reason it warns against `n_cb > 2`). The knobs exist so the device run can distinguish
them: try `n_cb = 8`, then concurrency off, then fusion off. The `compute buffer … MB(VRAM)` line
that would show whether the DiT activations spill is `LOG_VERBOSE`, which the app writes to the
native file but the Logs export drops (the in-memory store mirrors INFO+ only), so it is not in any
shared log yet.

---

### 2.17 The watchdog after all: an even graph split (2026-10-19)

The GPU event log settled the §2.16 question and corrected it. `gpuEvent-unknown-…txt` is a
`bug_type 284` record whose `restart_reason_desc` is `"progress timeout"` — the OS GPU **watchdog**,
exactly the failure §2.15 was meant to fix. So the `status 5` /
`kIOGPUCommandBufferCallbackErrorInnocentVictim` that the app printed was not a separate Metal
fault: it is what an in-flight command buffer reports after the GPU firmware resets, and the reset
was the watchdog killing a step that had run past its ~10 s budget. The §2.16 note ("a Metal
*error*, not the watchdog") was wrong — the two are the same event seen from different sides.

The reason `n_cb = 4` was not enough is the shape of the split, not the count. `ggml_metal_graph_compute`
gave the calling thread `MAX(64, 0.1*n_nodes)` nodes and divided the *remainder* across the `n_cb`
helper buffers, so with `n_cb = 4` each helper buffer carried ~22.5 % of the graph against the main
thread's 10 % — every buffer but the main one was still a long pole, and a heavy node inside one of
them could outrun the budget on its own. Raising the count only ever shrank the helpers by
`90 %/n_cb`; it never balanced against the fixed 10 % main-thread chunk.

| change | reason |
|---|---|
| `ggml-metal-context.m`: `n_main = MAX(1, gf->n_nodes/(ctx->n_cb + 1))` (was `MAX(64, 0.1*gf->n_nodes)`) | split the graph *evenly* across the calling thread and the `n_cb` helpers, so no buffer is the long pole; with `n_cb = 0` it still collapses to the whole graph on the calling thread, as before |
| `ggml-metal-context.m`: one `n_nodes = … main thread … x … per command buffer` line per distinct graph shape | the per-buffer node count is the number that decides whether a step can outrun the watchdog, and it was invisible; it is now in the device log |
| `SdEngine.metalCommandBuffers` default `4 → 8` | the backend's max: 9 evenly sized buffers put ~11 % of a step in each, roughly a third of the §2.15 time even before the split was balanced |
| `ios.yml`: a second patch guard on `gf->n_nodes/(ctx->n_cb + 1)` | the guard only checked the `ggml-metal.cpp` half; the context change would have failed silently |

**Not verified:** that `n_cb = 8` with the even split clears the watchdog at 1024×768 on device —
this is a CI-compiled fix with the standard runtime gap. The per-shape split line is what will show
it: if a step still dies, the line names the buffer that did it, and whether a single heavy node is
the culprit rather than the aggregate node count.

---

### 2.18 The split cannot subdivide one op: the decode is the long pole (2026-10-19)

The round-four device runs answered it, and refuted the split as the fix for decode. On Anima at
1024×576 with `vaeTile 128`, sampling finishes in **134 s for 5 steps** and the VAE decode takes
**1034.70 s** — 17 minutes, 21 tiles of 128 px, ~8.6 s each. The whole generation is ~19.5 min. At
tile 256 (run *PocketLLM 4*) the decode instead dies at tile 4 with `status 5`; that is the same
watchdog (`bug_type 284`, `progress timeout`), just reached sooner because the per-tile graph is
bigger. The `command buffers = 8` line in both logs confirms the even split took effect, and the
`tensor API disabled for pre-M5 and pre-A19 devices` line explains the cost: with no Metal tensor
API every big convolution runs on the plain path.

The split theory was structurally wrong for this stage. `n_cb` divides a graph by **node**, and the
decode's cost is concentrated in a handful of very large convolutions; no node count can cut a
single 3D-conv op below the watchdog budget. So the two ends are both bad and no tile size escapes
them:

| tile | per-tile graph | outcome |
|---|---|---|
| 256 px | large | watchdog at tile 4 (`status 5`) |
| 128 px | small enough to survive | survives, but 120 tiles × ~8.6 s = 17 min |

Smaller tiles only survive because each op stays small; they pay roughly 3.3× the pixels for the 50 %
overlap plus per-tile overhead. That is the observed 17 min, not a tuning miss.

**The escape hatch, implemented this round:** decode the VAE on the **CPU**, moving only that module
with `vae=cpu` in the backend spec (`te=cpu` already keeps the conditioner on CPU; the diffusion model
stays on Metal). CPU decode cannot trip the GPU watchdog at all, and tiling stops mattering — the app
disables it when the toggle is on. Added as a first-class app option (**VAE 用 CPU 解碼**) beside the
tiling controls; it requires a model reload because the backend spec is read when the context is
created. Whether CPU decode is *fast* on this VAE (bf16 194 tensors, 242 MB, handled as F32 by
`WanVAE`) is exactly what the next device run measures; it is the guaranteed-safe floor, not a
guaranteed-fast one.

| change | reason |
|---|---|
| `SdBridge.h` / `.mm` `initWithModel:…vaeOnCPU:` | build `p.backend = "te=cpu"` + `",vae=cpu"`; the spec string now outlives `new_sd_ctx` as a local `std::string` |
| `SdEngine.vaeOnCPU` | thread the flag through, force `vaeTiling` off when set, and log `vae=cpu|metal` in the load line |
| `ImageGenView` | `VAE 用 CPU 解碼` toggle; the tiling toggle is disabled while it is on |
| `ImageGenView` custom tile size (was a 5-preset picker) | any tile edge is now typeable; the earlier caption had the cost backwards and is fixed (smaller tile = slower) |

MLX is the other candidate and was checked this round rather than assumed: `MLXNN` ships `Conv1d/2d/3d`,
`ConvTransposed1d/2d/3d`, `GroupNorm` and `Upsample`, and `MLX.loadArrays(url:)` reads `.safetensors`
directly at runtime, so a user-imported VAE needs no conversion. The catch is that MLX submits the same
Metal work: it is a *faster* decoder, not a watchdog-proof one, because it does not change the fact
that one large op runs long. It stays the plan for speed; `vae=cpu` is the plan for the crash.

---


### 2.19 The MLX VAE decoder, fed by a latent dump (2026-10-19)

The `vae=cpu` escape hatch is the crash fix; this round builds the *fast* decoder. The two cannons
(mm and Swift) are already in the app, and `mlx-swift` can read the user's `.safetensors` directly,
so the VAE can be decoded in MLX without converting anything. The obstacle was purely one of
hand-off: the latent never leaves `stable-diffusion.cpp`, which decodes it in the same call.

So the engine grew a dump hook. With `SD_LATENTS_DUMP` set, `decode_image_outputs` writes the final
latents to `<path>[.i]` (magic `PLLV`, version 1, `i32` ndim, dims fastest-first, `f32`) and
returns without decoding: `num_images_out = 0`, and the bridge accepts that only when the MLX path
is on (`free_sd_images(images, 0)`). That keeps the probability path — sampling, the DiT, the
schedule — in exactly the engine that already works; only the last stage moves.

`MLXVae.swift` is a decoder-only port of `wan_vae.hpp`, checked against both the C++ and diffusers
`autoencoder_kl_wan.py`: undo the latent stats, `conv2` (= `post_quant_conv`), `decoder.conv1`,
middle (`Residual`, single-head `Attention`, `Residual`), three `upsamples.N` stages of three
residuals plus a nearest-2× resample between them (8× total), then `head.0` RMS + SiLU and
`head.2` conv, clamped to [-1, 1]. Two details decide correctness:

| detail | why |
|---|---|
| NHWC throughout | `mlx-swift`'s `conv2d` is channels-last (`MLX/Ops.swift:614`); weights are transposed `OIHW → OHWI` and 5D `[O,I,kt,kh,kw]` takes the last temporal tap after `3`-wide causal geometry |
| one `eval` per stage | a whole-graph `eval` would submit the same giant Metal work that trips the watchdog; per-stage evaluation breaks it into the same-sized chunks the working 128 px tile path already survives |

Because the dumped latent is `T=1` and the causal pad contributes two zero frames, the last
temporal tap of every `kt=3` conv is exact — the stage is a 2D decoder with no approximation. The
latent constants (16-channel `mean`/`std`) live in `MLXVae.latentsMean/latentsStd`, copied from
`wan_vae.hpp:1127`.

Plumbing: `generatePrompt:` took `mlxVae:` (sets/unsets `SD_LATENTS_DUMP` around `generate_image`),
`SdEngine.vaeOnMLX` suppresses tiling, and the image view has an off-by-default
**VAE 用 MLX 解碼（實驗）** toggle mutually exclusive with the CPU one. The MLX path needs a
separate VAE file, so an all-in-one checkpoint logs a clear message instead of failing obscurely.

The first push failed to compile on exactly one line — `conv2d(…, padding: padding)` with an `Int`
variable; Swift's `IntOrPair` only converts integer *literals*. Wrapped as `.init(padding)`.
Run `39341146470` is green and produced the usual `PocketLLM-device-unsigned` artifact.

| change | reason |
|---|---|
| `ios-app/PocketLLM/MLXVae.swift` | decoder port, latent blob reader, NHWC ↔ `MLXArray` |
| `ios-app/patches/stable-diffusion-latents-dump.patch` | `SD_LATENTS_DUMP` hunk for `src/pipeline/image.cpp`; applied by CI with a `SD_LATENTS_DUMP` guard |
| `SdBridge.mm` | `mlxVae:` parameter, `+latentsDumpPath`, `-removeLatentsDump`, `num <= 0` accepted only on this path |
| `SdEngine.swift` | `vaeOnMLX`, decode branch, `bitmap(from: MLXArray)` (NHWC → `UIImage`) |
| `ImageGenView.swift` | MLX toggle, mutual exclusion, tiling disabled |
| `project.yml` | `MLXNN` product from the already-pinned `mlx-swift` |

**Not proven:** compile and link only. Whether MLX's Metal path is fast enough, whether the port
matches the engine bit-for-bit, and whether the tensor-key names in a real
`qwen_image_vae.safetensors` cover every lookup are all device-run questions. If the MLX path
produces a wrong image, the first suspect is the attention reshape (`[N,1,3C,HW]` split), the second
is `nearest2x`'s reshape order — both are covered by the `decode` comment.

---

### 2.20 The first MLX decode died on the upsample groups (2026-10-20)

The first device run trapped. It was not the watchdog and not the GPU: the crash report is
`bug_type 309`, `EXC_BREAKPOINT`, faulting thread on a Swift cooperative queue, with
`_assertionFailure` at the top and `MLX.errorHandlerTrampoline` → `MLX.ErrorHandler.dispatch` →
`_mlx_error` in between. That is MLX's default handler calling `fatalError` after an op failed.

The report is enough to name the exact op even without a message. `usedImages[2]` carries the app
UUID `af1ca2f0-…`, which matches the `PocketLLM-device-unsigned` artifact from run `39341146470`
byte for byte, so the symbols in that artifact apply:

```
0x12b86c  MLX.ErrorHandler.dispatch          (the fatalError)
0x12b6fc  MLX.errorHandlerTrampoline
0x1b7ea4  _mlx_error
0x1e911c  _mlx_eval
0x188638  MLX.eval(MLXArray)
0x4cfb8   MLXVae.decode + 0xad4              <- return address of the 4th `bl MLX.eval`
0x724c4   SdEngine.generate closure
```

`decode` has five `eval` calls; the fourth is the per-stage one. Disassembly of `decode` puts the
`conv`/`resample` call immediately before it, i.e. the failure is in the upsample stage loop, and
stage 0 had already evaluated. The port had modelled the decoder as **four** groups of three
residuals, one resample between groups. The engine has **five**:

| `dims` from `Decoder3d::Decoder3d` | indices |
|---|---|
| `[384, 384, 384, 192, 96, 96]` | `upsamples.0-2` R(384), `3` Resample(384) |
| | `4-6` R(192→384), `7` Resample(384) |
| | `8-10` R(192), `11` Resample(192) |
| | `12-14` R(96) |
| | `15-17` R(96) |

`dims` starts with `dim_mult.last * dim` *and then* repeats `dim * dim_mult.reversed()`, so the
last multiplier appears twice and the channel halving lands on the first residual of the next
group. The four-group version read `upsamples.4` (a 192→384 shortcut block) with a 384-channel
input, which is where the conv failed. The index mistake also explains why the error came from the
stage `eval` and not from a lookup: the keys existed, the shapes did not.

Fixed by reproducing the engine's `dims` construction and walking `dims.count - 1` groups. Three
hardening changes go with it:

| change | reason |
|---|---|
| `withError { … }` around the decode | an MLX error becomes a thrown `MLXError` and reaches the app log (`sd: mlx vae failed: …`) instead of `fatalError` |
| `eval` inside `conv`, not per stage | keeps each lazy graph (and each Metal submission) to one conv; MLX frees intermediates as they go |
| `MLX.GPU.set(cacheLimit: 64 MB)` before decoding | MLX's pool otherwise grows toward the working-set size next to the engine's 1.4 GB of VRAM; this is the documented iOS mitigation |

`compute` is f32: the widest live tensor is 512×288×384 (~226 MB), so bf16 buys nothing worth a
new kernel-coverage risk. The group log now prints resolution, channels and elapsed time per group,
and the decoded line prints `GPU.peakMemory`, so the next run has both a completion trace and a
speed number for every stage.

**Device result:** the next run decoded 512×512 in **~2 s**, image correct. That ends the decode
story: the 1035 s figure in §2.18 was the engine's tiled Metal decode at 1024×576, which is ~2.25×
the pixels of 512², so the same work should land around 4-5 s here. Nothing else in the pipeline is
within two orders of magnitude of the old decode.

MLX is now the default (`vaeOnMLX = true`). It needs a separate VAE file, so when the user loads an
all-in-one checkpoint the app creates the context with `vae=cpu` instead of leaving the Metal decode
armed; that configuration still decodes slowly, but safely. `vae=cpu` remains the manual fallback.

### 2.21 The model browser becomes a tree, and image models configure themselves (2026-10-20)

The browser listed one flat page of files, and the format picker was really a file-extension
filter. With image models arriving as repositories of three or four parts, that shape no longer
matches what a user downloads. Four changes, all in the iOS app:

| change | detail |
|---|---|
| **Image** category | `ModelStore.Kind.image`, searching `pipeline_tag=text-to-image` with no format filter; the vision toggle is hidden there because it would narrow an image search to nothing |
| tree browser | one request to `/api/models/<repo>/tree/main?recursive=true`, rebuilt into folders with `OutlineGroup`; a checkbox per folder (all its files) and per file, tri-state for partial |
| per-repository folders | downloads land in `Models/<repo>/` with the repository's own paths kept, so `split_files/text_encoders/…` stays recognisable |
| auto-configuration | when the last file of a repository finishes, `ModelInspector.bundle(in:repoId:)` matches the folder and the slots are set without another tap |

The recursive listing *does* return directory rows, but they carry no sizes and are not needed:
`HuggingFaceClient.tree` keeps only files and the view rebuilds the directories from the paths.
Verified against `vanes430/Anima-Turbo-V1.1-GGUF` — 14 rows, three directories and 11 files.

Matching is by name, against the same slots `SdEngine` takes. The first version had two bugs that
this repository exposed before any device run:

1. the text-encoder folder test looked for `text_encoder/`, but these repos spell it
   `text_encoders/`, so `qwen_3_06b_base.safetensors` was counted as a *main* candidate and the
   bundle never completed — the test is now on the prefix without the slash;
2. the largest weight was treated as the main model, which is right inside one folder but wrong
   as a *default selection*: a GGUF repo holds every quantisation, and preselecting them all would
   have queued 7.5 GB. The tree now starts with nothing checked and the user (or a folder
   checkbox) says what to fetch.

Moving every download under a repository folder broke the installed list, which had only ever
looked at the top level for `.gguf` files — a text model downloaded after this change would have
vanished from the chat picker. `refresh` now walks the tree, and skips files inside a recognised
bundle folder so an image quant is not offered as a chat model. A folder holding one lone weight
file is a text model unless it was downloaded under the Image category (the folder names are kept
in `models.imageRepos`); otherwise downloading any single-file GGUF would have auto-configured it
as an image model.

`ModelBundle` is complete when a main model is joined by a text encoder and a VAE, or when the
folder holds a single checkpoint and no companions — the all-in-one case `SdEngine` already
detects as `textEncoder.isEmpty && clipL.isEmpty && clipG.isEmpty && t5.isEmpty`. An incomplete
folder yields no bundle rather than one that cannot load; the files remain visible in the browser.
Video checkpoints are excluded through `SdEngine.isVideoCheckpoint`, so a Wan repository cannot
auto-configure an engine that refuses it.

The engine is owned by `ImageGenView` as a `@StateObject`, so `ModelStore` cannot write its slots
directly without creating a second instance. Instead the store persists `appliedBundleId` and
publishes it; `ImageGenView` mirrors that id into the six slots, remembering the id it last wrote
so hand-picked paths survive a tab switch. The mirror listens to both the id and the bundle list,
because the id is set before the refresh that publishes the bundle.

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
| iOS SD even graph split builds | run `39273582227`: patch + both guards (`ggml-metal.cpp`, `ggml-metal-context.m`) pass; artifact 29.4 MB |
| iOS SD decode is the long pole | device logs: sampling 5 steps 134 s vs decode 1035 s (1024×576, tile 128, 21 tiles); tile 256 dies at tile 4 `status 5` |
| iOS `vae=cpu` escape hatch builds | run `39291045148`: `stable-diffusion.cpp` (device) and the app both build with the `vaeOnCPU:` init and the backend spec; artifact 29.4 MB |
| iOS MLX VAE decoder builds | run `39341146470`: `MLXVae.swift` + `MLXNN` link, latent-dump patch + guard pass; artifact `PocketLLM-device-unsigned` |
| MLX VAE first device run | crash `bug_type 309`; symbolication: `_mlx_error` from the stage-loop `eval`, caused by the four-vs-five upsample groups |
| iOS MLX VAE decode on device | 512×512 in ~2 s, image correct (user device run, build `39452756882`) |
| iOS tree browser + auto-config builds | run `39465503482`: compile, link, ggml guard and artifact upload all green |
| iOS native SD log reaches the file | not yet run on device (this build); the format is asserted against `LogStore.loadFromDisk` |
| HF `/tree?recursive=true` shape | live request to `vanes430/Anima-Turbo-V1.1-GGUF`: 14 rows, 3 directories + 11 files, `split_files/{text_encoders,vae}` layout as expected |
| iOS simulator slice removed | workflow step list: 26 → 21 steps |
| iOS dylib packaging (reverted) | `Frameworks/` in the `.ipa`: 13 dylibs, `libggml.0.dylib` present |
| Converter correctness | round-trip through the official `gguf` reader |
| Android Kotlin compiles | `:app:compileReleaseKotlin` BUILD SUCCESSFUL |

**Not verified:** anything requiring a device — iOS image generation, iOS vision output,
Android vision output, and all iOS UI changes. Every iOS build in this log is
compile-and-link evidence only.

---

## 5. Build and release reference

### Release v0.6.0 (2026-10-20)

`v0.6.0` was moved from the unreleased 2026-10-06 snapshot (`50bbfdc`) to `f9a2d88`, and the
release was cut from run `39472414846`:

| item | value |
|---|---|
| tag | `v0.6.0` → `f9a2d88` |
| asset | `PocketLLM-v0.6.0-unsigned.ipa` (29 765 545 bytes, unsigned, arm64, iOS 17+) |
| sha256 | `2461d870acced3d0a864eccd199f94b48d0127d437690335e8f07fca06021253` |
| version metadata | `CFBundleShortVersionString 0.6.0`, `CFBundleVersion 2` — set in `project.yml`, because xcodegen regenerates the plist and falls back to `1.0/1` |
| Android | `versionCode 21`, `versionName 0.6.0`; the APK is built on the device and is not attached |


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
| `PocketLLM-device-unsigned` | run `39291045148` artifacts (29.4 MB) |
| `prerelease-20261006-1309` | `arm64-` and `x86_64-pocketllm-v0.6.0-beta.apk` |

Note: the `v0.6.0` **git tag** exists but its release was deleted when the split-ABI pair
replaced the single dual-ABI APK.

---

## 6. Next steps, in priority order

1. **iOS vision logging** — determine whether `describeImage` is reached at all, or whether
   the native writer fails. The probe is `bridge: loading mmproj`, emitted from a call already
   confirmed to succeed.
2. **iOS image generation under the GPU watchdog** — §2.13 (hand-off) and §2.14 (logging) are
   proven. §2.15 split the step into `n_cb = 4` command buffers and fixed the tab-switch kill;
   §2.16 added the on-device knobs (`n_cb`, concurrency, fusion), single-file SD1.5/SDXL loading and
   the full sampler table. §2.17 read the GPU event log and corrected §2.16: the step-3 status-5 was
   the watchdog, not a Metal fault, and it persisted because the split gave the calling thread
   `MAX(64, 10 %)` of the nodes instead of an even share. The fix splits the graph evenly across
   `n_cb + 1` buffers, logs the per-shape node counts, and defaults `n_cb` to 8 (9 buffers). The
   build is CI-green; the device run has not happened. Next: the per-shape split line in the log
   names the buffer that dies, if one still does — then concurrency off, then fusion off. If the step
   is still slow, the §2.14 log distinguishes staged weights (`params_backend` / `auto_fit=false`)
   from CPU fallbacks (kernel coverage). Last resort is to stop sampling on Metal.
   §2.18 then measured the decode directly: it is the long pole (1035 s vs 134 s sampling), and it
   cannot be made watchdog-safe by any tile size because `n_cb` divides a graph by node and the cost
   sits in single large convolutions. The shipped escape hatch is **VAE 用 CPU 解碼** (`vae=cpu`);
   next device run is 1024×576 with it on *and* off, to get the CPU decode time against the 17-minute
   GPU baseline. §2.19 built the `MLXVae` port, §2.20 fixed its group walk, and the device run
   decodes 512×512 in ~2 s, so MLX is now the default decoder. Remaining: a 1024×576 run to confirm
   the ~4-5 s extrapolation and that peak memory stays clear of jetsam at the largest canvas.
3. **iOS tree browser and auto-configuration** — compiled only. On device: browse an image repo,
   check a quant plus the `split_files` folder, confirm the progress rows, then check that the
   bundle row appears with 「已自動配置」 and that the image tab's slots follow it without
   opening the browser again.
4. **Android MTP / speculative decoding** — add `common/` (`common.cpp`, `sampling.cpp`,
   `speculative.cpp`) to the native build, or implement draft-and-verify directly against
   `libllama`.
5. **iOS LoRA and the decision model** — mirror `setLoras` in `LlamaBridge.mm`; the decision
   model has the same singleton constraint as Android.
6. **Release signing** — add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD` as secrets and replace the generated debug keystore in the workflow. Until
   then every CI prerelease has a different signature and cannot upgrade in place.
7. **`llama.cpp` upgrade** — a re-vendor, not a merge: replace the tree wholesale and re-apply
   the deltas (`llama_jni.cpp`, the CMake flags, `LLAMA_COMMIT`).

# Third-party notices

PocketLLM's own source is MIT-licensed (see `LICENSE`). This file records what
else ships inside the repository and inside a build, because a few of these
components are **not** MIT and one of them cannot be redistributed at all.

Before publishing an APK/IPA, re-read this list.

## Build-time dependencies (not committed)

| Component | Where | Licence | Notes |
|---|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | cloned into `app/src/main/cpp/llama.cpp` by the build | MIT | Must keep the copyright notice and MIT text in any distribution. |
| [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) | `third_party/sd`, pinned as a submodule | MIT | Local patches exist; MIT dispositions are unaffected. |
| ggml | submodule of the two above | MIT | |
| CMake / NDK toolchains | toolchain, not shipped | respective | |

## Bundled in the Android app

| Component | Where | Licence | Notes |
|---|---|---|---|
| sherpa-onnx | `app/libs/sherpa-onnx-1.13.6.aar` | Apache-2.0 | Include the Apache notice and `NOTICE` file if present in the AAR. |
| Live2D Cubism SDK (Java) | `app/libs/Live2DCubismCore.aar` | Live2D Proprietary Software Licence | **Non-redistributable as source.** Shipping the compiled AAR inside an app is what the Cubism Licence permits for an application; publishing the SDK files as a reusable library is not. |
| Live2D sample models (`Haru`, `Hiyori`, `Mao`, `Mark`, `Natori`, `Ren`, `Rice`, `Wanko`) | `app/src/main/assets/` | Live2D Free Material Licence / Sample Model Terms | Free to use inside an application, **not** free to redistribute as assets. This is why the app deliberately has no model importing (see `v0.4.0` release notes). |
| `Seed-san.vrm` | `app/src/main/assets/Seed-san.vrm` | VRM sample avatar terms | Verify terms before any redistribution. |
| Alpine Linux minirootfs | `app/src/main/assets/alpine-minirootfs.tar.gz` | Mixed (GPL-2.0, MIT, BSD, …) | The sandbox rootfs; Alpine's own licence set applies to what it contains. |
| PRoot, libtalloc, libandroid-shmem | `app/src/main/jniLibs/arm64-v8a/*.so` | GPL-2.0 (PRoot) / LGPL (talloc) / MIT (shmem) | **Prebuilt binaries with no source in-tree.** GPL-2.0 requires that you can supply the corresponding source to recipients. This is the strongest reason to treat a distributed build as "GPL obligations outstanding" until those sources are published. |
| Turnip / freedreno Vulkan driver | `third_party/turnip/libvulkan_freedreno.so` | MIT/Apache-2.0 (Mesa) | Prebuilt; Mesa's notices apply. |

## Vendored on iOS (TTS)

| Component | Where | Licence | Notes |
|---|---|---|---|
| Phonon TTS Swift sources (`Phonon.swift`, `PhononModels.swift`, `PhononPlayer.swift`) | `ios-app/PhononTTS/` | MIT OR Apache-2.0 | Vendored from `gradium-ai/xn-ptts` v0.6.0 and annotated `@available(iOS 18, *)` so one binary runs on iOS 17 and 18. Local modifications are listed at the head of each file. |
| `PhononCore.xcframework` (compiled Rust runtime, static) | Fetched into `ios-app/PhononCore/` by `fetch-phonon-core.sh` | MIT OR Apache-2.0 | **Not committed.** Pinned to the v0.6.0 release by the same SHA-256 the upstream `Package.swift` pins. |
| Phonon model weights (`phonon-31m-en-coreml`) | Downloaded at runtime by the app | **Apache-2.0** for 31M; **CC BY-NC 4.0** for all 40M models | Never bundled. The 40M weights are non-commercial only, which is why the app downloads rather than ships them and why the bundle is not in this repository. |

## Bundled or generated on iOS

| Component | Where | Licence | Notes |
|---|---|---|---|
| [mlx-swift-lm](https://github.com/ml-explore/mlx-swift-examples) and MLX | SwiftPM dependency | MIT | |
| stable-diffusion.cpp + ggml | `ios-app` build | MIT | |

## Runtime, downloaded by the user

| Component | Licence |
|---|---|
| GGUF / safetensors model weights | Whichever licence the publisher set. Llama-family weights often carry a community licence with use restrictions; some checkpoints are non-commercial. PocketLLM does not redistribute weights. |
| Piper voices, upscalers, LoRA adapters | Publisher's licence; check each before commercial use. |
| `cloudflared` (optional, `pocketllm serve --tunnel`) | Apache-2.0. Not bundled — the CLI executes whatever `cloudflared` is on `PATH`. |

## Obligations if you distribute a build

1. Ship `LICENSE` and this file.
2. Ship the MIT text for llama.cpp, stable-diffusion.cpp and MLX.
3. Ship the Apache-2.0 `NOTICE` for sherpa-onnx and cloudflared if bundled.
4. Do **not** republish the Live2D Cubism SDK or its sample models as a
   standalone asset pack.
5. Publish the PRoot source (or a written offer) — a binary-only APK containing
   `libproot.so` is a GPL-2.0 distribution.

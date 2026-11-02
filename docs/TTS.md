# On-device TTS (Phonon)

`PhononTTSEngine.swift` replaces the cloud speech call with
[Gradium Phonon](https://huggingface.co/gradium/phonon-31m-en), a 31M-parameter
flow-matching TTS that streams 24 kHz mono audio one 80 ms frame at a time with
the transformer on the Neural Engine.

## What is wired up

| Piece | State |
|---|---|
| `PhononTTSEngine` | Done — load, speak, stop, voice switch, download, latency metrics |
| Settings UI | Done — engine state, voice picker, sample/stop, measured first-audio time |
| Phonon runtime | Done — vendored sources + fetched `PhononCore` static library, **iOS 18+** |
| System-voice fallback | Done — `AVSpeechSynthesizer` on iOS 17, same UI |
| Chat auto-speak | Not wired — the engine is callable, but no reply speaks itself yet |

## Why it is vendored instead of a SwiftPM dependency

Upstream's `ptts` package declares `platforms: [.iOS(.v18), .macOS(.v15)]`,
because the model bundles are Core ML **mlprogram** and need the iOS 18 runtime.
SwiftPM refuses to resolve a package whose platform floor is above the app's, so
adding it to an iOS 17 app is a resolution error, not a runtime surprise.

Rather than drop iOS 17 — the app supports it today, and most of what it does has
nothing to do with speech — the three Swift sources are **vendored** into the app
target (`ios-app/PhononTTS/`) with every public type annotated
`@available(iOS 18.0, *)`. That compiles into one binary that runs on 17 and
lights the feature up on 18. Below 18 the compiler will not even let the engine be
called, which is the guarantee we want: the models cannot run there anyway.

The compiled runtime (`PhononCore`, a ~50 MB static Rust library) is **not
committed**. `ios-app/fetch-phonon-core.sh` downloads it, checks it against the
exact SHA-256 upstream's `Package.swift` pins for v0.6.0, and installs the iOS
slice into `ios-app/PhononCore/` (git-ignored). This matches how the repository
already treats llama.cpp and stable-diffusion.cpp: heavy native dependencies are
fetched and pinned, not stored in git.

Static linking is what makes the two-OS build safe: a static archive carries no
load-time version check, so nothing in it can fail to load on iOS 17, and no
symbol in it is reachable below iOS 18 because every entry point is behind the
availability check.

Verified on the CI-built binary (run `41418863241`): `LC_BUILD_VERSION minos` is
still **17.0**, and the 1,667 `ptts_*` symbols are linked in. The archive's
objects are individually stamped iOS 18, but the final link does not inherit
that, so the app installs on 17 and speaks on 18.

The one thing that cannot be proven from CI: that no Core ML symbol the archive
references unconditionally is iOS 18-only. The link resolves cleanly and the
frameworks used (CoreML, CoreVideo, Accelerate, AVFoundation) are all far older
than 17, but a launch on a real iOS 17 device is the check that would settle it —
it costs one install.

```bash
cd ios-app && ./fetch-phonon-core.sh   # once per checkout; CI runs it too
```

## The model bundle

The weights are **not** committed. Phonon is a separate download because:

- **Licensing.** Phonon 31m is Apache-2.0, but **Phonon 40m and 40m multilingual
  are CC BY-NC 4.0 — non-commercial only.** A build that bundles a 40M model
  cannot be sold, and the app cannot tell which one a user dropped in.
- **Size.** The bundle is roughly 60 MB, and Core ML keeps a compiled copy beside
  each `.mlpackage`, so an installed model is about twice the folder size.

Two delivery paths, both supported by `PhononModels`:

- **Ship it.** Download `phonon-31m-en-coreml.zip` from the
  [xn-ptts releases](https://github.com/gradium-ai/xn-ptts/releases), rename the
  folder to `Models`, and add it to the app target as a *folder reference*
  (blue folder). In Xcode 16 a synchronized folder compiles the models inside and
  copies files loose — set its Build Rules to *Apply Once to Folder*.
- **Download it.** `PhononTTSEngine.downloadModels(from:)` fetches the folder
  from any static host, verifying every file against `bundle.json` (size +
  SHA-256) and resuming an interrupted transfer.

## Latency

The target is **under 50 ms to first audio**. Upstream's published numbers on an
iPhone 16 Pro:

| | Realtime factor | First audio |
|---|---:|---:|
| Neural Engine, after a pause | 12x | 37 ms |
| Neural Engine, back to back | 14.5x | 16 ms |
| CPU | 10x | 55 ms |

Two things this implies, which the settings screen reports rather than hides:

- **CPU misses the target.** 55 ms is over 50. The ANE path is the one that
  meets it, which is why `Phonon.load` defaults to `computeUnit: .neuralEngine`.
- **A cold first call is slower than any of these.** The first load after an
  install compiles the models for the device (~10 s on an iPhone 16 Pro); later
  loads are ~0.6 s. `Phonon.needsCompiling(_:)` is checked before loading so the
  UI can say "compiling" instead of looking hung.

`PhononTTSEngine.lastFirstChunkMilliseconds` records the runtime's own
`SpeechStats.timeToFirstAudio` for the last utterance. That is the number to
trust on a given device — it is measured inside the generator, so it excludes
none of the model's own work.

## Why Core ML and not ONNX Runtime

The weights ship as safetensors/GGUF and the Python/browser/Android runtimes read
them directly, but the only Apple-silicon path upstream supports is the Core ML
export driven by its Rust core (`PhononCore.xcframework`, MIT OR Apache-2.0).
Going through ONNX Runtime would mean adding a second inference runtime to an app
that already links llama.cpp, stable-diffusion.cpp and MLX, and re-implementing
the flow-matching sampler and the Mimi decoder in Swift — which is precisely what
the runtime exists to do. Core ML also keeps the model off the GPU, which the
image and video engines need for themselves.

## Memory

~95 MB resident once loaded. The engine is owned by `PocketLLMApp` as a
`@StateObject` so it is created once; the model serialises its own calls, so a
second utterance queues behind the first rather than running concurrently.

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
| System-voice fallback | Done — `AVSpeechSynthesizer` on iOS 17 or with no model |
| Chat auto-speak | Not wired — the engine is callable, but no reply currently speaks itself |
| Phonon package | **Not enabled** — see below |

## The blocker: iOS 18

The `ptts` Swift package declares **iOS 18 / macOS 15**, and this app targets
**iOS 17**. SwiftPM refuses to link a package whose minimum platform is above the
app's, so the dependency cannot simply be added; the build fails at resolution
rather than at runtime.

Three ways forward, in order of how much they cost:

1. **Raise the deployment target to iOS 18.** Cleanest, and matches what
   upstream supports. It drops iOS 17 devices. `deploymentTarget.iOS: "18.0"`
   in `ios-app/project.yml`, then uncomment the `ptts` package there.
2. **Keep iOS 17 and ship the system voice there.** What the code does today:
   `#if canImport(PhononTTS)` compiles the fallback when the package is absent,
   and the same source lights up the real engine on an iOS 18 build.
3. **Vendor the runtime as a binary target.** A separate iOS 18 slice would still
   need runtime gating and two build paths; not worth it for one feature.

## Enabling it

```yaml
# ios-app/project.yml
options:
  deploymentTarget:
    iOS: "18.0"
packages:
  ptts:
    url: https://github.com/gradium-ai/xn-ptts.git
    from: 0.6.0
```

Then add the library product `ptts` (module `PhononTTS`) to the app target.

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

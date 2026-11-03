import AVFoundation
import Foundation

/// On-device text to speech through Gradium's Phonon.
///
/// Phonon is a 31M-parameter flow-matching TTS that streams 24 kHz mono audio one
/// 80 ms frame at a time, with the transformer on the Neural Engine. Speech stops
/// being a cloud round trip — nothing leaves the device — and the first chunk
/// arrives while the sentence is still being generated instead of after it.
///
/// ## Why Core ML and not ONNX Runtime
///
/// The weights ship as safetensors/GGUF, but the only Apple-silicon path the
/// upstream project supports is its Core ML export driven by a Rust core
/// (`PhononCore.xcframework`, MIT OR Apache-2.0). Running it through ONNX
/// Runtime instead would mean adding a second inference runtime to an app that
/// already links llama.cpp, stable-diffusion.cpp and MLX, and re-implementing
/// the flow-matching sampler and the Mimi decoder in Swift — which is exactly
/// what that runtime exists to provide. Core ML also puts the model on the ANE,
/// keeping it off the GPU that the image and video engines need.
///
/// ## Latency
///
/// Upstream's own measurements on an iPhone 16 Pro: 12x real time, first audio
/// at 37 ms on the ANE (16 ms back to back, 55 ms on CPU). A published benchmark
/// is not a measurement of the phone in hand, so ``lastFirstChunkMilliseconds``
/// records what this device actually did — taken from the runtime's own
/// `SpeechStats.timeToFirstAudio`, not from a stopwatch around the call.
///
/// ## Availability
///
/// Phonon needs iOS 18 and Xcode 16; this app targets iOS 17. The package is
/// therefore optional: without it this file compiles to a system-voice fallback
/// and ``state`` reports `.systemFallback`.
@MainActor
final class PhononTTSEngine: ObservableObject {

    /// Where the engine is in its lifecycle. The UI shows this directly, because
    /// a first load compiles the Core ML models for the device and takes about
    /// ten seconds: silence with no explanation reads as a hang.
    enum State: Equatable {
        /// iOS 17, or the package is not in this build: speech uses the system voice.
        case systemFallback
        /// Supported, but no model folder has been installed or downloaded yet.
        case notInstalled
        case downloading(fraction: Double)
        case loading
        case ready
        case failed(String)
    }

    @Published private(set) var state: State = .systemFallback
    @Published private(set) var voices: [String] = []
    @Published private(set) var selectedVoice: String?

    /// Milliseconds from `speak` to the first PCM chunk, for the last utterance.
    /// The requirement this engine exists to meet is under 50 ms, and the only
    /// honest way to know is to measure it on the device.
    @Published private(set) var lastFirstChunkMilliseconds: Double?

    /// Realtime factor of the last utterance, from the runtime's own stats.
    @Published private(set) var lastRealtimeFactor: Double?

    /// The loaded model and its player.
    ///
    /// Held as `AnyObject` because a stored property cannot have an
    /// `@available(iOS 18)` type in a class that is also compiled for iOS 17 —
    /// the property would be unreachable but still ill-typed. The concrete holder
    /// is itself annotated, so every read is a cast inside an availability check
    /// and the compiler keeps the boundary honest.
    @available(iOS 18.0, *)
    private final class Backend {
        let phonon: Phonon
        let player: PhononPlayer
        init(phonon: Phonon, player: PhononPlayer) {
            self.phonon = phonon
            self.player = player
        }
    }
    private var backend: AnyObject?

    /// Fallback voice, used on iOS 17 or when no model is installed.
    private var synthesizer: AVSpeechSynthesizer?

    /// The playback task is kept so `stop()` can cancel generation and not only
    /// the audio already queued: `PhononPlayer.stop()` clears scheduled buffers
    /// but leaves the producer running.
    private var playback: Task<Void, Never>?

    nonisolated static var isSupported: Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    /// Where a downloaded bundle lives, or nil when the package is absent.
    nonisolated static var installDirectory: URL? {
        if #available(iOS 18.0, *) { return PhononModels.defaultDirectory }
        return nil
    }

    // MARK: - Model installation

    /// Installs the model folder and loads the engine.
    ///
    /// Prefers an already-installed bundle in Application Support, then a folder
    /// the app ships as `Models`. The bundle is deliberately not committed: Phonon
    /// 31m is Apache-2.0, but the 40M models are CC BY-NC 4.0 and must not ship in
    /// a paid build, and either way it is tens of megabytes of derived weights
    /// that belong in a release asset rather than in git.
    func prepare() async {
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        guard #available(iOS 18.0, *) else {
            // Say so once, with the version, instead of leaving the UI on
            // "system voice" and letting it look like the feature is missing.
            state = .systemFallback
            appLog("tts: Phonon needs iOS 18; this device reports \(os) — using the system voice", source: "tts")
            return
        }
        appLog("tts: \(os) supports Phonon; looking for a model bundle", source: "tts")

        // A bundle installed on a previous launch wins: it is already compiled.
        if PhononModels.isInstalled() {
            appLog("tts: using the installed bundle at \(PhononModels.defaultDirectory.path)", source: "tts")
            await load(at: PhononModels.defaultDirectory)
            return
        }

        guard let found = Self.findBundledModelDirectory() else {
            state = .notInstalled
            appLog("tts: no model bundle found. Add a folder containing bundle.json to the app as "
                   + "\"Models\", or download one. Looked in the app bundle and Documents/Models.", source: "tts")
            return
        }
        appLog("tts: found a model folder at \(found.path)", source: "tts")
        state = .loading
        do {
            let directory = try PhononModels.install(bundled: found)
            await load(at: directory)
        } catch {
            state = .failed(error.localizedDescription)
            appLog("tts: phonon install failed — \(error.localizedDescription)", source: "tts")
        }
    }

    /// Finds a model folder wherever it was actually put.
    ///
    /// Upstream ships the bundle as `phonon-31m-en/` and asks you to rename it to
    /// `Models`. Requiring exactly that name means a correctly downloaded folder
    /// that kept its original name is invisible, which reads as "the model is not
    /// there". So any folder containing a `bundle.json` is accepted: the app
    /// bundle first (a folder reference), then Documents/Models, which is where
    /// a user can drop one through the Files app without rebuilding.
    nonisolated static func findBundledModelDirectory() -> URL? {
        var candidates: [URL] = []
        for name in ["Models", "phonon-31m-en", "phonon-31m-en-coreml"] {
            if let url = Bundle.main.url(forResource: name, withExtension: nil) {
                candidates.append(url)
            }
        }
        // Anything the user dropped into Documents/Models (or Documents itself).
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            candidates.append(docs)
            let models = docs.appendingPathComponent("Models", isDirectory: true)
            candidates.append(models)
            if let inner = try? FileManager.default.contentsOfDirectory(
                at: models, includingPropertiesForKeys: [.isDirectoryKey]) {
                candidates.append(contentsOf: inner)
            }
        }
        return candidates.first { url in
            FileManager.default.fileExists(atPath: url.appendingPathComponent("bundle.json").path)
        }
    }

    /// Downloads the model folder on first run instead of bundling it.
    ///
    /// `base` must serve the exported folder's `bundle.json` and its files. Each
    /// file is verified against its size and SHA-256 and an interrupted download
    /// resumes, so a flaky connection costs time rather than the whole fetch.
    func downloadModels(from base: URL) async {
        if #available(iOS 18.0, *) {
            state = .downloading(fraction: 0)
            do {
                let directory = try await PhononModels.download(from: base) { [weak self] fraction in
                    Task { @MainActor in self?.state = .downloading(fraction: fraction) }
                }
                state = .loading
                await load(at: directory)
            } catch {
                state = .failed(error.localizedDescription)
                appLog("tts: phonon download failed — \(error.localizedDescription)", source: "tts")
            }
            return
        }
        state = .systemFallback
    }

    @available(iOS 18.0, *)
    private func load(at directory: URL) async {
        state = .loading
        let started = CFAbsoluteTimeGetCurrent()
        do {
            // The first load after an install compiles the models for this
            // device (~10 s on an iPhone 16 Pro); say so rather than look hung.
            if Phonon.needsCompiling(directory) {
                appLog("tts: compiling Core ML models for this device (first run only)", source: "tts")
            }
            let engine = try await Phonon.load(models: directory, language: .english)
            let audioPlayer = try PhononPlayer()
            backend = Backend(phonon: engine, player: audioPlayer)
            voices = engine.voices
            selectedVoice = engine.voice
            state = .ready
            appLog(String(format: "tts: phonon ready in %.2fs · %d voices · %.0f Hz",
                          CFAbsoluteTimeGetCurrent() - started,
                          engine.voices.count, Phonon.sampleRate), source: "tts")
        } catch {
            state = .failed(error.localizedDescription)
            appLog("tts: phonon load failed — \(error.localizedDescription)", source: "tts")
        }
    }

    // MARK: - Speaking

    /// Speaks `text`, streaming each 80 ms chunk to the speaker as it is produced.
    func speak(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        stop()

        if #available(iOS 18.0, *), let backend = backend as? Backend {
            let engine = backend.phonon
            let player = backend.player
            playback = Task { [weak self] in
                do {
                    // `speak` delivers each chunk on a background thread and
                    // `enqueue` is safe from any thread. The runtime reports
                    // time-to-first-audio itself, which is the number worth
                    // trusting: it is measured inside the generator, not around
                    // the call, so it excludes none of the model's own work.
                    let stats = try await engine.speak(trimmed) { chunk in
                        player.enqueue(chunk)
                    }
                    let first = stats.timeToFirstAudio
                    let firstMs = Double(first.components.seconds) * 1000
                        + Double(first.components.attoseconds) / 1e15
                    await MainActor.run {
                        self?.lastFirstChunkMilliseconds = firstMs
                        self?.lastRealtimeFactor = stats.realtimeFactor
                        appLog(String(format: "tts: first audio %.0f ms · %.1fx realtime · %.1fs audio",
                                      firstMs, stats.realtimeFactor, stats.audioSeconds), source: "tts")
                    }
                } catch is CancellationError {
                    // Stopped by the user; not a failure.
                } catch {
                    await MainActor.run {
                        appLog("tts: speak failed — \(error.localizedDescription)", source: "tts")
                    }
                }
            }
            return
        }

        // iOS 17, or no model installed: the system voice keeps the feature
        // working, just without the local model's quality.
        let synth = synthesizer ?? AVSpeechSynthesizer()
        synthesizer = synth
        let utterance = AVSpeechUtterance(string: trimmed)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        synth.speak(utterance)
    }

    /// Stops generation and clears audio already scheduled.
    ///
    /// Both halves are needed: cancelling the task stops the model at the next
    /// chunk, and `stop()` clears what is already queued — cancelling alone
    /// leaves up to a second of speech playing.
    func stop() {
        playback?.cancel()
        playback = nil
        if #available(iOS 18.0, *) {
            (backend as? Backend)?.player.stop()
        }
        synthesizer?.stopSpeaking(at: .immediate)
    }

    /// Switches voice. The runtime re-conditions the model (~0.6 s), so this
    /// applies to the next utterance rather than mid-sentence.
    func setVoice(_ name: String) {
        if #available(iOS 18.0, *), let engine = (backend as? Backend)?.phonon {
            Task {
                do {
                    try await engine.setVoice(name)
                    await MainActor.run { self.selectedVoice = name }
                } catch {
                    await MainActor.run {
                        appLog("tts: voice '\(name)' rejected — \(error.localizedDescription)", source: "tts")
                    }
                }
            }
            return
        }
        selectedVoice = name
    }
}

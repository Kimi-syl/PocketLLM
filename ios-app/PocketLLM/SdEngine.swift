import Foundation
import SwiftUI

// The bridge context is an opaque C++ handle. It is never used by two
// tasks at once, so conforming it lets loading and generation run off the
// main actor without the compiler rejecting the capture.
extension SdContext: @unchecked Sendable {}

/// On-device image generation via stable-diffusion.cpp.
///
/// Deliberately the same engine and the same model layout as Android: a text
/// encoder, a main (diffusion) model, a VAE and optional LoRAs, all GGUF. That
/// means one model set serves both platforms, and the four slots map onto
/// `sd_ctx_params_t` identically on each.
@MainActor
final class SdEngine: ObservableObject {

    enum Phase: Equatable {
        case idle
        case loading
        case running(step: Int, steps: Int)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var images: [UIImage] = []

    /// The four slots. Empty string means "not supplied".
    @Published var textEncoder = ""   // llm_path (Qwen2.5-VL for Qwen-Image)
    @Published var mainModel   = ""   // diffusion_model_path
    @Published var vae         = ""   // vae_path
    @Published var clipL       = ""   // clip_l_path (SD1.5/SDXL encoders)
    @Published var t5          = ""   // t5xxl_path
    @Published var loras       = ""   // "path|multiplier;path|multiplier"

    @Published var prompt = ""
    @Published var negative = ""
    @Published var width = 512
    @Published var height = 512
    @Published var steps = 20
    @Published var cfg: Float = 7.0
    @Published var seed: Int64 = -1
    @Published var batch = 1
    @Published var sampleMethod = 0
    @Published var scheduler = 0
    @Published var clipSkip = 1

    private var context: SdContext?
    private var pollTask: Task<Void, Never>?
    private var lastLoggedStep = -1

    init() {
        // The LLM frees us before it builds its own Metal context (see
        // ComputeCoordinator). The coordinator calls this on the main thread,
        // which is why assumeIsolated is safe here.
        ComputeCoordinator.shared.registerSDTeardown { [weak self] in
            MainActor.assumeIsolated {
                self?.unload()
            }
        }
    }

    var isReady: Bool { context != nil }

    // MARK: - Loading

    /// True for video checkpoints (WAN, LTXV, AnimateDiff, LingBot), matched on
    /// whole name tokens so `wan` does not match inside another word.
    ///
    /// They share the .gguf extension with image models, so nothing downstream
    /// can tell them apart before load -- and by then several GB of weights are
    /// being mapped. Refusing here keeps a phone from being asked to hold many
    /// frames at once.
    nonisolated static func isVideoCheckpoint(_ name: String) -> Bool {
        let markers: Set<String> = ["wan", "wan21", "wan22", "ltxv", "animatediff", "lingbot"]
        let tokens = name.lowercased()
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .map(String.init)
        return tokens.contains { markers.contains($0) }
    }

    func load() {
        if let bad = [mainModel, textEncoder, vae, clipL, t5]
            .first(where: { !$0.isEmpty && Self.isVideoCheckpoint($0) }) {
            phase = .failed("這是影片模型（\(bad)）。手機記憶體放不下多幀影片，請改用圖片模型。")
            appLog("sd: refused video checkpoint \(bad)", source: "sd")
            return
        }
        guard !mainModel.isEmpty || !textEncoder.isEmpty else {
            phase = .failed("先選擇主要模型（或文字編碼器）")
            return
        }
        phase = .loading
        appLogMemory("sd pre-load", source: "sd")
        // Free the LLM and its ggml-Metal context before sdcpp builds its own.
        // Two live Metal backends abort llama at context creation, so the
        // hand-off is a hard requirement, not an optimisation.
        ComputeCoordinator.shared.freeLLM()

        // Weight loading reads gigabytes. On the main actor that alone is enough
        // to trip the watchdog, so the whole call happens off-main.
        let model = mainModel, encoder = textEncoder, t5v = t5, vaePath = vae, clipLPath = clipL
        let threadCount = Int32(max(2, ProcessInfo.processInfo.activeProcessorCount - 1))
        // Opened before the context is created, not after: new_sd_ctx is where a
        // Metal load aborts, so a log file opened afterwards would miss the only
        // line that says why.
        let logPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pocketllm.log").path

        Task.detached(priority: .userInitiated) { [weak self] in
            SdContext.setLogPath(logPath)
            let ctx: SdContext
            do {
                // ObjC methods whose last parameter is NSError** import into
                // Swift as throwing, so there is no `error:` argument to pass.
                ctx = try SdContext(
                    model: nil,
                    clipL: clipLPath.isEmpty ? nil : clipLPath,
                    clipG: nil,
                    clipVision: nil,
                    t5: t5v.isEmpty ? nil : t5v,
                    llm: encoder.isEmpty ? nil : encoder,
                    diffusion: model.isEmpty ? nil : model,
                    vae: vaePath.isEmpty ? nil : vaePath,
                    taesd: nil,
                    threads: threadCount)
            } catch {
                await MainActor.run {
                    self?.phase = .failed(error.localizedDescription)
                    appLog("sd load failed: \(error.localizedDescription)", source: "sd")
                }
                return
            }
            await MainActor.run {
                guard let self else { return }
                self.context = ctx
                self.phase = .idle
                appLog("sd loaded · \(URL(fileURLWithPath: model).lastPathComponent)", source: "sd")
            }
        }
    }

    func unload() {
        if context != nil {
            appLog("sd unloaded so the LLM can take the GPU", source: "sd")
        }
        context = nil
        phase = .idle
    }

    // MARK: - Generation

    func generate() {
        guard let ctx = context else {
            phase = .failed("尚未載入模型")
            return
        }
        phase = .running(step: 0, steps: steps)
        appLogMemory("sd pre-generate", source: "sd")

        // Progress is polled rather than pushed: the sampler runs off the main
        // thread and must not re-enter Swift from C++.
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(300))
                guard let self, let c = self.context else { return }
                let step = Int(c.progressStep)
                let total = Int(c.progressSteps)
                if total > 0 {
                    self.phase = .running(step: step, steps: total)
                    if step != self.lastLoggedStep {
                        self.lastLoggedStep = step
                        appLog(String(format: "sd: step %d/%d · %@",
                                      step, total, LogStore.memoryDescription()), source: "sd")
                    }
                }
            }
        }

        // A 20-step run takes minutes. Blocking the main actor for that long is
        // what the watchdog kills, so the call and the pixel conversion both
        // happen off-main and only the finished images are handed back.
        let promptText = prompt
        let negativeText = negative
        let (w, h) = (width, height)
        let (n, c) = (steps, cfg)
        let (s0, b) = (seed, batch)
        let (sm, sc, cs2) = (sampleMethod, scheduler, clipSkip)
        let loraSpec = loras

        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            // Stage logs: a native crash produces no line of its own, so the last
            // line before death is the only evidence of where it died.
            appLog(String(format: "sd: entering generatePrompt (%dx%d, %d steps, cfg %.1f, batch %d) · %@",
                          w, h, n, c, b, LogStore.memoryDescription()), source: "sd")
            let result: [SdImage]?
            do {
                result = try ctx.generatePrompt(
                    promptText, negative: negativeText.isEmpty ? nil : negativeText,
                    width: Int32(w), height: Int32(h), steps: Int32(n),
                    cfg: c, distilledGuidance: 0, seed: s0, batch: Int32(b),
                    sampleMethod: Int32(sm), scheduler: Int32(sc),
                    clipSkip: Int32(cs2),
                    loras: loraSpec.isEmpty ? nil : loraSpec)
            } catch {
                await MainActor.run {
                    guard let self else { return }
                    self.pollTask?.cancel()
                    self.pollTask = nil
                    self.phase = .failed(error.localizedDescription)
                    appLog("sd generate failed: \(error.localizedDescription)", source: "sd")
                }
                return
            }

            // pollTask lives on the main actor; it is cancelled in the
            // MainActor.run block below, not from this detached context.
            appLog(String(format: "sd: generatePrompt returned %d image(s) in %.1fs · %@",
                          (result ?? []).count, Date().timeIntervalSince(started),
                          LogStore.memoryDescription()), source: "sd")

            var decoded: [UIImage] = []
            for (i, img) in (result ?? []).enumerated() {
                appLog("sd: decoding image \(i) \(img.width)x\(img.height)x\(img.channel)", source: "sd")
                if let ui = SdEngine.bitmap(from: img) { decoded.append(ui) }
            }
            let secs = Date().timeIntervalSince(started)

            await MainActor.run {
                guard let self else { return }
                self.pollTask?.cancel()
                self.pollTask = nil
                if decoded.isEmpty {
                    self.phase = .failed("未產生影像")
                    appLog("sd generate failed: no output", source: "sd")
                } else {
                    self.images = decoded
                    self.phase = .idle
                    appLog(String(format: "sd generated %d image(s) · %.1fs · %@",
                                  decoded.count, secs, LogStore.memoryDescription()), source: "sd")
                }
            }
        }
    }

    /// sd_image_t carries interleaved RGB(A) bytes; UIImage wants a CGImage.
    /// nonisolated so the detached generation task can convert off-main.
    nonisolated static func bitmap(from img: SdImage) -> UIImage? {
        let w = Int(img.width), h = Int(img.height), c = Int(img.channel)
        guard w > 0, h > 0, c >= 3 else { return nil }
        let bytes = [UInt8](img.pixels as Data)
        guard bytes.count >= w * h * c else { return nil }

        var rgba = [UInt8](repeating: 255, count: w * h * 4)
        for i in 0..<(w * h) {
            let s = i * c, d = i * 4
            rgba[d]     = bytes[s]
            rgba[d + 1] = bytes[s + 1]
            rgba[d + 2] = bytes[s + 2]
            rgba[d + 3] = c == 4 ? bytes[s + 3] : 255
        }
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cg = CGImage(width: w, height: h, bitsPerComponent: 8,
                               bitsPerPixel: 32, bytesPerRow: w * 4,
                               space: cs,
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                               provider: provider, decode: nil,
                               shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }
        return UIImage(cgImage: cg)
    }

    func cancel() {
        context?.cancel()
        pollTask?.cancel()
        pollTask = nil
        phase = .idle
    }
}

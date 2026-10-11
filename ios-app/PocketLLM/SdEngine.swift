import Foundation
import SwiftUI

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
    @Published var textEncoder = ""   // llm_path  (Qwen2.5-VL for Qwen-Image)
    @Published var mainModel   = ""   // diffusion_model_path
    @Published var vae         = ""   // vae_path
    @Published var clipL       = ""   // clip_l_path  (SD1.5/SDXL text encoders)
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

    var isReady: Bool { context != nil }

    // MARK: - Loading

    func load() {
        guard !mainModel.isEmpty || !textEncoder.isEmpty else {
            phase = .failed("先選擇主要模型（或文字編碼器）")
            return
        }
        phase = .loading
        appLogMemory("sd pre-load", source: "sd")

        var err: NSError?
        let ctx = SdContext(
            model: nil,
            clipL: clipL.isEmpty ? nil : clipL,
            clipG: nil,
            clipVision: nil,
            t5: t5.isEmpty ? nil : t5,
            llm: textEncoder.isEmpty ? nil : textEncoder,
            diffusion: mainModel.isEmpty ? nil : mainModel,
            vae: vae.isEmpty ? nil : vae,
            taesd: nil,
            threads: Int32(max(2, ProcessInfo.processInfo.activeProcessorCount - 1)),
            error: &err)

        guard let ctx else {
            phase = .failed(err?.localizedDescription ?? "載入失敗")
            appLog("sd load failed: \(err?.localizedDescription ?? "unknown")", source: "sd")
            return
        }
        context = ctx
        phase = .idle
        appLog("sd loaded · \(URL(fileURLWithPath: mainModel).lastPathComponent)", source: "sd")
    }

    func unload() {
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
                }
            }
        }

        let start = Date()
        var err: NSError?
        let result = ctx.generatePrompt(
            prompt, negative: negative.isEmpty ? nil : negative,
            width: Int32(width), height: Int32(height), steps: Int32(steps),
            cfg: cfg, distilledGuidance: 0, seed: seed, batch: Int32(batch),
            sampleMethod: Int32(sampleMethod), scheduler: Int32(scheduler),
            clipSkip: Int32(clipSkip),
            loras: loras.isEmpty ? nil : loras,
            error: &err)

        pollTask?.cancel()
        pollTask = nil

        guard let result, !result.isEmpty else {
            phase = .failed(err?.localizedDescription ?? "未產生影像")
            appLog("sd generate failed: \(err?.localizedDescription ?? "unknown")", source: "sd")
            return
        }

        var decoded: [UIImage] = []
        for img in result {
            if let ui = Self.bitmap(from: img) { decoded.append(ui) }
        }
        images = decoded
        phase = .idle
        let secs = Date().timeIntervalSince(start)
        appLog(String(format: "sd generated %d image(s) · %.1fs · %@",
                      decoded.count, secs, LogStore.memoryDescription()), source: "sd")
    }

    /// sd_image_t carries interleaved RGB(A) bytes; UIImage wants a CGImage.
    private static func bitmap(from img: SdImage) -> UIImage? {
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

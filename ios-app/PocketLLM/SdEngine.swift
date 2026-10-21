import Foundation
import MLX
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

    /// The model slots. Empty string means "not supplied". `mainModel` is also
    /// the slot for a single-file all-in-one checkpoint (SD1.5/SDXL downloads):
    /// when no separate encoder is given, it is loaded as model_path and the
    /// engine reads the encoders and VAE out of it.
    @Published var textEncoder = ""   // llm_path (Qwen2.5-VL for Qwen-Image)
    @Published var mainModel   = ""   // diffusion_model_path, or model_path for a full checkpoint
    /// Persisted: an all-in-one checkpoint has no VAE inside it that the MLX
    /// decoder can read, so the separate VAE the user attaches has to survive a
    /// relaunch or it would be re-imported every session.
    @Published var vae = SdEngine.restore("sd.vae") {   // vae_path
        didSet { SdEngine.store(vae, "sd.vae", oldValue) }
    }
    @Published var clipL       = ""   // clip_l_path (SD1.5/SDXL encoders)
    @Published var clipG       = ""   // clip_g_path (SDXL's OpenCLIP-G encoder)
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

    /// Decode the VAE in overlapping spatial tiles instead of all at once. Cuts
    /// the VAE's peak compute buffer, which is what makes large canvases fit —
    /// the trade is seams if the overlap is too small and a little extra time.
    @Published var vaeTiling = false
    /// Tile edge in image pixels. The engine rounds it down to a multiple of the
    /// VAE scale factor, so any of these is safe for every checkpoint.
    @Published var vaeTileSize = 256
    /// Decode the VAE on the CPU instead of Metal. The decode graph is one long
    /// chain whose length grows with the canvas, so on a big image it is the
    /// component that trips the iOS GPU watchdog no matter how the graph is
    /// split. CPU decode cannot time out the GPU at all; tiling stops mattering.
    @Published var vaeOnCPU = false
    /// Decode the VAE in MLX instead of the engine. The engine skips its decode
    /// and writes the final latents (`SD_LATENTS_DUMP`); MLX loads the same
    /// safetensors the user imported and runs the decoder stage by stage, so
    /// every Metal submission stays short. This is the faster Metal path; it
    /// still runs on the GPU, so `vaeOnCPU` remains the watchdog-proof floor.
    @Published var vaeOnMLX = true

    /// ESRGAN weights for the standalone upscaler. Independent of the diffusion
    /// context, so it can be picked and used without reloading the model.
    ///
    /// Defaults to the bundled Real-ESRGAN model, so 放大 works on a fresh
    /// install; the key is still written once the user picks something else.
    @Published var upscaler = SdEngine.restoreUpscaler() {
        didSet { SdEngine.store(upscaler, "sd.upscaler", oldValue) }
    }
    /// Final enlargement factor. The model's own factor is 4; a smaller choice
    /// resamples the 4x result down, which keeps the synthesised detail.
    @Published var upscaleScale = SdEngine.restoreScale() {
        didSet {
            guard upscaleScale != oldValue else { return }
            UserDefaults.standard.set(upscaleScale, forKey: "sd.upscaleScale")
        }
    }
    /// Run the ESRGAN graph on Metal rather than on the CPU. The graph is tiled
    /// at `upscaleTileSize`, which is what keeps a 4x pass under the watchdog.
    @Published var upscaleOnGPU = true
    /// Tile edge in source pixels for the upscaler. Smaller tiles bound the
    /// graph; ESRGAN has no overlap problem, so this can be as small as fits.
    @Published var upscaleTileSize = 128
    @Published private(set) var isUpscaling = false
    @Published private(set) var upscaleError: String?
    /// Set when the Core AI path is asked for but cannot run yet. Kept apart
    /// from `upscaleError` because the ESRGAN path is unaffected.
    @Published private(set) var coreAIUpscaleError: String?

    /// Shipped in the app bundle (iOS only; Android has no upscaler yet).
    static let bundledUpscalerName = "RealESRGAN_x4plus_anime_6B"

    static var bundledUpscalerPath: String? {
        Bundle.main.url(forResource: bundledUpscalerName, withExtension: "pth")?.path
    }

    private static func restore(_ key: String) -> String {
        UserDefaults.standard.string(forKey: key) ?? ""
    }

    /// An explicit clear (stored "") must stick, so only fall back to the bundled
    /// model when the key has never been written.
    private static func restoreUpscaler() -> String {
        if let stored = UserDefaults.standard.string(forKey: "sd.upscaler") { return stored }
        return bundledUpscalerPath ?? ""
    }

    private static func restoreScale() -> Int {
        let stored = UserDefaults.standard.integer(forKey: "sd.upscaleScale")
        return (2...4).contains(stored) ? stored : 4
    }

    private static func store(_ value: String, _ key: String, _ old: String) {
        guard value != old else { return }
        UserDefaults.standard.set(value, forKey: key)
    }

    /// Metal backend tuning, applied once when the context is created (the
    /// backend reads these environment knobs at init, not per graph). Kept
    /// user-visible because a full-resolution diffusion graph is exactly the
    /// workload the backend's defaults were not tuned for: a step that outruns
    /// the GPU watchdog needs more command buffers, and a GPU error under
    /// concurrency is worth ruling out by turning it off. n_cb defaults to 8
    /// (the backend's max; 1 is the library's own value, tuned for llama's
    /// small graphs) so a 1024x768 step is split into 9 evenly sized buffers,
    /// each well under the watchdog budget.
    @Published var metalCommandBuffers = 8
    @Published var metalFusion = true
    @Published var metalConcurrency = true

    private var context: SdContext?
    private var pollTask: Task<Void, Never>?
    private var lastLoggedStep = -1

    init() {
        // Every text/vision engine frees us before it builds its own Metal
        // context (see ComputeCoordinator). The coordinator calls this on the
        // main thread, which is why assumeIsolated is safe here.
        ComputeCoordinator.shared.registerImageEngine { [weak self] in
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
        if let bad = [mainModel, textEncoder, vae, clipL, clipG, t5]
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
        // Free every text/vision engine and its ggml-Metal context before sdcpp
        // builds its own. Two live Metal users abort llama at context creation
        // and starve auto-fit, so the hand-off is a hard requirement, not an
        // optimisation.
        ComputeCoordinator.shared.freeTextEngines()

        // An all-in-one checkpoint (the usual single-file SD1.5/SDXL download)
        // keeps its text encoders and VAE inside the one file. stable-diffusion
        // only looks inside it when it is handed to model_path; given instead to
        // diffusion_model_path the loader strips a "model.diffusion_model."
        // prefix and imports the UNet alone, so the conditioner finds no tensors
        // and load fails with "not in model metadata". Decide by what else was
        // supplied: with no separate encoder or CLIP/T5, the main file is the
        // whole checkpoint.
        let isFullCheckpoint = textEncoder.isEmpty && clipL.isEmpty && clipG.isEmpty && t5.isEmpty

        // Weight loading reads gigabytes. On the main actor that alone is enough
        // to trip the watchdog, so the whole call happens off-main.
        let model = mainModel, encoder = textEncoder, t5v = t5, vaePath = vae
        let clipLPath = clipL, clipGPath = clipG
        let full = isFullCheckpoint
        let (ncb, fuse, concurrent) = (metalCommandBuffers, metalFusion, metalConcurrency)
        // MLX decode needs a separate VAE file. With an all-in-one checkpoint
        // there is nothing for it to read, so fall back to a CPU decode rather
        // than leaving the GPU decode path (and its watchdog) armed.
        let mlxOn = vaeOnMLX
        let vaeCPU = vaeOnCPU || (mlxOn && vae.isEmpty)
        let threadCount = Int32(max(2, ProcessInfo.processInfo.activeProcessorCount - 1))
        // Opened before the context is created, not after: new_sd_ctx is where a
        // Metal load aborts, so a log file opened afterwards would miss the only
        // line that says why.
        let logPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pocketllm.log").path

        Task.detached(priority: .userInitiated) { [weak self] in
            SdContext.setLogPath(logPath)
            // The Metal backend reads these at init, so they must be set before
            // new_sd_ctx. Logged because the effect is invisible otherwise.
            SdContext.configureMetalCommandBuffers(Int32(ncb), fusion: fuse, concurrency: concurrent)
            appLog(String(format: "sd: metal n_cb=%d fusion=%@ concurrency=%@ vae=%@ mlx=%@ · fullCheckpoint=%@",
                          ncb, fuse ? "on" : "off", concurrent ? "on" : "off",
                          vaeCPU ? "cpu" : "metal", mlxOn ? "on" : "off",
                          full ? "yes" : "no"), source: "sd")
            let ctx: SdContext
            do {
                // ObjC methods whose last parameter is NSError** import into
                // Swift as throwing, so there is no `error:` argument to pass.
                ctx = try SdContext(
                    model: full ? model : nil,
                    clipL: clipLPath.isEmpty ? nil : clipLPath,
                    clipG: clipGPath.isEmpty ? nil : clipGPath,
                    clipVision: nil,
                    t5: t5v.isEmpty ? nil : t5v,
                    llm: encoder.isEmpty ? nil : encoder,
                    diffusion: full ? nil : (model.isEmpty ? nil : model),
                    vae: vaePath.isEmpty ? nil : vaePath,
                    taesd: nil,
                    threads: threadCount,
                    vaeOnCPU: vaeCPU)
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
        // MLX owns the decode, so the engine's own tiling must be off.
        // Only an actual separate VAE file can be handed to MLX; the engine then
        // leaves the latents untouched for it.
        let mlxVae = vaeOnMLX && !vae.isEmpty
        let (tile, tileSize) = (vaeTiling && !vaeOnCPU && !mlxVae, vaeTileSize)
        let vaePathForMLX = vae

        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            // Stage logs: a native crash produces no line of its own, so the last
            // line before death is the only evidence of where it died.
            appLog(String(format: "sd: entering generatePrompt (%dx%d, %d steps, cfg %.1f, batch %d, vaeTiling %@%d) · %@",
                          w, h, n, c, b, tile ? "on " : "off ", tile ? tileSize : 0,
                          LogStore.memoryDescription()), source: "sd")
            let result: [SdImage]?
            do {
                result = try ctx.generatePrompt(
                    promptText, negative: negativeText.isEmpty ? nil : negativeText,
                    width: Int32(w), height: Int32(h), steps: Int32(n),
                    cfg: c, distilledGuidance: 0, seed: s0, batch: Int32(b),
                    sampleMethod: Int32(sm), scheduler: Int32(sc),
                    clipSkip: Int32(cs2),
                    vaeTiling: tile, vaeTileSize: Int32(tileSize),
                    mlxVae: mlxVae,
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
            if mlxVae {
                // The engine wrote the latents and skipped its decode; run the
                // MLX decoder on them. Any existing images are ignored because
                // this path is defined by the latents file.
                let url = URL(fileURLWithPath: SdContext.latentsDumpPath())
                let t0 = Date()
                do {
                    // MLX's buffer pool otherwise grows to a large fraction of
                    // the GPU working set and counts against jetsam next to the
                    // engine's weights.
                    GPU.set(cacheLimit: 64 * 1024 * 1024)
                    GPU.resetPeakMemory()
                    let latents = try MLXVae.latents(from: url)
                    appLog("sd: mlx vae latents \(latents.shape)", source: "sd")
                    let vae = try MLXVae(vaePath: vaePathForMLX)
                    let pixels = try vae.decode(latents: latents)
                    appLog(String(format: "sd: mlx vae decoded %@ in %.1fs · peak %d MB · %@",
                                  String(describing: pixels.shape), Date().timeIntervalSince(t0),
                                  GPU.peakMemory / 1_048_576,
                                  LogStore.memoryDescription()), source: "sd")
                    if let ui = SdEngine.bitmap(from: pixels) { decoded.append(ui) }
                } catch {
                    appLog("sd: mlx vae failed: \(error.localizedDescription)", source: "sd")
                }
            }
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

    // MARK: - Upscaling

    /// Enlarges a finished image with the ESRGAN model in `upscaler`.
    ///
    /// Separate from `generate` on purpose: the model does not need reloading,
    /// and a 4x pass on a 1024px canvas allocates hundreds of MB per tile, so
    /// it runs off-main like the sampler does.
    func upscale(_ source: UIImage) {
        guard !isUpscaling else { return }
        guard !upscaler.isEmpty else {
            upscaleError = "尚未選擇放大模型（ESRGAN）"
            return
        }
        guard let input = Self.sdImage(from: source) else {
            upscaleError = "無法讀取這張圖片"
            return
        }
        isUpscaling = true
        upscaleError = nil
        let model = upscaler
        let gpu = upscaleOnGPU
        let target = max(2, min(4, upscaleScale))
        let tile = Int32(max(32, upscaleTileSize))
        let threads = Int32(max(2, ProcessInfo.processInfo.activeProcessorCount - 1))

        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            var output: UIImage?
            var failure: String?
            do {
                let upscaler = try SdUpscaler(
                    model: model, tileSize: tile, threads: threads, useGPU: gpu)
                let images: [SdImage]? = try upscaler.upscaleImage(input, factor: 4)
                if let first = images?.first, let bitmap = Self.bitmap(from: first) {
                    // The model's factor is fixed (4 here). Asking for 2x or 3x
                    // means resampling that result down instead of running a
                    // different network.
                    let native = Double(first.width) / Double(max(1, input.width))
                    if abs(native - Double(target)) > 0.01,
                       let shrunk = Self.resized(bitmap, factor: Double(target) / native) {
                        output = shrunk
                    } else {
                        output = bitmap
                    }
                    appLog(String(format: "sd: upscaled %dx%d -> %dx%d (native %@x, asked %dx) in %.1fs (gpu=%@ tile=%d) · %@",
                                  input.width, input.height, first.width, first.height,
                                  String(format: "%.0f", native), target,
                                  Date().timeIntervalSince(started), gpu ? "on" : "off",
                                  Int(tile), LogStore.memoryDescription()), source: "sd")
                } else {
                    failure = "放大沒有輸出"
                }
            } catch {
                failure = error.localizedDescription
            }
            await MainActor.run {
                guard let self else { return }
                self.isUpscaling = false
                if let failure {
                    self.upscaleError = failure
                    appLog("sd upscale failed: \(failure)", source: "sd")
                }
                if let output { self.images.append(output) }
            }
        }
    }

    /// Resamples by `factor` with high interpolation, for a requested scale that
    /// is below the model's native one. Runs off-main like the rest of the pass.
    nonisolated static func resized(_ image: UIImage, factor: Double) -> UIImage? {
        guard factor > 0, abs(factor - 1) > 0.01, let cg = image.cgImage else { return nil }
        let w = max(1, Int((Double(cg.width) * factor).rounded()))
        let h = max(1, Int((Double(cg.height) * factor).rounded()))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let out = ctx.makeImage() else { return nil }
        return UIImage(cgImage: out)
    }

    // MARK: - Core AI

    /// Runs the optional Core AI upscaler on the last image. The gate is inside
    /// `CoreAIUpscaler`; this only surfaces the outcome, so an iOS 17 device
    /// gets a sentence instead of a crash.
    func coreAIUpscale(_ source: UIImage) {
        coreAIUpscaleError = nil
        do {
            let out = try CoreAIUpscaler.upscale(source)
            images.append(out)
        } catch {
            coreAIUpscaleError = error.localizedDescription
            appLog("coreai upscale: \(error.localizedDescription)", source: "sd")
        }
    }

    /// UIImage -> interleaved RGB, the only layout the ESRGAN path reads. Drawn
    /// through a 32-bit context because 24-bit bitmap contexts are not reliably
    /// supported; the alpha bytes are dropped afterwards.
    nonisolated static func sdImage(from image: UIImage) -> SdImage? {
        guard let cg = image.cgImage else { return nil }
        let w = cg.width, h = cg.height
        guard w > 0, h > 0 else { return nil }
        var rgba = [UInt8](repeating: 255, count: w * h * 4)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(
                  data: &rgba, width: w, height: h, bitsPerComponent: 8,
                  bytesPerRow: w * 4, space: space,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))

        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        for i in 0..<(w * h) {
            let s = i * 4, d = i * 3
            rgb[d] = rgba[s]
            rgb[d + 1] = rgba[s + 1]
            rgb[d + 2] = rgba[s + 2]
        }
        // ObjC imports the +imageWithWidth: factory as this initializer.
        return SdImage(width: Int32(w), height: Int32(h), channel: 3,
                       pixels: Data(rgb))
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

    /// MLX decoder output is NHWC `[N, H, W, 3]` in `[-1, 1]` (mlx-swift's
    /// convolution is channels-last). Converts off-main like the `SdImage` path.
    nonisolated static func bitmap(from pixels: MLXArray) -> UIImage? {
        var x = pixels
        if x.ndim == 4 { x = x.squeezed(axis: 0) }
        guard x.ndim == 3 else { return nil }
        let h = x.shape[0], w = x.shape[1], c = x.shape[2]
        guard c >= 3, w > 0, h > 0 else { return nil }
        let scaled = clip((x + 1.0) * 127.5, min: 0.0, max: 255.0)
        let floats = scaled.asArray(Float.self)
        guard floats.count >= w * h * c else { return nil }

        var rgba = [UInt8](repeating: 255, count: w * h * 4)
        for i in 0..<(w * h) {
            let s = i * c, d = i * 4
            rgba[d]     = UInt8(floats[s])
            rgba[d + 1] = UInt8(floats[s + 1])
            rgba[d + 2] = UInt8(floats[s + 2])
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

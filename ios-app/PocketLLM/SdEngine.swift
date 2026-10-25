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
        /// Kept after the run so the progress bar can stay filled and green.
        case succeeded
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle {
        didSet { SdEngine.keepScreenAwake(while: phase) }
    }
    @Published private(set) var images: [UIImage] = []
    /// Set when a run ended in `.failed`. A Metal fault, a watchdog kill or an
    /// aborted sampler can leave the engine's graph and state caches
    /// inconsistent, so the next `generate()` rebuilds the context instead of
    /// reusing it — the alternative is a run that keeps failing on the same
    /// context until the user thinks to press 卸載.
    private var needsReload = false
    /// Set when the reload was started *because* a run failed, so the load
    /// resumes the generation the user asked for instead of stopping at idle.
    private var resumeAfterReload = false
    /// The user's saved presets, oldest first. Built-ins live in
    /// `GenPreset.builtins` and are never written here.
    @Published private(set) var presets: [GenPreset] = GenPreset.loadStored()
    /// Parameters of the last generation, so an upscale can be archived with the
    /// prompt that produced the source instead of the tab's current text.
    private var lastArchive: GalleryStore.Meta?

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
    /// A tiny decoder (`taesd_path`) used instead of the real VAE. Wan 2.2's
    /// bundle ships `taew2_2.safetensors` for exactly this: decoding a dozen
    /// frames through the full 1.4 GB VAE is minutes of GPU work in one chain,
    /// which is the chain the watchdog kills, while the tiny decoder is cheap.
    @Published var tae = SdEngine.restore("sd.tae") {
        didSet { SdEngine.store(tae, "sd.tae", oldValue) }
    }
    @Published var clipL       = ""   // clip_l_path (SD1.5/SDXL encoders)
    @Published var clipG       = ""   // clip_g_path (SDXL's OpenCLIP-G encoder)
    @Published var t5          = ""   // t5xxl_path
    @Published var loras       = ""   // "path|multiplier;path|multiplier"

    @Published var prompt = ""
    @Published var negative = ""
    /// img2img: the starting image, and how much of it to replace (1 ignores it).
    /// Not persisted: a UIImage from the gallery is not something to silently
    /// carry into the next session's generation.
    @Published var initImage: UIImage?
    @Published var strength: Float = 0.6
    /// Video: Wan 2.2 TI2V and friends go through generate_video, not
    /// generate_image, and their checkpoints are refused in image mode on
    /// purpose (a phone cannot hold a multi-frame graph plus the weights). The
    /// frame count has to be 4n+1 for these models; the UI only offers those.
    @Published var videoMode = false
    @Published var videoFrames = 13
    @Published var videoFps = 16
    @Published var videoFlowShift: Float = 3
    @Published private(set) var videoFramesOut: [UIImage] = []

    /// Hires: a second pass at `hiresScale` times the canvas, re-denoised for
    /// `hiresSteps` at `hiresStrength`. Costs roughly a second base pass plus a
    /// decode at the target size, which is why the strength is the knob that
    /// keeps it from redoing the whole picture.
    /// A second checkpoint used as a refiner: the last result goes back through
    /// img2img at `refinerStrength`. Persisted like the VAE, because it is a slot
    /// the user picked deliberately and an all-in-one file cannot declare it.
    @Published var refinerModel = SdEngine.restore("sd.refiner") {
        didSet { SdEngine.store(refinerModel, "sd.refiner", oldValue) }
    }
    @Published var refinerStrength: Float = 0.3
    @Published var hiresEnabled = false
    @Published var hiresScale: Float = 2
    @Published var hiresSteps = 10
    @Published var hiresStrength: Float = 0.45
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

    /// Holds the screen awake while the GPU has work queued.
    ///
    /// The iOS GPU watchdog kills a command buffer that stops making progress
    /// for ~10 s, and a dimmed or locked screen is exactly how that happens to a
    /// run that would otherwise finish: once the display sleeps, GPU scheduling
    /// is throttled and the buffer that was mid-flight times out. Loading
    /// counts too - it is minutes of weight traffic on a big checkpoint.
    private static func keepScreenAwake(while phase: Phase) {
        let busy: Bool
        switch phase {
        case .loading, .running: busy = true
        case .idle, .succeeded, .failed: busy = false
        }
        if UIApplication.shared.isIdleTimerDisabled != busy {
            UIApplication.shared.isIdleTimerDisabled = busy
            appLog("app: idle timer \(busy ? "disabled (generating)" : "restored")", source: "app")
        }
    }

    func load() {
        // A video checkpoint is refused in image mode because the picture path
        // cannot decode multiple frames and the weights plus a multi-frame graph
        // do not fit. With 影片模式 the user is asking for exactly that run, so
        // the slot is allowed through and the generate button routes to the
        // video entry point instead.
        if !videoMode,
           let bad = [mainModel, textEncoder, vae, clipL, clipG, t5]
               .first(where: { !$0.isEmpty && Self.isVideoCheckpoint($0) }) {
            phase = .failed("這是影片模型（\(bad)）。手機記憶體放不下多幀影片：要跑請開「影片模式」，否則請改用圖片模型。")
            appLog("sd: refused video checkpoint \(bad) (影片模式 is off)", source: "sd")
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
        let taePath = tae
        let clipLPath = clipL, clipGPath = clipG
        let full = isFullCheckpoint
        let (ncb, fuse, concurrent) = (metalCommandBuffers, metalFusion, metalConcurrency)
        // MLX decode needs a separate VAE file. With an all-in-one checkpoint
        // there is nothing for it to read, so fall back to a CPU decode rather
        // than leaving the GPU decode path (and its watchdog) armed.
        let mlxOn = vaeOnMLX
        // CPU whenever MLX is armed, not only when it has no file: if the MLX
        // decode fails the run is retried with the engine's own decoder, and
        // that fallback must not be the GPU decode the watchdog kills.
        let vaeCPU = vaeOnCPU || mlxOn
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
                    taesd: taePath.isEmpty ? nil : taePath,
                    threads: threadCount,
                    vaeOnCPU: vaeCPU)
            } catch {
                await MainActor.run {
                    self?.resumeAfterReload = false
                    self?.phase = .failed(error.localizedDescription)
                    appLog("sd load failed: \(error.localizedDescription)", source: "sd")
                }
                return
            }
            await MainActor.run {
                guard let self else { return }
                self.context = ctx
                self.needsReload = false
                self.phase = .idle
                appLog("sd loaded · \(URL(fileURLWithPath: model).lastPathComponent)", source: "sd")
                // A failed run asked for a rebuild; continue the generation the
                // user pressed 生成 for rather than making them press it again.
                if self.resumeAfterReload {
                    self.resumeAfterReload = false
                    self.generate()
                }
            }
        }
    }

    func unload() {
        if context != nil {
            appLog("sd unloaded so the LLM can take the GPU", source: "sd")
        }
        context = nil
        needsReload = false
        resumeAfterReload = false
        phase = .idle
    }

    // MARK: - Presets

    /// The built-in that fits the loaded model, offered first in the menu.
    /// `ModelAudit` caches header facts, so re-reading it on every body pass is
    /// a dictionary lookup after the first call.
    var recommendedPreset: GenPreset? { GenPreset.recommended(for: mainModel) }

    /// Apply a preset. A built-in only sets sampling values; a saved preset is
    /// a snapshot of the whole tab, so its slots are authoritative and an empty
    /// slot means cleared.
    func apply(_ preset: GenPreset) {
        steps = preset.steps
        cfg = preset.cfg
        width = preset.width
        height = preset.height
        seed = preset.seed
        clipSkip = preset.clipSkip
        negative = preset.negative
        // Indices come from the engine's own tables, and a preset saved by an
        // older build may point past the end of a shorter list.
        sampleMethod = min(max(0, preset.sampleMethod),
                           max(0, SdContext.sampleMethodNames().count - 1))
        scheduler = min(max(0, preset.scheduler),
                        max(0, SdContext.schedulerNames().count - 1))
        if !preset.isBuiltin {
            let slotsChanged = preset.mainModel != mainModel || preset.textEncoder != textEncoder
                || preset.vae != vae || preset.loras != loras
            mainModel = preset.mainModel
            textEncoder = preset.textEncoder
            vae = preset.vae
            loras = preset.loras
            if slotsChanged && context != nil {
                // The loaded weights are not the preset's. Rebuild before the
                // next run, reusing the same path a failed run takes.
                needsReload = true
                appLog("sd: preset 「\(preset.name)」changed the model slots, reloading before the next run",
                       source: "sd")
            }
        }
        appLog(String(format: "sd: applied preset 「%@」 %dx%d %d steps cfg %.1f",
                      preset.name, preset.width, preset.height, preset.steps, preset.cfg),
               source: "sd")
    }

    /// Save the tab's current settings under a name. Saving again under the same
    /// name overwrites, which is what "save" means the second time.
    func savePreset(named raw: String) {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        var preset = GenPreset(name: name)
        preset.mainModel = mainModel
        preset.textEncoder = textEncoder
        preset.vae = vae
        preset.loras = loras
        preset.steps = steps
        preset.cfg = cfg
        preset.width = width
        preset.height = height
        preset.seed = seed
        preset.sampleMethod = sampleMethod
        preset.scheduler = scheduler
        preset.clipSkip = clipSkip
        preset.negative = negative
        if let i = presets.firstIndex(where: { $0.name == name }) {
            preset.id = presets[i].id
            presets[i] = preset
        } else {
            presets.append(preset)
        }
        GenPreset.persist(presets)
        appLog("sd: saved preset 「\(name)」", source: "sd")
    }

    func deletePreset(_ preset: GenPreset) {
        presets.removeAll { $0.id == preset.id }
        GenPreset.persist(presets)
    }

    /// Re-runs the last image through the refiner at low strength.
    ///
    /// This is the img2img path with another checkpoint, not a second engine
    /// feature: the source is the picture itself, so the base pass is skipped
    /// and only the low-strength pass runs. The main slot becomes the refiner,
    /// which the reload path already handles; the file should be the same kind
    /// as the base model (all-in-one for all-in-one), because the encoder slots
    /// are left as they are rather than guessed at.
    func refineLast() {
        guard let last = images.last else {
            phase = .failed("還沒有可精修的圖片")
            return
        }
        guard !refinerModel.isEmpty else {
            phase = .failed("先在「進階」選精修模型")
            return
        }
        // img2img stretches the source to width/height, so match the picture
        // being refined instead of the tab's canvas.
        let w8 = max(256, min(1024, (Int(last.size.width) / 8) * 8))
        let h8 = max(256, min(1024, (Int(last.size.height) / 8) * 8))
        width = w8
        height = h8
        initImage = last
        strength = refinerStrength
        hiresEnabled = false
        let reload = context != nil && refinerModel != mainModel
        mainModel = refinerModel
        if reload { needsReload = true }
        appLog("sd: refine pass — \(URL(fileURLWithPath: refinerModel).lastPathComponent) \(w8)x\(h8) strength \(refinerStrength)", source: "sd")
        generate()
    }

    /// Video generation: the same shape as `generate()` but through
    /// `generate_video`, which returns frames instead of one image.
    ///
    /// No MLX-VAE hand-off here - the latents hook lives in the image pipeline,
    /// and the engine's own decode is what the tiny decoder slot is for. There
    /// is also no second fallback: a failed frame is not worth re-sampling a
    /// dozen frames for, and the watchdog levers (flash attention, n_cb, the
    /// tiny decoder) are already applied.
    func generateVideo() {
        guard let ctx = context else {
            phase = .failed("尚未載入模型")
            return
        }
        if needsReload {
            needsReload = false
            resumeAfterReload = true
            appLog("sd: reloading the model after the failed run", source: "sd")
            context = nil
            load()
            return
        }
        phase = .running(step: 0, steps: steps)
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .milliseconds(300))
                } catch {
                    return
                }
                guard let self, let c = self.context, !Task.isCancelled else { return }
                let step = Int(c.progressStep)
                let total = Int(c.progressSteps)
                if total > 0, case .running = self.phase {
                    self.phase = .running(step: step, steps: total)
                    if step != self.lastLoggedStep {
                        self.lastLoggedStep = step
                        appLog(String(format: "sd: step %d/%d · %@",
                                      step, total, LogStore.memoryDescription()), source: "sd")
                    }
                }
            }
        }

        let promptText = prompt
        let negativeText = negative
        let (w, h, n) = (width, height, steps)
        let (c, s0) = (cfg, seed)
        let (frames, fps) = (videoFrames, videoFps)
        let flow = videoFlowShift
        let strengthValue = strength
        let initSpec = initImage.flatMap { SdEngine.imageSpec(from: $0, width: w, height: h) }
        let modelName = URL(fileURLWithPath: mainModel).lastPathComponent

        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            appLog(String(format: "sd: entering generateVideo (%dx%d, %d frames @ %d fps, %d steps, cfg %.1f, flow %.1f%@) · %@",
                          w, h, frames, fps, n, c, flow,
                          initSpec != nil ? String(format: ", i2v %.2f", strengthValue) : "",
                          LogStore.memoryDescription()), source: "sd")
            var out: [UIImage] = []
            do {
                let images = try ctx.generateVideo(
                    promptText, negative: negativeText.isEmpty ? nil : negativeText,
                    width: Int32(w), height: Int32(h), frames: Int32(frames), fps: Int32(fps),
                    steps: Int32(n), cfg: c, flowShift: flow, seed: s0,
                    initImage: initSpec, strength: strengthValue)
                out = (images ?? []).compactMap { SdEngine.bitmap(from: $0) }
            } catch {
                await MainActor.run {
                    guard let self else { return }
                    self.pollTask?.cancel()
                    self.pollTask = nil
                    self.needsReload = true
                    self.phase = .failed(error.localizedDescription)
                    appLog("sd video failed: \(error.localizedDescription)", source: "sd")
                }
                return
            }
            let secs = Date().timeIntervalSince(started)
            await MainActor.run {
                guard let self else { return }
                self.pollTask?.cancel()
                self.pollTask = nil
                if out.isEmpty {
                    self.needsReload = true
                    self.phase = .failed("影片沒有輸出")
                    appLog("sd video failed: no frames", source: "sd")
                } else {
                    self.videoFramesOut = out
                    // The result area renders `images`, so a video is shown as
                    // its frames; the archive keeps the first one as the still.
                    self.images = out
                    self.needsReload = false
                    self.phase = .succeeded
                    let archive = GalleryStore.Meta(
                        prompt: promptText, negative: negativeText,
                        width: w, height: h, steps: n, seed: s0, model: modelName)
                    self.lastArchive = archive
                    if let first = out.first { GalleryStore.shared.save(first, meta: archive) }
                    appLog(String(format: "sd generated %d frame(s) · %.1fs · %@",
                                  out.count, secs, LogStore.memoryDescription()), source: "sd")
                }
            }
        }
    }

    // MARK: - Generation

    func generate() {
        guard let ctx = context else {
            phase = .failed("尚未載入模型")
            return
        }
        if needsReload {
            needsReload = false
            resumeAfterReload = true
            appLog("sd: reloading the model after the failed run", source: "sd")
            // Release the old context first: the reload allocates the whole
            // weight set again, and two live contexts do not fit in memory.
            context = nil
            load()
            return
        }
        phase = .running(step: 0, steps: steps)
        appLogMemory("sd pre-generate", source: "sd")

        // Progress is polled rather than pushed: the sampler runs off the main
        // thread and must not re-enter Swift from C++.
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                // `do/catch`, not `try?`: a cancelled sleep throws immediately,
                // and `try?` would swallow that and let the body run once more -
                // overwriting the .succeeded (green) or .failed (red) phase the
                // finishing task had just set with a stale .running.
                do {
                    try await Task.sleep(for: .milliseconds(300))
                } catch {
                    return
                }
                guard let self, let c = self.context, !Task.isCancelled else { return }
                let step = Int(c.progressStep)
                let total = Int(c.progressSteps)
                // Only a live run may move the bar: a terminal phase is owned by
                // the task that produced it.
                if total > 0, case .running = self.phase {
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
        // Plain values for the detached task: these are main-actor state.
        let strengthValue = strength
        let hiresOn = hiresEnabled
        let hiresScaleValue = hiresScale
        // The engine wants a size in image pixels, a multiple of 8 (16 for the
        // 2D VAEs' three 2x stages would be safest); 8 keeps the aspect right
        // and the engine rounds further down itself.
        let hiresTargetW = Int32(max(8, (Int(Float(w) * hiresScale) / 8) * 8))
        let hiresTargetH = Int32(max(8, (Int(Float(h) * hiresScale) / 8) * 8))
        let hiresStepCount = Int32(hiresSteps)
        let hiresDenoise = hiresStrength
        let (n, c) = (steps, cfg)
        let (s0, b) = (seed, batch)
        let (sm, sc, cs2) = (sampleMethod, scheduler, clipSkip)
        let loraSpec = loras
        // MLX owns the decode, so the engine's own tiling must be off.
        // Only an actual separate VAE file can be handed to MLX; the engine then
        // leaves the latents untouched for it.
        // A VAE the MLX decoder cannot read must not take the MLX path: the
        // engine would skip its own decode and the run would end with no image.
        var mlxVae = vaeOnMLX && !vae.isEmpty
        if mlxVae, let problem = MLXVae.decodeProblem(vae) {
            appLog("sd: MLX decode unavailable, engine will decode: \(problem)", source: "sd")
            mlxVae = false
        }
        let (tile, tileSize) = (vaeTiling && !vaeOnCPU && !mlxVae, vaeTileSize)
        let vaePathForMLX = vae
        let modelName = URL(fileURLWithPath: mainModel).lastPathComponent
        // Snapshot the img2img input on the main actor: UIImage is not sendable,
        // and the engine wants raw interleaved RGB at the target size.
        let initSpec = initImage.flatMap { SdEngine.imageSpec(from: $0, width: w, height: h) }
        if initImage != nil && initSpec == nil {
            appLog("sd: img2img source could not be converted, running text-to-image", source: "sd")
        }

        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            // Stage logs: a native crash produces no line of its own, so the last
            // line before death is the only evidence of where it died.
            appLog(String(format: "sd: entering generatePrompt (%dx%d, %d steps, cfg %.1f, batch %d, vaeTiling %@%d%@%@) · %@",
                          w, h, n, c, b, tile ? "on " : "off ", tile ? tileSize : 0,
                          initSpec != nil ? String(format: ", img2img %.2f", strengthValue) : "",
                          hiresOn ? String(format: ", hires %gx->%dx%d %.2f", hiresScaleValue, hiresTargetW, hiresTargetH, hiresDenoise) : "",
                          LogStore.memoryDescription()), source: "sd")
            var result: [SdImage]?
            do {
                result = try ctx.generatePrompt(
                    promptText, negative: negativeText.isEmpty ? nil : negativeText,
                    width: Int32(w), height: Int32(h), steps: Int32(n),
                    cfg: c, distilledGuidance: 0, seed: s0, batch: Int32(b),
                    sampleMethod: Int32(sm), scheduler: Int32(sc),
                    clipSkip: Int32(cs2),
                    vaeTiling: tile, vaeTileSize: Int32(tileSize),
                    mlxVae: mlxVae,
                    initImage: initSpec,
                    strength: strengthValue,
                    hires: hiresOn, hiresTargetW: hiresTargetW, hiresTargetH: hiresTargetH,
                    hiresSteps: hiresStepCount, hiresStrength: hiresDenoise,
                    loras: loraSpec.isEmpty ? nil : loraSpec)
            } catch {
                await MainActor.run {
                    guard let self else { return }
                    self.pollTask?.cancel()
                    self.pollTask = nil
                    self.needsReload = true
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
            var mlxFailure: String?
            /// Which blob the MLX attempt read, so the engine retry reads the
            /// same one: the ".vae" sibling for a 2D decoder, the raw blob for 3D.
            var mlxVaeSpace = false
            if mlxVae {
                // The engine wrote the latents and skipped its decode; run the
                // MLX decoder on them. Any existing images are ignored because
                // this path is defined by the latents file. A 2D VAE reads the
                // ".vae" sibling: those latents already carry the engine's scale
                // factor and shift, which the decoder cannot derive from weights.
                let t0 = Date()
                do {
                    let info = try MLXVae.inspect(vaePathForMLX)
                    // A 2D decoder reads the ".vae" blob (the engine's scale
                    // factor and shift are already applied); the 3D path reads
                    // the raw one and applies Wan/Qwen statistics itself. The
                    // engine's decode-only retry has to read the same file.
                    mlxVaeSpace = info.layout == .diffusers2D
                    let dump = SdContext.latentsDumpPath()
                        + (info.layout == .diffusers2D ? ".vae" : "")
                    let url = URL(fileURLWithPath: dump)
                    // MLX's buffer pool otherwise grows to a large fraction of
                    // the GPU working set and counts against jetsam next to the
                    // engine's weights.
                    GPU.set(cacheLimit: 64 * 1024 * 1024)
                    GPU.resetPeakMemory()
                    let latents = try MLXVae.latents(from: url)
                    appLog("sd: mlx vae latents \(latents.shape) (\(info.layout == .diffusers2D ? "2d" : "3d"))", source: "sd")
                    let vae = try MLXVae(vaePath: vaePathForMLX)
                    let pixels = try vae.decode(latents: latents)
                    appLog(String(format: "sd: mlx vae decoded %@ in %.1fs · peak %d MB · %@",
                                  String(describing: pixels.shape), Date().timeIntervalSince(t0),
                                  GPU.peakMemory / 1_048_576,
                                  LogStore.memoryDescription()), source: "sd")
                    if let ui = SdEngine.bitmap(from: pixels) {
                        decoded.append(ui)
                    } else {
                        mlxFailure = "MLX 解碼結果無法轉成圖片"
                    }
                } catch {
                    mlxFailure = error.localizedDescription
                    appLog("sd: mlx vae failed: \(error.localizedDescription)", source: "sd")
                }
                if decoded.isEmpty, mlxFailure == nil {
                    mlxFailure = "MLX 解碼沒有輸出"
                }
            }

            // Fallback, cheapest first:
            //   1. the engine decodes the *same* latents on the CPU (sd_decode_latents
            //      reads the blob MLX just failed on, so no sampling happens),
            //   2. only if that fails too, a full re-run with the same seed - the
            //      public generate path samples first, so this is the expensive one.
            // The context was created with `vae=cpu` whenever MLX is armed, so
            // neither can end in the GPU watchdog that made MLX the default.
            if let failure = mlxFailure {
                appLog("sd: MLX 解碼失敗（\(failure)），改用引擎 CPU 解碼同一批 latent", source: "sd")
                let dump = SdContext.latentsDumpPath() + (mlxVaeSpace ? ".vae" : "")
                if let images = try? ctx.decodeLatents(dump, vaeSpace: mlxVaeSpace,
                                                       tiling: true, tileSize: Int32(tileSize)),
                   !images.isEmpty {
                    decoded = images.compactMap { SdEngine.bitmap(from: $0) }
                    if !decoded.isEmpty {
                        appLog("sd: 引擎 CPU 解碼 latent 成功（\(decoded.count) 張），未重新取樣", source: "sd")
                    }
                }
            }
            if let failure = mlxFailure, decoded.isEmpty {
                appLog("sd: 引擎解碼 latent 也失敗，重跑（同 seed）", source: "sd")
                do {
                    result = try ctx.generatePrompt(
                        promptText, negative: negativeText.isEmpty ? nil : negativeText,
                        width: Int32(w), height: Int32(h), steps: Int32(n),
                        cfg: c, distilledGuidance: 0, seed: s0, batch: Int32(b),
                        sampleMethod: Int32(sm), scheduler: Int32(sc),
                        clipSkip: Int32(cs2),
                        vaeTiling: true, vaeTileSize: Int32(tileSize),
                        mlxVae: false,
                        initImage: initSpec,
                        strength: strengthValue,
                        hires: hiresOn, hiresTargetW: hiresTargetW, hiresTargetH: hiresTargetH,
                        hiresSteps: hiresStepCount, hiresStrength: hiresDenoise,
                        loras: loraSpec.isEmpty ? nil : loraSpec)
                } catch {
                    await MainActor.run {
                        guard let self else { return }
                        self.pollTask?.cancel()
                        self.pollTask = nil
                        let message = "MLX 解碼失敗（\(failure)），CPU 解碼也失敗：\(error.localizedDescription)"
                        self.needsReload = true
                        self.phase = .failed(message)
                        appLog("sd cpu fallback failed: \(message)", source: "sd")
                    }
                    return
                }
            }

            for (i, img) in (result ?? []).enumerated() {
                appLog("sd: decoding image \(i) \(img.width)x\(img.height)x\(img.channel)", source: "sd")
                if let ui = SdEngine.bitmap(from: img) { decoded.append(ui) }
            }
            let secs = Date().timeIntervalSince(started)
            // Snapshot: the failure text is only needed for the final message,
            // and a `let` keeps it out of the sendable capture.
            let mlxFailureText = mlxFailure

            await MainActor.run {
                guard let self else { return }
                self.pollTask?.cancel()
                self.pollTask = nil
                if decoded.isEmpty {
                    let message = mlxFailureText.map { "MLX 解碼失敗（\($0)），CPU 解碼後仍未產生影像" }
                        ?? "未產生影像"
                    self.needsReload = true
                    self.phase = .failed(message)
                    appLog("sd generate failed: \(message)", source: "sd")
                } else {
                    self.images = decoded
                    self.needsReload = false
                    self.phase = .succeeded
                    let archive = GalleryStore.Meta(
                        prompt: promptText, negative: negativeText,
                        width: w, height: h, steps: n, seed: s0,
                        model: modelName)
                    self.lastArchive = archive
                    for image in decoded {
                        GalleryStore.shared.save(image, meta: archive)
                    }
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
                if let output {
                    self.images.append(output)
                    var meta = self.lastArchive ?? GalleryStore.Meta()
                    meta.kind = "upscaled"
                    meta.scale = target
                    meta.width = Int(output.size.width)
                    meta.height = Int(output.size.height)
                    meta.createdAt = Date()
                    GalleryStore.shared.save(output, meta: meta)
                }
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
            var meta = lastArchive ?? GalleryStore.Meta()
            meta.kind = "upscaled"
            meta.width = Int(out.size.width)
            meta.height = Int(out.size.height)
            meta.createdAt = Date()
            GalleryStore.shared.save(out, meta: meta)
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

    /// Redraws an image into the engine's `sd_image_t` form: interleaved RGB8 at
    /// exactly width x height. The engine would scale it too, but doing it here
    /// keeps the aspect-ratio decision (a plain stretch) visible in the app
    /// instead of hidden in C, and costs one draw.
    nonisolated static func imageSpec(from image: UIImage, width: Int, height: Int) -> SdImage? {
        guard width > 0, height > 0 else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        guard let cg = image.cgImage else { return nil }
        let space = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(data: &rgba, width: width, height: height,
                                  bitsPerComponent: 8, bytesPerRow: width * 4,
                                  space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return nil
        }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        for i in 0..<(width * height) {
            rgb[i * 3]     = rgba[i * 4]
            rgb[i * 3 + 1] = rgba[i * 4 + 1]
            rgb[i * 3 + 2] = rgba[i * 4 + 2]
        }
        return SdImage(width: Int32(width), height: Int32(height), channel: 3,
                       pixels: Data(rgb))
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

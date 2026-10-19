import Foundation
import UIKit

/// On-device inference using the llama.cpp ObjC++ bridge.
final class LocalEngine: ObservableObject {
    @Published var state: String = "no model loaded"

    /// Time to first token of the last generation, in seconds.
    @Published var lastTTFT: Double = 0
    /// Decode rate of the last generation, in tokens per second.
    @Published var lastTokensPerSecond: Double = 0
    /// Tokens emitted by the last generation.
    @Published var lastTokenCount: Int = 0

    /// Offload every layer. llama.cpp treats a negative count as "all", but 999
    /// exceeds any model in this family and is what the Android bridge uses, so
    /// the two stay in step.
    static let allLayers = 999

    private var context: LlamaContext?

    /// YES when a companion mmproj loaded and reported image support. Gate the
    /// attachment UI on this, not on the model's filename.
    @Published private(set) var supportsVision = false

    /// Name of the loaded projector, for display in the details panel.
    @Published private(set) var mmprojName: String?
    // Security scope is held for the context's whole lifetime: llama.cpp mmaps
    // the model file, so pages can fault in long after load() returns; stopping
    // the scope early can make later page-ins fail.
    private var securedURL: URL?
    private var securedActive = false

    private func releaseSecurityScope() {
        if securedActive { securedURL?.stopAccessingSecurityScopedResource() }
        securedActive = false
        securedURL = nil
    }

    init() {
        // The image engine frees us before it builds its own Metal context, so
        // the two ggml instances never hold a GPU at the same time.
        ComputeCoordinator.shared.registerLLMTeardown { [weak self] in
            self?.unload()
        }
    }

    /// Drops the context and, with it, llama's Metal buffers and model weights.
    /// Called from the coordinator under the hand-off lock, so it must actually
    /// release the GPU before returning rather than scheduling the free.
    func unload() {
        guard context != nil else { return }
        context = nil
        releaseSecurityScope()
        appLog("unloaded so the image engine can take the GPU", source: "llama")
        // @Published mutations must reach SwiftUI on the main thread; the free
        // itself has already happened synchronously above.
        DispatchQueue.main.async {
            self.supportsVision = false
            self.mmprojName = nil
            self.state = "no model loaded"
        }
    }

    /// `gpuLayers` is 0 for CPU-only, `allLayers` for Metal. The bridge clamps it
    /// to what the build actually supports, so asking for Metal on the simulator
    /// (which has no Metal backend) silently yields a CPU load rather than a
    /// failure — the reported backend below is the truth.
    func load(url: URL, contextSize: Int32, threads: Int, gpuLayers: Int) {
        // Native stage logs must land in the same file the Logs tab reads.
        LlamaContext.setLogPath(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("pocketllm.log").path)
        appLogMemory("pre-load", source: "llama")
        state = "loading…"
        supportsVision = false
        mmprojName = nil
        appLog("loading…", source: "llama")
        LlamaGlobalInit()
        // Release the image engine first: its ggml-Metal copy must be gone before
        // llama builds its context, or llama_init_from_model aborts in
        // ggml_metal_buffer_is_shared.
        ComputeCoordinator.shared.freeSD()
        releaseSecurityScope() // drop the previous model's scope, if any
        let securityScoped = url.startAccessingSecurityScopedResource()
        securedURL = url
        securedActive = securityScoped
        do {
            let ctx = try LlamaContext(modelPath: url.path,
                                       contextSize: contextSize,
                                       batchSize: 2048,
                                       threads: Int32(threads),
                                       nGpuLayers: Int32(gpuLayers))
            context = ctx
            let backend = ctx.usingGpu ? "Metal" : "CPU"
            // contextLength is an ObjC method, not a property: without the call
            // Swift interpolates the function value itself.
            state = "\(url.lastPathComponent) · \(backend) · ctx \(ctx.contextLength())"
            appLog("\(url.lastPathComponent) · \(backend) · ctx \(ctx.contextLength())", source: "llama")

            // Vision models ship as two files in one folder: the text model and
            // its projector. Loading the companion here means the attachment
            // button can be gated on the truth rather than on a name guess.
            if let mmproj = Self.companionMmproj(for: url) {
                do {
                    // Swift imports BOOL + NSError** as `throws -> Void`, treating
                    // the flag as the success indicator. The loader is called for
                    // its effect and the answer is read from supportsVision, which
                    // is the engine's own report rather than the return value.
                    try ctx.loadVision(withMmprojPath: mmproj.path)
                    supportsVision = ctx.supportsVision
                    mmprojName = mmproj.lastPathComponent
                    appLog(supportsVision
                           ? "vision: enabled via \(mmproj.lastPathComponent)"
                           : "vision: \(mmproj.lastPathComponent) loaded but reports no vision",
                           source: "llama")
                } catch {
                    appLog("vision: \(mmproj.lastPathComponent) failed — \(error.localizedDescription)",
                           source: "llama")
                }
            } else {
                appLog("vision: no mmproj beside \(url.lastPathComponent)", source: "llama")
            }
        } catch {
            releaseSecurityScope()
            // Surface the real reason — "load failed" gave users nothing.
            state = "load failed: \(error.localizedDescription)"
            appLog("load failed: \(error.localizedDescription)", source: "llama")
        }
    }

    var isReady: Bool { context != nil }

    /// Attaches a projector the user picked by hand, for the case where the
    /// mmproj does not sit beside the model or does not carry "mmproj" in its
    /// name. Without this the picker in the details panel was a no-op, so a
    /// projector that was not auto-found could never reach the vision tower.
    func attachVision(from url: URL) {
        guard let ctx = context else {
            appLog("vision: no model loaded to attach a projector to", source: "llama")
            return
        }
        // Held for the session on purpose: mtmd mmaps the projector, so pages
        // can fault in long after this call returns. Releasing the scope here
        // would make a later page-in fail.
        _ = url.startAccessingSecurityScopedResource()
        appLog("vision: attaching \(url.lastPathComponent)", source: "llama")
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try ctx.loadVision(withMmprojPath: url.path)
                DispatchQueue.main.async {
                    self.supportsVision = ctx.supportsVision
                    self.mmprojName = url.lastPathComponent
                    appLog(self.supportsVision
                           ? "vision: enabled via \(url.lastPathComponent)"
                           : "vision: \(url.lastPathComponent) loaded but reports no vision",
                           source: "llama")
                }
            } catch {
                DispatchQueue.main.async {
                    self.supportsVision = false
                    appLog("vision: \(url.lastPathComponent) failed — \(error.localizedDescription)",
                           source: "llama")
                }
            }
        }
    }

    /// Finds the mmproj that ships beside a GGUF model.
    ///
    /// Matched on the filename because that is the only signal the filesystem
    /// offers — an mmproj carries no marker readable without loading it, and
    /// loading it is the expensive decision this lookup exists to inform.
    /// Returns nil for a plain text model, which is the common case.
    /// Writes a down-scaled copy for the vision path and returns it.
    ///
    /// Vision prompts spend context on image tokens, so a full-resolution photo
    /// can exhaust the window before the question is tokenised. The copy lives
    /// beside the original and is temporary.
    nonisolated static func visionSizedCopy(of url: URL, maxDimension: CGFloat) -> URL? {
        guard let data = try? Data(contentsOf: url),
              let image = UIImage(data: data) else { return nil }
        let scaled = image.scaled(maxDimension: maxDimension)
        guard let jpeg = scaled.jpegData(compressionQuality: 0.9) else { return nil }
        let out = url.deletingLastPathComponent()
            .appendingPathComponent("vision-\(url.deletingPathExtension().lastPathComponent).jpg")
        do {
            try jpeg.write(to: out)
            return out
        } catch {
            return nil
        }
    }

    nonisolated static func companionMmproj(for modelURL: URL) -> URL? {
        let dir = modelURL.deletingLastPathComponent()
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil)) ?? []
        return entries.first { url in
            let n = url.lastPathComponent.lowercased()
            // Same match as ModelInspector.companionProjection: it decides whether
            // the UI offers vision, so a projector this rejects would advertise a
            // button the engine then refuses.
            return n.hasSuffix(".gguf") && (n.contains("mmproj") || n.contains("projector"))
        }
    }

    /// One image+text turn on the vision path, when an mmproj is loaded.
    ///
    /// Takes a URL rather than a UIImage because the bridge reads the file
    /// itself; decoding here would only duplicate that work.
    func describe(image: URL, prompt: String,
                  onToken: @escaping (String) -> Void,
                  done: @escaping (String?) -> Void) {
        guard let ctx = context, supportsVision else {
            done("此模型沒有載入視覺投影（mmproj），無法分析圖片")
            return
        }
        // Encoding, evaluating and decoding an image is minutes of work and
        // describeImage is synchronous. Running it on the caller's thread froze
        // the UI until the watchdog killed the app -- the same defect already
        // fixed in SdEngine and VLMEngine, and the reason this path hung.
        DispatchQueue.global(qos: .userInitiated).async {
            // Image tokens scale with pixel area: a 1280px photo on a
            // Qwen-class VLM is roughly (1280/28)^2 ~ 2000 tokens before any
            // text, which overflows the default 2048 context.
            let target = Self.visionSizedCopy(of: image, maxDimension: 768) ?? image
            // An instruct VLM given bare text has no user turn, so it emits a
            // space and stops. The marker goes inside the user turn, which is
            // where mtmd expects the image placeholder.
            let marker = LlamaContext.visionMarker()
            let raw = marker.isEmpty ? prompt : marker + "\n" + prompt
            let templated = ctx.applyTemplate([["user", raw]]) ?? raw
            // Log exactly what reaches the model: the typed text, the template
            // wrapper, and the marker position. Reading the code has been an
            // unreliable way to confirm this chain, so the prompt is recorded.
            let head = templated.prefix(160).replacingOccurrences(of: "\n", with: "\\n")
            appLog("vision: typed=\(prompt.prefix(80)) chars=\(prompt.count)", source: "llama")
            appLog("vision: templated chars=\(templated.count) head=\(head)", source: "llama")
            ctx.describeImage(target.path, prompt: templated, maxTokens: 512) { piece in
                DispatchQueue.main.async { onToken(piece) }
            } completion: { ok, err in
                DispatchQueue.main.async {
                    appLog("vision: eval tokens=\(ctx.lastVisionTokens) ok=\(ok)", source: "llama")
                    done(ok ? nil : (err ?? "vision failed"))
                }
            }
        }
    }

    func generate(messages: [OpenAIClient.Message],
                  sampling: AppSettings.Sampling,
                  onToken: @escaping (String) -> Void,
                  done: @escaping () -> Void) {
        guard let ctx = context else { return }
        let pairs: [[String]] = messages.map { [$0.role, $0.content] }
        guard let prompt = ctx.applyTemplate(pairs) else {
            done()
            return
        }

        // TTFT is measured to the first token the caller receives; the decode
        // rate runs from there to completion, so prompt processing does not
        // drag the number down. Same definitions as MLXEngine, so the two
        // backends report comparable figures.
        let started = DispatchTime.now()
        let lock = NSLock()
        var firstTokenAt: DispatchTime?
        var tokenCount = 0

        ctx.generate(prompt,
                     maxTokens: Int32(sampling.maxTokens),
                     temperature: sampling.temperature,
                     topP: sampling.topP,
                     topK: Int32(sampling.topK)) { piece in
            lock.lock()
            tokenCount += 1
            let now = DispatchTime.now()
            if firstTokenAt == nil { firstTokenAt = now }
            lock.unlock()
            onToken(piece)
        } completion: { _, _, _ in
            lock.lock()
            let count = tokenCount
            let first = firstTokenAt
            lock.unlock()

            let ended = DispatchTime.now()
            let ttft = first.map {
                Double($0.uptimeNanoseconds - started.uptimeNanoseconds) / 1_000_000_000
            } ?? 0
            let window = first.map {
                Double(ended.uptimeNanoseconds - $0.uptimeNanoseconds) / 1_000_000_000
            } ?? 0
            let rate = window > 0 ? Double(count) / window : 0

            DispatchQueue.main.async {
                self.lastTTFT = ttft
                self.lastTokensPerSecond = rate
                self.lastTokenCount = count
            }
            done()
        }
    }

    func stop() {
        context?.stop()
    }
}

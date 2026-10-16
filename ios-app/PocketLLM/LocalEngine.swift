import Foundation

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

    /// `gpuLayers` is 0 for CPU-only, `allLayers` for Metal. The bridge clamps it
    /// to what the build actually supports, so asking for Metal on the simulator
    /// (which has no Metal backend) silently yields a CPU load rather than a
    /// failure — the reported backend below is the truth.
    func load(url: URL, contextSize: Int32, threads: Int, gpuLayers: Int) {
        appLogMemory("pre-load", source: "llama")
        state = "loading…"
        supportsVision = false
        mmprojName = nil
        appLog("loading…", source: "llama")
        LlamaGlobalInit()
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
                    let ok = try ctx.loadVision(withMmprojPath: mmproj.path)
                    supportsVision = ok
                    mmprojName = mmproj.lastPathComponent
                    appLog(ok
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

    /// Finds the mmproj that ships beside a GGUF model.
    ///
    /// Matched on the filename because that is the only signal the filesystem
    /// offers — an mmproj carries no marker readable without loading it, and
    /// loading it is the expensive decision this lookup exists to inform.
    /// Returns nil for a plain text model, which is the common case.
    nonisolated static func companionMmproj(for modelURL: URL) -> URL? {
        let dir = modelURL.deletingLastPathComponent()
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil)) ?? []
        return entries.first { url in
            let n = url.lastPathComponent.lowercased()
            return n.hasSuffix(".gguf") && n.contains("mmproj")
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
        ctx.describeImage(image.path, prompt: prompt, maxTokens: 512) { piece in
            onToken(piece)
        } completion: { ok, err in
            done(ok ? nil : (err ?? "vision failed"))
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

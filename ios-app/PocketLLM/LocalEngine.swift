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
        state = "loading…"
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
        } catch {
            releaseSecurityScope()
            // Surface the real reason — "load failed" gave users nothing.
            state = "load failed: \(error.localizedDescription)"
        }
    }

    var isReady: Bool { context != nil }

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

import Foundation

/// On-device inference using the llama.cpp ObjC++ bridge.
final class LocalEngine: ObservableObject {
    @Published var state: String = "no model loaded"

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
        ctx.generate(prompt,
                     maxTokens: Int32(sampling.maxTokens),
                     temperature: sampling.temperature,
                     topP: sampling.topP,
                     topK: Int32(sampling.topK)) { piece in
            onToken(piece)
        } completion: { _, _, _ in
            done()
        }
    }

    func stop() {
        context?.stop()
    }
}

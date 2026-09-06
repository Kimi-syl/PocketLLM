import Foundation

/// On-device inference using the llama.cpp ObjC++ bridge.
final class LocalEngine: ObservableObject {
    @Published var state: String = "no model loaded"
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

    func load(url: URL, contextSize: Int32, threads: Int) {
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
                                       threads: Int32(threads))
            context = ctx
            state = "ready (ctx=\(ctx.contextLength))"
        } catch {
            releaseSecurityScope()
            // Surface the real reason — "load failed" gave users nothing.
            state = "load failed: \(error.localizedDescription)"
        }
    }

    var isReady: Bool { context != nil }

    func generate(messages: [OpenAIClient.Message],
                  onToken: @escaping (String) -> Void,
                  done: @escaping () -> Void) {
        guard let ctx = context else { return }
        let pairs: [[String]] = messages.map { [$0.role, $0.content] }
        guard let prompt = ctx.applyTemplate(pairs) else {
            done()
            return
        }
        var collected = ""
        ctx.generate(prompt, maxTokens: 1024, temperature: 0.8, topP: 0.95, topK: 40) { piece in
            collected += piece
            onToken(piece)
        } completion: { _, _, _ in
            _ = collected
            done()
        }
    }

    func stop() {
        context?.stop()
    }
}

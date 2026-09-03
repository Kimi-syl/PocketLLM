import Foundation

/// On-device inference using the llama.cpp ObjC++ bridge.
final class LocalEngine: ObservableObject {
    @Published var state: String = "no model loaded"
    private var context: LlamaContext?
    private var current: LlamaContext?

    func load(url: URL, contextSize: Int32, threads: Int) {
        state = "loading…"
        LlamaGlobalInit()
        let securityScoped = url.startAccessingSecurityScopedResource()
        defer { if securityScoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let ctx = try LlamaContext(modelPath: url.path,
                                       contextSize: contextSize,
                                       batchSize: 2048,
                                       threads: Int32(threads),
                                       error: nil)
            context = ctx
            current = ctx
            state = "ready (ctx=\(ctx.contextLength))"
        } catch {
            state = "load failed"
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

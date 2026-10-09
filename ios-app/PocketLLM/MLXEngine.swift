import Foundation
import Hub
import MLX
import MLXLLM
import MLXLMCommon
import Tokenizers
import MLXLMTokenizers

/// On-device inference through Apple's MLX framework.
///
/// The counterpart to `LocalEngine`: where llama.cpp reads a single GGUF file on
/// the CPU or Metal through ggml, MLX reads a converted model *directory*
/// (weights plus config and tokenizer) and runs it on the GPU with kernels Apple
/// tunes per chip. Models come from the `mlx-community` organisation on the Hub,
/// downloaded by the Models tab.
///
/// Deliberately the same shape as `LocalEngine` so the chat view can drive either
/// one without branching on the backend.
final class MLXEngine: ObservableObject {

    @Published var state: String = "no model loaded"

    /// Time to first token of the last generation, in seconds. 0 until one runs.
    @Published var lastTTFT: Double = 0

    /// Decode rate of the last generation, in tokens per second.
    @Published var lastTokensPerSecond: Double = 0

    /// Tokens emitted by the last generation.
    @Published var lastTokenCount: Int = 0

    private var container: ModelContainer?
    private let stopFlag = StopFlag()

    var isReady: Bool { container != nil }

    /// Loads an MLX model directory. Weights already on disk make this a load,
    /// not a download; the progress handler still reports the weight read.
    /// Qwen3.5 checkpoints whose `model_type` is newer than the vendored
    /// MLX-LM registry still share the Qwen3 architecture. Rewrite the config in
    /// a sibling directory and load that, so the model works instead of failing
    /// on a name the registry does not know yet.
    private func normalizedFallback(for directory: URL) -> URL? {
        let configURL = directory.appendingPathComponent("config.json")
        guard let data = try? Data(contentsOf: configURL),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let modelType = json["model_type"] as? String
        else { return nil }

        let lower = modelType.lowercased()
        guard lower.contains("qwen") else { return nil }

        // Qwen3.5 -> qwen3, qwen4 -> qwen3, etc. Only ever downwards to a known
        // ancestor, never to an unrelated family.
        // Map to a known ancestor. Qwen3.5/4 are Qwen3-family; any other
        // unrecognized type is mapped to "qwen3" only when the config actually
        // carries a Qwen-shaped text_config, otherwise we leave it alone rather
        // than guessing at an unrelated architecture.
        let ancestor: String
        if lower.contains("qwen3_5") || lower.contains("qwen3.5") || lower.contains("qwen4") {
            ancestor = "qwen3"
        } else if lower.contains("qwen") {
            ancestor = "qwen3"
        } else {
            return nil
        }

        let work = directory.appendingPathComponent(".mlx-normalized", isDirectory: true)
        try? FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)

        // Symlink the weights and tokenizer so we only duplicate the small files.
        let fm = FileManager.default
        for item in (try? fm.contentsOfDirectory(atPath: directory.path)) ?? [] {
            guard item != ".mlx-normalized", item != "config.json" else { continue }
            let src = directory.appendingPathComponent(item)
            let dst = work.appendingPathComponent(item)
            try? fm.createSymbolicLink(at: dst, withDestinationURL: src)
        }

        var mutable = json
        mutable["model_type"] = ancestor

        // Qwen-VL / Qwen3.5 nest the language model under `text_config`, so
        // `hidden_size` and friends are not at the top level where a qwen3
        // config is expected to find them. Flatten it, keeping any existing
        // top-level key authoritative.
        if let textConfig = json["text_config"] as? [String: Any] {
            for (key, value) in textConfig where mutable[key] == nil {
                mutable[key] = value
            }
        }
        guard let out = try? JSONSerialization.data(
            withJSONObject: mutable, options: [.prettyPrinted, .sortedKeys]
        ) else { return nil }
        try? out.write(to: work.appendingPathComponent("config.json"))

        // swift-transformers raises "TokenizersBackend is not supported" for a
        // tokenizer_class it does not implement (MiniCPM5 is one). Falling back
        // to the generic class lets the pure-Swift tokenizer load instead of
        // failing the entire model.
        let tokURL = work.appendingPathComponent("tokenizer_config.json")
        if let tokData = try? Data(contentsOf: tokURL),
           var tok = try? JSONSerialization.jsonObject(with: tokData) as? [String: Any],
           let tokClass = tok["tokenizer_class"] as? String {
            let known = ["PreTrainedTokenizer", "GPT2Tokenizer", "LlamaTokenizer",
                         "BertTokenizer", "Qwen2Tokenizer", "CodeGenTokenizer"]
            if !known.contains(tokClass) {
                tok["tokenizer_class"] = "PreTrainedTokenizer"
                if let rewritten = try? JSONSerialization.data(
                    withJSONObject: tok, options: [.prettyPrinted, .sortedKeys]) {
                    try? rewritten.write(to: tokURL)
                }
            }
        }
        return work
    }

    func load(directory: URL) async {
        setState("loading \(directory.lastPathComponent)…")

        // Normalize a Qwen3.5-style config BEFORE asking MLX-LM to load it.
        // Waiting for the load to fail meant the user still saw
        // "unknown model type: qwen3_5" even though we could retry, because the
        // failure is raised from the registry lookup. Going straight to the
        // ancestor config never raises it.
        let effective = normalizedFallback(for: directory) ?? directory
        if effective != directory {
            setState("loading \(directory.lastPathComponent)… (Qwen3-family config)")
        }

        do {
            let loaded = try await LLMModelFactory.shared.loadContainer(
                from: effective,
                using: TokenizersLoader()
            )
            container = loaded
            // MLX keeps a buffer cache for reuse between runs; a phone has far
            // less headroom than a Mac, so cap it rather than let it grow.
            GPU.set(cacheLimit: 256 * 1024 * 1024)
            setState("\(directory.lastPathComponent) · MLX")
        } catch {
            container = nil
            // A Qwen3.5 model_type the registry does not know is not fatal:
            // retry through a normalized config with the ancestor model_type.
            if let fallback = normalizedFallback(for: directory) {
                setState("retrying as Qwen3-family config…")
                do {
                    let retry = try await LLMModelFactory.shared.loadContainer(
                        from: fallback,
                        using: TokenizersLoader()
                    )
                    container = retry
                    setState("ready (\(directory.lastPathComponent))")
                    return
                } catch {
                    // fall through to the reported failure below
                }
            }
            setState("load failed: \(error.localizedDescription)")
        }
    }

    func generate(
        messages: [OpenAIClient.Message],
        sampling: AppSettings.Sampling,
        onToken: @escaping (String) -> Void,
        done: @escaping () -> Void
    ) {
        guard let container else {
            done()
            return
        }
        stopFlag.reset()

        let chat = messages.map { message -> Chat.Message in
            switch message.role {
            case "system": return .system(message.content)
            case "assistant": return .assistant(message.content)
            default: return .user(message.content)
            }
        }
        let flag = stopFlag

        Task.detached(priority: .userInitiated) {
            do {
                // The action is typed out rather than passed as a trailing
                // closure: ModelContainer.perform has a synchronous
                // (model, tokenizer) overload, and an async closure body can be
                // matched against either.
                let action: @Sendable (ModelContext) async throws -> Void = { context in
                    let input = try await context.processor.prepare(
                        input: UserInput(chat: chat))
                    // sampling.topK goes unused: MLXLMCommon's
                    // GenerateParameters has no top-k, only temperature and
                    // top-p, so that setting is llama.cpp-only by necessity.
                    let parameters = GenerateParameters(
                        maxTokens: sampling.maxTokens,
                        maxKVSize: sampling.maxKVSize,
                        temperature: sampling.temperature,
                        topP: sampling.topP)

                    // Token-by-token decoding of a BPE vocabulary produces
                    // spurious word breaks, so the detokenizer accumulates a
                    // segment and hands back only what is newly decodable.
                    var detokenizer = NaiveStreamingDetokenizer(tokenizer: context.tokenizer)

                    // TTFT is measured to the first DECODED token, which is what
                    // a person perceives as "it started answering". Decode rate
                    // is measured from there so prompt processing does not drag
                    // the number down.
                    let started = DispatchTime.now()
                    var firstTokenAt: DispatchTime?
                    var tokenCount = 0

                    // Qualified: this class has its own `generate`, which would
                    // otherwise win the unqualified name lookup.
                    _ = try MLXLMCommon.generate(
                        input: input, parameters: parameters, context: context
                    ) { token in
                        guard !flag.isStopped else { return .stop }
                        tokenCount += 1
                        let now = DispatchTime.now()
                        if firstTokenAt == nil { firstTokenAt = now }
                        detokenizer.append(token: token)
                        if let piece = detokenizer.next(), !piece.isEmpty {
                            DispatchQueue.main.async { onToken(piece) }
                        }
                        return .more
                    }

                    // Publish the numbers once the stream ends.
                    let ended = DispatchTime.now()
                    let ttft = firstTokenAt.map {
                        Double($0.uptimeNanoseconds - started.uptimeNanoseconds) / 1_000_000_000
                    } ?? 0
                    let decodeWindow = firstTokenAt.map {
                        Double(ended.uptimeNanoseconds - $0.uptimeNanoseconds) / 1_000_000_000
                    } ?? 0
                    let rate = decodeWindow > 0 ? Double(tokenCount) / decodeWindow : 0
                    DispatchQueue.main.async {
                        self.lastTTFT = ttft
                        self.lastTokensPerSecond = rate
                        self.lastTokenCount = tokenCount
                        self.state = String(
                            format: "done · %.2fs TTFT · %.1f tok/s · %d tokens",
                            ttft, rate, tokenCount)
                    }
                }
                try await container.perform(action)
                DispatchQueue.main.async { done() }
            } catch {
                DispatchQueue.main.async {
                    self.setState("generation failed: \(error.localizedDescription)")
                    done()
                }
            }
        }
    }

    func stop() {
        stopFlag.stop()
    }

    /// `@Published` mutations have to reach SwiftUI on the main thread, and this
    /// class is driven from whichever queue MLX or the caller happens to use.
    private func setState(_ value: String) {
        if Thread.isMainThread {
            state = value
        } else {
            DispatchQueue.main.async { self.state = value }
        }
    }
}

/// Cooperative cancellation for a generation loop.
///
/// `generate`'s visitor is called on MLX's own queue while `stop()` is called
/// from the main thread, so the flag needs its own lock.
private final class StopFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false

    func reset() {
        lock.lock()
        stopped = false
        lock.unlock()
    }

    func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
    }

    var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }
}

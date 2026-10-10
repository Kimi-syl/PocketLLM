import Foundation
import Hub
import MLX
import MLXLLM
import MLXLMCommon
import Tokenizers

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
/// Adapts swift-transformers' `Tokenizer` to `MLXLMCommon.Tokenizer`.
///
/// This is the expansion of mlx-swift-lm's `#adaptHuggingFaceTokenizer`, written
/// out because that macro lives in `MLXHuggingFaceMacros` (a target, not a
/// linkable product) and dependency macros must be explicitly enabled.
private struct TokenizerBridge: MLXLMCommon.Tokenizer {
    private let upstream: any Tokenizers.Tokenizer

    init(_ upstream: any Tokenizers.Tokenizer) {
        self.upstream = upstream
    }

    func encode(text: String, addSpecialTokens: Bool) -> [Int] {
        upstream.encode(text: text, addSpecialTokens: addSpecialTokens)
    }

    // swift-transformers spells this `decode(tokens:)`.
    func decode(tokenIds: [Int], skipSpecialTokens: Bool) -> String {
        upstream.decode(tokens: tokenIds, skipSpecialTokens: skipSpecialTokens)
    }

    func convertTokenToId(_ token: String) -> Int? { upstream.convertTokenToId(token) }
    func convertIdToToken(_ id: Int) -> String? { upstream.convertIdToToken(id) }

    var bosToken: String? { upstream.bosToken }
    var eosToken: String? { upstream.eosToken }
    var unknownToken: String? { upstream.unknownToken }

    func applyChatTemplate(
        messages: [[String: any Sendable]],
        tools: [[String: any Sendable]]?,
        additionalContext: [String: any Sendable]?
    ) throws -> [Int] {
        do {
            return try upstream.applyChatTemplate(
                messages: messages, tools: tools, additionalContext: additionalContext)
        } catch Tokenizers.TokenizerError.missingChatTemplate {
            throw MLXLMCommon.TokenizerError.missingChatTemplate
        }
    }
}

/// Loads a tokenizer from a model directory already on disk.
///
/// mlx-swift-lm 3.x takes a TokenizerLoader rather than building one itself.
private struct LocalTokenizerLoader: MLXLMCommon.TokenizerLoader {
    func load(from directory: URL) async throws -> any MLXLMCommon.Tokenizer {
        TokenizerBridge(try await Tokenizers.AutoTokenizer.from(modelFolder: directory))
    }
}

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
        func load(directory: URL) async {
        setState("loading \(directory.lastPathComponent)…")

        do {
            let loaded = try await LLMModelFactory.shared.loadContainer(
                from: directory,
                using: LocalTokenizerLoader()
            )
            container = loaded
            // MLX keeps a buffer cache for reuse between runs; a phone has far
            // less headroom than a Mac, so cap it rather than let it grow.
            GPU.set(cacheLimit: 256 * 1024 * 1024)
            setState("\(directory.lastPathComponent) · MLX")
        } catch {
            container = nil
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

import Combine
import Foundation

/// User-tunable inference, prompt and account settings, persisted in UserDefaults.
///
/// The iOS counterpart of Android's `AppSettings`, narrowed to the fields that
/// mean something here — the server port, HTTPS and agent-tool switches belong to
/// features this app does not run.
final class AppSettings: ObservableObject {

    static let shared = AppSettings()

    /// Context sizes offered as presets rather than a free number: the KV cache
    /// grows with the context, and on a phone that is the first thing to get the
    /// app killed for memory.
    static let contextChoices = [1024, 2048, 4096, 8192, 16384]

    @Published var contextSize: Int
    @Published var maxTokens: Int
    @Published var temperature: Double
    @Published var topP: Double
    @Published var topK: Int
    @Published var threads: Int
    @Published var gpuOffload: Bool
    @Published var systemPrompt: String
    @Published var hfToken: String
    @Published var serverURL: String

    /// Raw value of the chat tab's backend choice. Stored as a string so this
    /// file does not depend on a type declared inside a view.
    @Published var lastMode: String

    /// Sampling parameters, frozen into a value type.
    ///
    /// Generation runs inside `@Sendable` closures on MLX's and llama.cpp's own
    /// queues; handing them a plain struct avoids capturing this reference type
    /// across a concurrency domain, and means a settings edit mid-generation
    /// cannot change the run in flight.
    struct Sampling: Sendable {
        let maxTokens: Int
        let maxKVSize: Int
        let temperature: Float
        let topP: Float
        let topK: Int32
    }

    var sampling: Sampling {
        Sampling(
            maxTokens: maxTokens,
            maxKVSize: contextSize,
            temperature: Float(temperature),
            topP: Float(topP),
            topK: Int32(topK))
    }

    var trimmedSystemPrompt: String {
        systemPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private let defaults: UserDefaults
    private var cancellables: Set<AnyCancellable> = []

    private enum Key {
        static let contextSize = "settings.contextSize"
        static let maxTokens = "settings.maxTokens"
        static let temperature = "settings.temperature"
        static let topP = "settings.topP"
        static let topK = "settings.topK"
        static let threads = "settings.threads"
        static let gpuOffload = "settings.gpuOffload"
        static let systemPrompt = "settings.systemPrompt"
        static let hfToken = "settings.hfToken"
        static let serverURL = "settings.serverURL"
        static let lastMode = "settings.lastMode"
    }

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        // `object(forKey:)` rather than `integer(forKey:)`: the latter cannot tell
        // "never set" from "set to 0", and 0 is not a sane default for any of these.
        contextSize = defaults.object(forKey: Key.contextSize) as? Int ?? 4096
        maxTokens = defaults.object(forKey: Key.maxTokens) as? Int ?? 1024
        temperature = defaults.object(forKey: Key.temperature) as? Double ?? 0.8
        topP = defaults.object(forKey: Key.topP) as? Double ?? 0.95
        topK = defaults.object(forKey: Key.topK) as? Int ?? 40
        threads = defaults.object(forKey: Key.threads) as? Int ?? 4
        gpuOffload = defaults.object(forKey: Key.gpuOffload) as? Bool ?? true
        systemPrompt = defaults.string(forKey: Key.systemPrompt) ?? ""
        hfToken = defaults.string(forKey: Key.hfToken) ?? ""
        serverURL = defaults.string(forKey: Key.serverURL) ?? "http://192.168.1.100:8080/"
        lastMode = defaults.string(forKey: Key.lastMode) ?? "remote"

        // Persistence through subscriptions rather than `didSet`: observers on
        // property-wrapped properties are easy to get subtly wrong, and a
        // publisher per field is unambiguous.
        persist($contextSize, to: Key.contextSize)
        persist($maxTokens, to: Key.maxTokens)
        persist($temperature, to: Key.temperature)
        persist($topP, to: Key.topP)
        persist($topK, to: Key.topK)
        persist($threads, to: Key.threads)
        persist($gpuOffload, to: Key.gpuOffload)
        persist($systemPrompt, to: Key.systemPrompt)
        persist($hfToken, to: Key.hfToken)
        persist($serverURL, to: Key.serverURL)
        persist($lastMode, to: Key.lastMode)
    }

    private func persist<V>(_ publisher: Published<V>.Publisher, to key: String) {
        // `defaults` is captured by value so the subscription does not keep this
        // object alive through its own publisher.
        publisher
            .sink { [defaults] value in
                defaults.set(value, forKey: key)
            }
            .store(in: &cancellables)
    }
}

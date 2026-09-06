import Foundation

/// Minimal OpenAI-compatible client. Point it at the PocketLLM Android app
/// or the desktop CLI server (`pocketllm serve`) running on the same network.
struct OpenAIClient {
    var baseURL: URL

    struct Message: Codable, Identifiable {
        var id = UUID()
        var role: String
        var content: String
    }

    private struct Request: Codable {
        var model = "pocketllm"
        var messages: [Msg]
        struct Msg: Codable { var role: String; var content: String }
    }

    private struct Response: Codable {
        struct Choice: Codable {
            struct Msg: Codable { var content: String? }
            var message: Msg
        }
        var choices: [Choice]
    }

    func complete(messages: [Message]) async throws -> String {
        var url = baseURL
        url.append(path: "v1/chat/completions")
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 300
        let body = Request(
            messages: messages.map { .init(role: $0.role, content: $0.content) }
        )
        req.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await URLSession.shared.data(for: req)
        // 4xx/5xx bodies are error JSON, not completions — decoding them as a
        // Response produced a baffling parse error before.
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            let body = String(data: data, encoding: .utf8)?.prefix(300) ?? ""
            throw NSError(domain: "OpenAIClient", code: http.statusCode,
                          userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode): \(body)"])
        }
        let decoded = try JSONDecoder().decode(Response.self, from: data)
        return decoded.choices.first?.message.content ?? ""
    }
}

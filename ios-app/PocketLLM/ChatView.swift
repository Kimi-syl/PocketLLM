import MarkdownUI
import SwiftUI
import UniformTypeIdentifiers

struct ChatView: View {
    enum Mode: String, CaseIterable { case local = "On-device", remote = "Remote server" }
    @State private var mode: Mode = .remote
    @State private var serverURL = "http://192.168.1.100:8080/"
    @State private var input = ""
    @State private var messages: [OpenAIClient.Message] = []
    @State private var busy = false
    @State private var error: String?
    @State private var engine = LocalEngine()
    @State private var showModelPicker = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("Mode", selection: $mode) {
                ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            if mode == .remote {
            TextField("Server URL", text: $serverURL)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            } else {
                HStack {
                    Text(engine.state).font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                    Button("Pick GGUF…") { showModelPicker = true }
                }
                .padding(.horizontal)
            }
            ScrollView {
                ForEach(messages) { m in
                    messageRow(m)
                }
            }
            if let error { Text(error).foregroundStyle(.red).font(.footnote) }
            HStack {
                TextField("Message", text: $input)
                    .textFieldStyle(.roundedBorder)
                if mode == .local && busy {
                    Button("Stop") { engine.stop() }
                }
                Button(busy ? "..." : "Send") { send() }
                    .disabled(input.isEmpty || busy)
            }
            .padding()
        }
        .navigationTitle("PocketLLM")
        .fileImporter(isPresented: $showModelPicker, allowedContentTypes: [.data]) { result in
            if case .success(let url) = result {
                engine.load(url: url, contextSize: 4096, threads: 4)
            }
        }
    }

    @ViewBuilder
    private func messageRow(_ message: OpenAIClient.Message) -> some View {
        let isUser = message.role == "user"
        HStack(alignment: .top) {
            if isUser { Spacer(minLength: 40) }
            Group {
                if isUser || isStreaming(message) {
                    Text(message.content)
                } else {
                    Markdown(message.content)
                        .markdownTheme(.chat)
                }
            }
            .textSelection(.enabled)
            .padding(10)
            .background(isUser ? Color.accentColor.opacity(0.25) : Color(.systemGray5))
            .cornerRadius(12)
            if !isUser { Spacer(minLength: 40) }
        }
        .padding(.horizontal)
    }

    /// MarkdownUI re-parses the entire document on every update, so rendering
    /// markdown while tokens stream in would re-parse the whole reply dozens of
    /// times a second. The in-flight message stays plain text until it settles.
    private func isStreaming(_ message: OpenAIClient.Message) -> Bool {
        busy && message.id == messages.last?.id
    }

    private func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy else { return }
        input = ""
        let userMsg = OpenAIClient.Message(role: "user", content: text)
        let assistantMsg = OpenAIClient.Message(role: "assistant", content: "")
        messages.append(userMsg)
        messages.append(assistantMsg)
        busy = true
        error = nil
        let history = messages.dropLast()

        if mode == .local {
            guard engine.isReady else {
                error = "load a GGUF model first"
                busy = false
                return
            }
            let idx = messages.count - 1
            engine.generate(messages: Array(history), onToken: { piece in
                DispatchQueue.main.async {
                    // Guard: a stop+send between turns can shrink/reindex the
                    // array; appending to a stale index would crash.
                    if idx < messages.count { messages[idx].content += piece }
                }
            }, done: {
                DispatchQueue.main.async { busy = false }
            })
        } else {
            guard let url = URL(string: serverURL) else {
                error = "invalid server URL"
                busy = false
                return
            }
            let client = OpenAIClient(baseURL: url)
            Task {
                do {
                    let reply = try await client.complete(messages: Array(history))
                    let idx = messages.count - 1
                    messages[idx].content = reply
                } catch {
                    self.error = error.localizedDescription
                }
                busy = false
            }
        }
    }
}

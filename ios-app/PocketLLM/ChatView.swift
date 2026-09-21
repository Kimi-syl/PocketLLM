import MarkdownUI
import SwiftUI
import UniformTypeIdentifiers

struct ChatView: View {
    enum Mode: String, CaseIterable {
        case local = "llama.cpp"
        case mlx = "MLX"
        case remote = "Server"
    }
    @State private var mode: Mode = .remote
    @State private var serverURL = "http://192.168.1.100:8080/"
    @State private var input = ""
    @State private var messages: [OpenAIClient.Message] = []
    @State private var busy = false
    @State private var error: String?
    // StateObject, not State: both engines are ObservableObjects whose @Published
    // status the header shows, and @State would never re-render on a change.
    @StateObject private var engine = LocalEngine()
    @StateObject private var mlx = MLXEngine()
    @State private var showModelPicker = false
    @EnvironmentObject var store: ModelStore

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
                engineBar
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
                if busy && mode != .remote {
                    Button("Stop") {
                        if mode == .mlx { mlx.stop() } else { engine.stop() }
                    }
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

    /// Status line and model picker for whichever on-device backend is selected.
    /// Both read the same library, filtered to the format that backend loads.
    private var engineBar: some View {
        HStack {
            Text(mode == .mlx ? mlx.state : engine.state)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            modelMenu
        }
        .padding(.horizontal)
    }

    /// Downloaded models, plus the file importer for anything the library does
    /// not hold yet. Downloads are shared with the Models tab through the
    /// injected store.
    private var ggufModels: [ModelStore.InstalledModel] {
        store.installed.filter { $0.kind == .gguf }
    }

    private var installedModels: [ModelStore.InstalledModel] {
        store.installed.filter { mode == .mlx ? $0.kind == .mlx : $0.kind == .gguf }
    }

    private var modelMenu: some View {
        Menu {
            ForEach(installedModels) { model in
                Button(model.name) { load(model) }
            }
            if mode == .local {
                if !ggufModels.isEmpty {
                    Divider()
                }
                Button("Import file…") { showModelPicker = true }
            } else if installedModels.isEmpty {
                // Nothing to load yet, and a menu with no items is invisible.
                Button("Download models in the Models tab") {}
                    .disabled(true)
            }
        } label: {
            Label("Model", systemImage: "cube.box").font(.footnote)
        }
    }

    private func load(_ model: ModelStore.InstalledModel) {
        if mode == .mlx {
            // An MLX model is a directory of weights, config and tokenizer.
            Task { await mlx.load(directory: model.url) }
        } else {
            engine.load(url: model.url, contextSize: 4096, threads: 4)
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

        if mode == .local || mode == .mlx {
            if mode == .mlx {
                guard mlx.isReady else {
                    error = "load an MLX model first"
                    busy = false
                    return
                }
            } else {
                guard engine.isReady else {
                    error = "load a GGUF model first"
                    busy = false
                    return
                }
            }
            let idx = messages.count - 1
            let onToken: (String) -> Void = { piece in
                DispatchQueue.main.async {
                    // Guard: a stop+send between turns can shrink/reindex the
                    // array; appending to a stale index would crash.
                    if idx < messages.count { messages[idx].content += piece }
                }
            }
            let onDone: () -> Void = {
                DispatchQueue.main.async { busy = false }
            }
            if mode == .mlx {
                mlx.generate(messages: Array(history), onToken: onToken, done: onDone)
            } else {
                engine.generate(messages: Array(history), onToken: onToken, done: onDone)
            }
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

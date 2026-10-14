import MarkdownUI
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ChatView: View {
    enum Mode: String, CaseIterable {
        case local = "llama.cpp"
        case mlx = "MLX"
        case remote = "Server"
    }

    @EnvironmentObject var store: ModelStore
    @EnvironmentObject var settings: AppSettings
    @State private var input = ""
    // --- photo attachment -------------------------------------------------
    @StateObject private var vlm = VLMEngine()
    @State private var attachItem: PhotosPickerItem?
    @State private var showDetails = false
    @State private var loadedModel: ModelStore.InstalledModel?
    @State private var attachedImage: URL?
    @State private var attachedPreview: UIImage?
    @State private var pickingVLM = false
    @State private var messages: [OpenAIClient.Message] = []
    @State private var busy = false
    @State private var error: String?
    // StateObject, not State: both engines are ObservableObjects whose @Published
    // status the header shows, and @State would never re-render on a change.
    @StateObject private var engine = LocalEngine()
    @StateObject private var mlx = MLXEngine()
    @State private var showModelPicker = false

    /// The backend choice persists across launches, but as a raw string in
    /// AppSettings so that file never has to know about a view's enum.
    private var currentMode: Mode {
        Mode(rawValue: settings.lastMode) ?? .remote
    }

    private var modeBinding: Binding<Mode> {
        Binding(
            get: { Mode(rawValue: settings.lastMode) ?? .remote },
            set: { settings.lastMode = $0.rawValue }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("Mode", selection: modeBinding) {
                ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            if currentMode == .remote {
                TextField("Server URL", text: $settings.serverURL)
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
                // Only offered when the loaded model actually declares vision
                // support, so the button never promises what the model cannot do.
                if loadedSupportsVision {
                    PhotosPicker(selection: $attachItem, matching: .images) {
                        Image(systemName: attachedImage == nil ? "photo" : "photo.fill")
                            .foregroundStyle(attachedImage == nil ? Color.secondary : Color.accentColor)
                    }
                    if let attachedPreview {
                        Image(uiImage: attachedPreview)
                            .resizable().scaledToFill()
                            .frame(width: 28, height: 28)
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                }
                TextField("Message", text: $input)
                    .textFieldStyle(.roundedBorder)
                if busy && currentMode != .remote {
                    Button("Stop") {
                        if currentMode == .mlx { mlx.stop() } else { engine.stop() }
                    }
                }
                Button(busy ? "..." : "Send") { send() }
                    .disabled(input.isEmpty || busy)
            }
            .padding()
        }
            .onChange(of: attachItem) { _, item in
                guard let item else { return }
                Task { await attachPhoto(item) }
            }
        .navigationTitle("PocketLLM")
        .sheet(isPresented: $showDetails) {
            ModelDetailsView(modelName: loadedModel?.name ?? "未載入",
                             modelURL: loadedModel?.url,
                             isMLX: currentMode == .mlx)
        }
        .fileImporter(isPresented: $showModelPicker, allowedContentTypes: [.data]) { result in
            if case .success(let url) = result {
                loadGGUF(url)
            }
        }
    }

    /// Status line and model picker for whichever on-device backend is selected.
    /// Both read the same library, filtered to the format that backend loads.
    private var engineBar: some View {
        HStack {
            Text(currentMode == .mlx ? mlx.state : engine.state)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            // Throughput for the last MLX generation. A single Text rather
            // than an HStack of Labels: the Label form failed type inference
            // here ('type Any has no member foregroundStyle').
            if currentMode == .mlx && mlx.lastTokenCount > 0 {
                Text(
                    String(
                        format: "%.2fs TTFT  .  %.1f tok/s  .  %d tok",
                        mlx.lastTTFT, mlx.lastTokensPerSecond, mlx.lastTokenCount)
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            Spacer()
            Button { showDetails = true } label: {
                Image(systemName: "info.circle").font(.footnote)
            }
            .buttonStyle(.plain)
            .disabled(loadedModel == nil)
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
        store.installed.filter { currentMode == .mlx ? $0.kind == .mlx : $0.kind == .gguf }
    }

    private var modelMenu: some View {
        Menu {
            ForEach(installedModels) { model in
                Button(model.name) { load(model) }
            }
            if currentMode == .local {
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
        if currentMode == .mlx {
            // An MLX model is a directory of weights, config and tokenizer.
            loadedModel = model
            // Auto-detect: a vision-capable MLX model is also handed to the VLM
            // engine, so attaching a photo just works without a second step.
            if model.kind == .mlx && ModelInspector.inspect(directory: model.url).isVision {
                Task { await vlm.load(directory: model.url) }
            }
            Task { await mlx.load(directory: model.url) }
        } else {
            // Recorded for both backends: the details panel is about the model,
            // and a GGUF with no vision still has settings worth showing.
            loadedModel = model
            loadGGUF(model.url)
        }
    }

    private func loadGGUF(_ url: URL) {
        engine.load(
            url: url,
            contextSize: Int32(settings.contextSize),
            threads: settings.threads,
            gpuLayers: settings.gpuOffload ? LocalEngine.allLayers : 0)
    }

    /// Throughput line for whichever backend produced the reply, so llama.cpp
    /// and MLX report identically and neither can be forgotten.
    private var metricsLine: String {
        let ttft: Double, rate: Double, count: Int
        if currentMode == .mlx {
            (ttft, rate, count) = (mlx.lastTTFT, mlx.lastTokensPerSecond, mlx.lastTokenCount)
        } else {
            (ttft, rate, count) = (engine.lastTTFT, engine.lastTokensPerSecond, engine.lastTokenCount)
        }
        guard count > 0 else { return "" }
        return String(format: "%.2fs TTFT  .  %.1f tok/s  .  %d tok", ttft, rate, count)
    }

    @ViewBuilder
    private func messageRow(_ message: OpenAIClient.Message) -> some View {
        let isUser = message.role == "user"
        VStack(alignment: isUser ? .trailing : .leading, spacing: 4) {
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
        // Throughput belongs under the reply, not in the status bar. Only the
        // most recent assistant message shows it, so history stays clean.
        if !isUser,
           !metricsLine.isEmpty,
           let last = messages.last(where: { $0.role != "user" }),
           last.id == message.id {
            Text(metricsLine)
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12)
        }
        }
    }

    /// MarkdownUI re-parses the entire document on every update, so rendering
    /// markdown while tokens stream in would re-parse the whole reply dozens of
    /// times a second. The in-flight message stays plain text until it settles.
    private func isStreaming(_ message: OpenAIClient.Message) -> Bool {
        busy && message.id == messages.last?.id
    }

    /// The transcript plus the configured system prompt, in the form all three
    /// backends take. The prompt is prepended per request rather than stored in
    /// `messages`, so it never appears in the scroll view and editing it in
    /// Settings takes effect on the next reply without a new chat.
    private func requestHistory() -> [OpenAIClient.Message] {
        let history = Array(messages.dropLast())
        let prompt = settings.trimmedSystemPrompt
        guard !prompt.isEmpty else { return history }
        return [OpenAIClient.Message(role: "system", content: prompt)] + history
    }

    /// PhotosPicker yields an item, not a path, so persist the decoded image.
    ///
    /// The bytes must be RE-ENCODED, not written through: the picker hands back
    /// the asset in its original format (HEIC on iOS, sometimes JPEG), and
    /// writing those bytes under a `photo-….png` name made both engines fail —
    /// llama.cpp's mtmd produced no bitmap and MLX failed immediately, from one
    /// shared cause. Encoding from the decoded image guarantees a real file in a
    /// format both of them read.
    private func attachPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let ui = UIImage(data: data) else {
            appLog("attach: could not decode the picked item", source: "app")
            return
        }
        // A 48MP asset is ~190MB once decoded and both engines downscale it
        // anyway, so cap the long side before it ever reaches them.
        let scaled = ui.scaled(maxDimension: 1280)
        guard let jpeg = scaled.jpegData(compressionQuality: 0.9) else {
            appLog("attach: could not re-encode the image", source: "app")
            return
        }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("photo-\(Int(Date().timeIntervalSince1970)).jpg")
        do {
            try jpeg.write(to: url)
        } catch {
            appLog("attach: write failed — \(error.localizedDescription)", source: "app")
            return
        }
        appLog(String(format: "attach: %dx%d jpeg %d bytes -> %@",
                      Int(scaled.size.width), Int(scaled.size.height),
                      jpeg.count, url.lastPathComponent), source: "app")
        attachedPreview = scaled
        attachedImage = url
    }


    /// Whether the loaded model declares vision support, read from its own
    /// metadata. MLX VLMs carry vision keys in config.json; a GGUF needs a
    /// companion mmproj beside it.
    private var loadedSupportsVision: Bool {
        guard let m = loadedModel else { return false }
        if m.kind == .mlx {
            return ModelInspector.inspect(directory: m.url).isVision
        }
        return ModelInspector.companionProjection(for: m.url) != nil
    }

    private func send() {
        if let image = attachedImage, vlm.isReady {
            messages.append(.init(role: "user", content: input.isEmpty ? "[照片]" : input))
            let asked = input.isEmpty ? vlm.prompt : input
            input = ""
            attachedImage = nil
            attachedPreview = nil
            attachItem = nil
            vlm.prompt = asked
            vlm.analyze(image: image)
            let waited = Task { @MainActor in
                while vlm.isBusy { try? await Task.sleep(for: .milliseconds(250)) }
                if !vlm.answer.isEmpty {
                    messages.append(.init(role: "assistant", content: vlm.answer))
                }
            }
            _ = waited
            return
        }
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy else { return }
        input = ""
        let userMsg = OpenAIClient.Message(role: "user", content: text)
        let assistantMsg = OpenAIClient.Message(role: "assistant", content: "")
        messages.append(userMsg)
        messages.append(assistantMsg)
        busy = true
        error = nil
        let history = requestHistory()

        if currentMode == .local || currentMode == .mlx {
            if currentMode == .mlx {
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
            if currentMode == .mlx {
                mlx.generate(messages: history, sampling: settings.sampling,
                             onToken: onToken, done: onDone)
            } else {
                engine.generate(messages: history, sampling: settings.sampling,
                                onToken: onToken, done: onDone)
            }
        } else {
            guard let url = URL(string: settings.serverURL) else {
                error = "invalid server URL"
                busy = false
                return
            }
            let client = OpenAIClient(baseURL: url)
            Task {
                do {
                    let reply = try await client.complete(messages: history)
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

extension UIImage {
    /// A copy whose longest side is at most `maxDimension`.
    func scaled(maxDimension: CGFloat) -> UIImage {
        let longest = max(size.width, size.height)
        guard longest > maxDimension, longest > 0 else { return self }
        let factor = maxDimension / longest
        let target = CGSize(width: (size.width * factor).rounded(),
                            height: (size.height * factor).rounded())
        return UIGraphicsImageRenderer(size: target).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
    }
}

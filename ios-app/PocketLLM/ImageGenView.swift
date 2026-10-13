import SwiftUI
import UniformTypeIdentifiers

/// Image generation UI: the four model slots, prompt, parameters and results.
///
/// The slots are explicit rather than a single "pick a model" control because
/// the checkpoint format genuinely has four parts — text encoder, main model,
/// VAE and optional LoRAs — and the engine needs each in its own field.
struct ImageGenView: View {
    @StateObject private var engine = SdEngine()
    @State private var picking: Slot?
    @State private var isPickerPresented = false
    @State private var importNote: String?

    private enum Slot: String, Identifiable {
        case textEncoder, mainModel, vae, lora
        var id: String { rawValue }
        var title: String {
            switch self {
            case .textEncoder: return "文字編碼器 (llm_path)"
            case .mainModel:   return "主要模型 (diffusion)"
            case .vae:         return "VAE"
            case .lora:        return "LoRA"
            }
        }
        var hint: String {
            switch self {
            case .textEncoder: return "Qwen-Image 用 Qwen2.5-VL；SD1.5/SDXL 改用下方 CLIP/T5 欄位"
            case .mainModel:   return "Qwen / Flux / SD3 的 transformer"
            case .vae:         return "獨立的 VAE 權重"
            case .lora:        return "path|強度，多個以分號分隔"
            }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    slotsCard
                    promptCard
                    paramsCard
                    generateButton
                    resultSection
                }
                .padding()
            }
            .navigationTitle("圖片生成")
            // isPresented is its own state, NOT derived from `picking`. Deriving
            // it meant SwiftUI cleared `picking` as the sheet dismissed, so the
            // completion below read nil and silently assigned nothing.
            .fileImporter(
                isPresented: $isPickerPresented,
                allowedContentTypes: [.data, .folder],
                allowsMultipleSelection: false
            ) { result in
                // Read the slot BEFORE clearing it.
                let slot = picking
                picking = nil
                importNote = nil
                guard let slot, case .success(let urls) = result, let url = urls.first else { return }
                switch importIntoContainer(url) {
                case .success(let local):
                    assign(local.path, to: slot)
                    importNote = "已匯入：\(local.lastPathComponent)"
                case .failure(let err):
                    importNote = "匯入失敗：\(err.localizedDescription)"
                }
            }
        }
    }

    /// Copies a picked file (or diffusers folder) into the app container.
    ///
    /// A `.fileImporter` URL is security-scoped and only readable while that
    /// scope is held, but the engine reads the model much later, at load time.
    /// Copying gives a path that is permanently readable. A path already inside
    /// our own container is used as-is, so re-picking a model never duplicates it.
    private func importIntoContainer(_ url: URL) -> Result<URL, Error> {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if url.path.hasPrefix(docs.path) { return .success(url) }

        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let dir = docs.appendingPathComponent("Models", isDirectory: true)
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let dest = dir.appendingPathComponent(url.lastPathComponent)
            if fm.fileExists(atPath: dest.path) { return .success(dest) }
            try fm.copyItem(at: url, to: dest)
            return .success(dest)
        } catch {
            return .failure(error)
        }
    }

    private func assign(_ path: String, to slot: Slot) {
        switch slot {
        case .textEncoder: engine.textEncoder = path
        case .mainModel:   engine.mainModel = path
        case .vae:         engine.vae = path
        case .lora:
            // Append as path|<current strength> so several can accumulate.
            let entry = "\(path)|1.0"
            engine.loras = engine.loras.isEmpty ? entry : engine.loras + ";" + entry
        }
    }

    private var slotsCard: some View {
        GroupBox("模型元件") {
            VStack(alignment: .leading, spacing: 10) {
                Text("支援 .gguf 與 .safetensors（也接受 diffusers 資料夾）")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                slotRow(.textEncoder, value: engine.textEncoder, editable: true)
                slotRow(.mainModel, value: engine.mainModel, editable: true)
                slotRow(.vae, value: engine.vae, editable: true)
                slotRow(.lora, value: engine.loras, editable: true)
                if let note = importNote {
                    Text(note).font(.caption2)
                        .foregroundStyle(note.hasPrefix("已匯入") ? .green : .red)
                }
                HStack {
                    Button("載入模型") { engine.load() }
                        .buttonStyle(.borderedProminent)
                    Button("卸載") { engine.unload() }
                        .disabled(!engine.isReady)
                    Spacer()
                    Text(engine.isReady ? "已載入" : "未載入")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func slotRow(_ slot: Slot, value: String, editable: Bool) -> some View {
        let firstPath = value.split(separator: "|").first.map(String.init) ?? value
        let readable = !value.isEmpty && FileManager.default.isReadableFile(atPath: firstPath)
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(slot.title).font(.callout)
                Spacer()
                Button("選擇…") { picking = slot; isPickerPresented = true }
                    .font(.callout)
            }
            HStack(spacing: 4) {
                if !value.isEmpty {
                    Image(systemName: readable ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.caption2)
                        .foregroundStyle(readable ? .green : .orange)
                }
                Text(value.isEmpty
                     ? slot.hint
                     : URL(fileURLWithPath: firstPath).lastPathComponent)
                    .font(.caption)
                    .foregroundStyle(value.isEmpty ? .secondary : .primary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
    }

    private var promptCard: some View {
        GroupBox("提示詞") {
            VStack(alignment: .leading, spacing: 8) {
                TextField("描述想要的畫面", text: $engine.prompt, axis: .vertical)
                    .lineLimit(2...5)
                TextField("負向提示詞（可留空）", text: $engine.negative, axis: .vertical)
                    .lineLimit(1...3)
            }
        }
    }

    private var paramsCard: some View {
        GroupBox("參數") {
            VStack(alignment: .leading, spacing: 8) {
                stepper("寬", value: $engine.width, range: 256...1024, step: 64)
                stepper("高", value: $engine.height, range: 256...1024, step: 64)
                stepper("步數", value: $engine.steps, range: 1...60, step: 1)
                stepper("批次", value: $engine.batch, range: 1...4, step: 1)
                HStack {
                    Text("CFG")
                    Slider(value: $engine.cfg, in: 1...20)
                    Text(String(format: "%.1f", engine.cfg)).monospacedDigit()
                }
                HStack {
                    Text("Seed")
                    TextField("−1 為隨機", value: $engine.seed, format: .number)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                }
            }
        }
    }

    private func stepper(_ label: String, value: Binding<Int>,
                         range: ClosedRange<Int>, step: Int) -> some View {
        Stepper("\(label)：\(value.wrappedValue)", value: value, in: range, step: step)
    }

    private var generateButton: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Button("生成圖片") { engine.generate() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!engine.isReady)
                Button("取消") { engine.cancel() }
                    .disabled(!isRunning)
            }
            switch engine.phase {
            case .loading:
                Text("載入中…").font(.caption).foregroundStyle(.secondary)
            case .running(let step, let steps):
                ProgressView(value: Double(step), total: Double(max(steps, 1)))
                Text("\(step)/\(steps)").font(.caption).monospacedDigit()
            case .failed(let msg):
                Text(msg).font(.caption).foregroundStyle(.red)
            case .idle:
                EmptyView()
            }
        }
    }

    private var isRunning: Bool {
        if case .running = engine.phase { return true }
        return false
    }

    private var resultSection: some View {
        Group {
            if !engine.images.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("結果").font(.headline)
                    ForEach(Array(engine.images.enumerated()), id: \.offset) { _, img in
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .contextMenu {
                                ShareLink(item: Image(uiImage: img),
                                          preview: SharePreview("generated", image: Image(uiImage: img)))
                            }
                    }
                }
            }
        }
    }
}

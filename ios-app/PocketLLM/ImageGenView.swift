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
            .fileImporter(
                isPresented: Binding(get: { picking != nil },
                                     set: { if !$0 { picking = nil } }),
                allowedContentTypes: [.data],
                allowsMultipleSelection: false
            ) { result in
                defer { picking = nil }
                guard let slot = picking,
                      case .success(let urls) = result,
                      let url = urls.first else { return }
                // The picker hands back a security-scoped URL; the model is read
                // later, so the scope has to stay open rather than be dropped here.
                _ = url.startAccessingSecurityScopedResource()
                assign(url.path, to: slot)
            }
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
                slotRow(.textEncoder, value: engine.textEncoder, editable: true)
                slotRow(.mainModel, value: engine.mainModel, editable: true)
                slotRow(.vae, value: engine.vae, editable: true)
                slotRow(.lora, value: engine.loras, editable: true)
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
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(slot.title).font(.callout)
                Spacer()
                Button("選擇…") { picking = slot }
                    .font(.callout)
            }
            Text(value.isEmpty ? slot.hint
                               : URL(fileURLWithPath: value.split(separator: "|").first.map(String.init) ?? value)
                                    .lastPathComponent)
                .font(.caption)
                .foregroundStyle(value.isEmpty ? .secondary : .primary)
                .lineLimit(1)
                .truncationMode(.middle)
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

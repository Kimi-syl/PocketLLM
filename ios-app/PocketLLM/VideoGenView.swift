import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Video generation, kept deliberately apart from the Images tab.
///
/// Separate engine, separate slots, separate defaults. The two have nothing to
/// share: a video checkpoint is refused by the image path, its decode goes
/// through a tiny decoder rather than the VAE (or MLX), its sampler settings are
/// the distilled ones (4-8 steps, CFG 1), and it costs minutes and most of the
/// device's memory per run. Mixing them in one screen made both worse.
struct VideoGenView: View {
    @StateObject private var engine = SdEngine()
    @State private var picking: Slot?
    @State private var isPickerPresented = false
    @State private var photoItem: PhotosPickerItem?
    @State private var downloadNote: String?
    /// Progress per file id while the bundle downloads, and the fraction of the
    /// whole set (bytes are uneven, so it is weighted by the stated sizes).
    @State private var fileProgress: [String: Double] = [:]
    @State private var isDownloading = false
    @State private var elapsed: String = ""

    private enum Slot: String, Identifiable {
        case main, encoder, decoder
        var id: String { rawValue }
        var title: String {
            switch self {
            case .main:    return "主模型（DiT, Q4_K_S）"
            case .encoder: return "文字編碼器（umt5-xxl, T5 槽）"
            case .decoder: return "微型解碼器（taew2_2, taesd 槽）"
            }
        }
        var hint: String {
            switch self {
            case .main:    return "Wan2_2-TI2V-5B-Turbo-Q4_K_S.gguf"
            case .encoder: return "umt5-xxl-encoder-Q3_K_S.gguf"
            case .decoder: return "taew2_2.safetensors；沒有它解碼會是十幾分鐘的 GPU 鏈"
            }
        }
    }

    /// Named sizes first: Wan 2.2 TI2V is trained at 480p and degrades quickly
    /// off-distribution, so the native pair leads and everything else below it.
    private static let sizes: [(label: String, w: Int, h: Int)] = [
        ("480 × 832（原生直式）", 480, 832),
        ("832 × 480（原生橫式）", 832, 480),
        ("512 × 512", 512, 512),
        ("624 × 624", 624, 624),
        ("384 × 672", 384, 672),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    downloadCard
                    modelCard
                    promptCard
                    paramsCard
                    sourceCard
                    generateButton
                    resultSection
                }
                .padding()
            }
            .navigationTitle("影片")
            .fileImporter(isPresented: $isPickerPresented,
                          allowedContentTypes: [.data, .folder],
                          allowsMultipleSelection: false) { result in
                let slot = picking
                picking = nil
                guard let slot, case .success(let urls) = result, let url = urls.first else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                assign(url.path, to: slot)
            }
        }
        .onChange(of: engine.phase) { _, phase in
            if case .running = phase { startElapsed() } else { elapsed = "" }
        }
        .onAppear { adoptBundleDefaults() }
    }

    // MARK: - Download

    /// One click for the whole set. The three files are downloaded in order and
    /// the slots are filled as each lands, so a failure halfway still leaves a
    /// usable partial setup rather than nothing.
    private var downloadCard: some View {
        GroupBox("模型（一鍵下載）") {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(WanBundle.files) { file in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(file.file).font(.caption).lineLimit(1).truncationMode(.middle)
                            Text("\(file.megabytes) MB").font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let fraction = fileProgress[file.id] {
                            ProgressView(value: fraction, total: 1).frame(width: 70)
                        } else if ModelDownload.installed(file, in: WanBundle.directory) != nil {
                            Label("已下載", systemImage: "checkmark.circle.fill")
                                .labelStyle(.titleOnly)
                                .font(.caption2).foregroundStyle(.green)
                        } else {
                            Button("下載") { download([file]) }
                                .font(.caption)
                                .disabled(isDownloading)
                        }
                    }
                }
                HStack {
                    Button(isDownloading ? "下載中…" : "下載全部（約 \(WanBundle.totalMegabytes / 1024) GB）") {
                        download(WanBundle.files)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isDownloading)
                    if let downloadNote {
                        Text(downloadNote).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Text("來源：Hugging Face \\(WanBundle.repo)（自蒸餾 4 步版本 + umt5 編碼器 + 微型解碼器）。下載會在背景繼續，完成後自動填好上面三個插槽並套用蒸餾預設值。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 4)
        }
    }

    private func download(_ files: [RemoteModelFile]) {
        isDownloading = true
        downloadNote = nil
        fileProgress = [:]
        Task {
            for file in files {
                do {
                    let url = try await ModelDownload.download(file, into: WanBundle.directory) { fraction in
                        Task { @MainActor in fileProgress[file.id] = fraction ?? 0 }
                    }
                    await MainActor.run {
                        fileProgress[file.id] = nil
                        install(url, for: file.id)
                        appLog("video: downloaded \(file.file)", source: "app")
                    }
                } catch {
                    await MainActor.run {
                        fileProgress[file.id] = nil
                        downloadNote = "\(file.file) 下載失敗：\(error.localizedDescription)"
                        appLog("video: download failed — \(error.localizedDescription)", source: "app")
                    }
                    break
                }
            }
            await MainActor.run {
                isDownloading = false
                if downloadNote == nil {
                    downloadNote = "下載完成，插槽已填好"
                    adoptBundleDefaults()
                    engine.videoMode = true
                }
            }
        }
    }

    /// A finished download becomes the slot it belongs to, immediately.
    private func install(_ path: URL, for id: String) {
        switch id {
        case WanBundle.diffusion.id: engine.mainModel = path.path
        case WanBundle.encoder.id:   engine.t5 = path.path
        case WanBundle.decoder.id:   engine.tae = path.path
        default: break
        }
    }

    /// The distilled settings these weights are documented for. Applied when the
    /// set is complete so the first run is not a 20-step CFG 7 default that
    /// produces noise.
    private func adoptBundleDefaults() {
        let files = WanBundle.files
        let allThere = files.allSatisfy { ModelDownload.installed($0, in: WanBundle.directory) != nil }
        guard allThere, engine.mainModel.isEmpty else { return }
        for file in files {
            if let path = ModelDownload.installed(file, in: WanBundle.directory) { install(URL(fileURLWithPath: path), for: file.id) }
        }
        engine.videoMode = true
        engine.width = 480
        engine.height = 832
        engine.videoFrames = 13
        engine.steps = 8
        engine.cfg = 1
        engine.videoFlowShift = 3
    }

    // MARK: - Slots

    private var modelCard: some View {
        GroupBox("模型插槽") {
            VStack(alignment: .leading, spacing: 10) {
                ForEach([Slot.main, .encoder, .decoder]) { slot in
                    let value = path(for: slot)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(slot.title).font(.callout)
                            Spacer()
                            if !value.isEmpty {
                                Button("清除") { assign("", to: slot) }
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Button("選擇…") { picking = slot; isPickerPresented = true }
                                .font(.callout)
                        }
                        Text(value.isEmpty ? slot.hint
                                           : URL(fileURLWithPath: value).lastPathComponent)
                            .font(.caption)
                            .foregroundStyle(value.isEmpty ? .secondary : .primary)
                            .lineLimit(1).truncationMode(.middle)
                    }
                }
                Divider()
                HStack {
                    Button("載入模型") { engine.load() }
                        .disabled(engine.mainModel.isEmpty || engine.t5.isEmpty)
                    Button("卸載") { engine.unload() }
                    Spacer()
                    Text(LogStore.memoryDescription()).font(.caption2).foregroundStyle(.secondary)
                }
                Text("載入後 diffusion 在 GPU、文字編碼器在 CPU（te=cpu），微型解碼器也留在 GPU。開跑前請記住：13 影格的注意力是上萬個 token，失敗通常是 GPU watchdog 或記憶體不足。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 4)
        }
    }

    private func path(for slot: Slot) -> String {
        switch slot {
        case .main:    return engine.mainModel
        case .encoder: return engine.t5
        case .decoder: return engine.tae
        }
    }

    private func assign(_ value: String, to slot: Slot) {
        switch slot {
        case .main:    engine.mainModel = value
        case .encoder: engine.t5 = value
        case .decoder: engine.tae = value
        }
    }

    // MARK: - Prompt and parameters

    private var promptCard: some View {
        GroupBox("提示詞") {
            TextField("描述想要的畫面與動作", text: $engine.prompt, axis: .vertical)
                .lineLimit(2...5)
        }
    }

    private var paramsCard: some View {
        GroupBox("參數") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("尺寸")
                    Spacer()
                    Picker("尺寸", selection: sizeBinding) {
                        ForEach(Self.sizes.map(\.label), id: \.self) { Text($0).tag($0) }
                        Text("自訂").tag("自訂")
                    }
                    .pickerStyle(.menu)
                }
                HStack {
                    Text("影格數")
                    Spacer()
                    Picker("影格數", selection: $engine.videoFrames) {
                        ForEach([5, 9, 13, 17, 21, 25], id: \.self) { Text("\($0)").tag($0) }
                    }
                    .pickerStyle(.menu)
                }
                stepper("步數", value: $engine.steps, range: 1...20, step: 1)
                HStack {
                    Text("CFG")
                    Slider(value: $engine.cfg, in: 1...5)
                    Text(String(format: "%.1f", engine.cfg)).monospacedDigit()
                }
                HStack {
                    Text("Flow shift")
                    Slider(value: $engine.videoFlowShift, in: 1...8)
                    Text(String(format: "%.1f", engine.videoFlowShift)).monospacedDigit()
                }
                HStack {
                    Text("FPS")
                    Spacer()
                    Picker("FPS", selection: $engine.videoFps) {
                        ForEach([8, 12, 16, 24], id: \.self) { Text("\($0)").tag($0) }
                    }
                    .pickerStyle(.menu)
                }
                HStack {
                    Text("Seed")
                    TextField("−1 為隨機", value: $engine.seed, format: .number)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                }
                Text("蒸餾版本建議 4–8 步、CFG 1、Euler + simple；影格數必須是 4n+1。原生 480×832 以外的尺寸會明顯變差。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var sizeBinding: Binding<String> {
        Binding(
            get: { Self.sizes.first { $0.w == engine.width && $0.h == engine.height }?.label ?? "自訂" },
            set: { label in
                guard let size = Self.sizes.first(where: { $0.label == label }) else { return }
                engine.width = size.w
                engine.height = size.h
            }
        )
    }

    private func stepper(_ label: String, value: Binding<Int>,
                         range: ClosedRange<Int>, step: Int) -> some View {
        Stepper("\(label)：\(value.wrappedValue)", value: value, in: range, step: step)
    }

    /// Image-to-video: the same source slot the image tab uses, which the engine
    /// takes as `init_image` with a strength (how much of the frame to replace).
    private var sourceCard: some View {
        GroupBox("來源圖片（圖生影片）") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Text(engine.initImage == nil ? "選擇起始圖片" : "換一張")
                    }
                    .onChange(of: photoItem) { _, item in
                        guard let item else { return }
                        Task {
                            guard let data = try? await item.loadTransferable(type: Data.self),
                                  let ui = UIImage(data: data) else { return }
                            engine.initImage = ui.scaled(maxDimension: 1280)
                        }
                    }
                    Spacer()
                    if engine.initImage != nil {
                        Button("清除") { engine.initImage = nil; photoItem = nil }
                    }
                }
                if let source = engine.initImage {
                    Image(uiImage: source)
                        .resizable().scaledToFit().frame(height: 60)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                HStack {
                    Text("重繪強度")
                    Slider(value: $engine.strength, in: 0.3...1)
                        .disabled(engine.initImage == nil)
                    Text(String(format: "%.2f", engine.strength)).monospacedDigit()
                }
                Text("留空就是文字生影片。")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            .padding(.top, 4)
        }
    }

    // MARK: - Run

    private var generateButton: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Button("生成影片") { engine.generateVideo() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!engine.isReady || engine.tae.isEmpty)
                Button("取消") { engine.cancel() }
                    .disabled(!isRunning)
                if !elapsed.isEmpty {
                    Text(elapsed).font(.caption).monospacedDigit().foregroundStyle(.secondary)
                }
            }
            if !engine.isReady {
                Text("先載入模型").font(.caption2).foregroundStyle(.secondary)
            } else if engine.tae.isEmpty {
                Text("先選微型解碼器：沒有它整個 VAE 解碼會是十幾分鐘的 GPU 鏈，必定被 watchdog 中斷。")
                    .font(.caption2).foregroundStyle(.orange)
            }
            switch engine.phase {
            case .loading:
                Text("載入中…").font(.caption).foregroundStyle(.secondary)
            case .running(let step, let steps):
                ProgressView(value: Double(step), total: Double(max(steps, 1)))
                Text("\(step)/\(steps)").font(.caption).monospacedDigit()
            case .succeeded:
                ProgressView(value: 1, total: 1).tint(.green)
                Text("完成").font(.caption).foregroundStyle(.green)
            case .failed(let msg):
                ProgressView(value: 1, total: 1).tint(.red)
                Text(msg).font(.caption).foregroundStyle(.red).textSelection(.enabled)
            case .idle:
                EmptyView()
            }
        }
    }

    private var isRunning: Bool {
        if case .running = engine.phase { return true }
        return false
    }

    @MainActor
    private func startElapsed() {
        let start = Date()
        Task { @MainActor in
            while case .running = engine.phase {
                elapsed = String(format: "%.0fs", Date().timeIntervalSince(start))
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private var resultSection: some View {
        Group {
            if !engine.videoFramesOut.isEmpty {
                GroupBox("結果（\(engine.videoFramesOut.count) 影格）") {
                    VStack(alignment: .leading, spacing: 8) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(Array(engine.videoFramesOut.enumerated()), id: \.offset) { _, frame in
                                    Image(uiImage: frame)
                                        .resizable().scaledToFit().frame(height: 120)
                                        .clipShape(RoundedRectangle(cornerRadius: 6))
                                }
                            }
                        }
                        Text("影格依序排出。這版沒有檔輸出：第一格已存進圖庫，逐格匯出（或寫成 mp4）還沒做。")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(.top, 4)
                }
            }
        }
    }
}

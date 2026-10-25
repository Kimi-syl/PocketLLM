import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Image generation UI: the model, prompt, parameters and results.
///
/// The model comes from the library as a bundle, so the normal path is one
/// picker. The individual slots still exist underneath because a checkpoint
/// genuinely has several parts — text encoder, main model, CLIP/T5 encoders,
/// VAE, upscaler and LoRAs — and the engine needs each in its own field; that
/// list is folded away as "advanced" to keep it out of the way.
struct ImageGenView: View {
    @StateObject private var engine = SdEngine()
    @EnvironmentObject private var store: ModelStore
    @State private var picking: Slot?
    @State private var isPickerPresented = false
    @State private var importNote: String?
    /// The bundle id last written into the slots. Guards against overwriting
    /// hand-picked paths every time this tab reappears.
    @State private var mirroredBundleId: String?
    /// The slot list is an escape hatch, not the normal flow: a bundle from the
    /// Models tab already sets every slot, so this starts collapsed.
    @State private var showAdvanced = false
    /// img2img source, as a picker item rather than an image: the transfer is
    /// asynchronous and the engine only ever needs the decoded UIImage.
    @State private var photoItem: PhotosPickerItem?
    /// Upscaler download: which entry is in flight, its fraction, and the error.
    @State private var downloadingUpscaler: String?
    @State private var upscalerProgress: Double?
    @State private var upscalerError: String?
    /// Name prompt for "save the current settings as a preset".
    @State private var isPresetNamePrompt = false
    @State private var presetName = ""

    private enum Slot: String, Identifiable {
        case textEncoder, mainModel, clipL, clipG, t5, vae, upscaler, lora, refiner
        var id: String { rawValue }
        var title: String {
            switch self {
            case .textEncoder: return "文字編碼器 (llm_path)"
            case .mainModel:   return "主要模型 (diffusion / model)"
            case .clipL:       return "CLIP-L"
            case .clipG:       return "CLIP-G"
            case .t5:          return "T5 (t5xxl)"
            case .vae:         return "VAE"
            case .upscaler:    return "放大模型 (ESRGAN)"
            case .refiner:     return "精修模型 (img2img)"
            case .lora:        return "LoRA"
            }
        }
        var hint: String {
            switch self {
            case .textEncoder: return "Qwen-Image 用 Qwen2.5-VL"
            case .mainModel:   return "單一檔案的完整模型，或 Qwen/Flux/SD3 的 transformer"
            case .clipL:       return "SD1.5 / SDXL 的 CLIP-L"
            case .clipG:       return "SDXL 的 OpenCLIP-G"
            case .t5:          return "SD3 / Flux 的 T5"
            case .vae:         return "單檔模型加這個就能用 MLX 解碼"
            case .upscaler:    return "RealESRGAN / 4x-UltraSharp（.pth 或 .safetensors）"
            case .refiner:     return "低強度重繪最後一張的模型，格式要和主要模型相同"
            case .lora:        return "path|強度，多個以分號分隔"
            }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    modelCard
                    promptCard
                    img2imgCard
                    hiresCard
                    paramsCard
                    generateButton
                    upscaleCard
                    resultSection
                }
                .padding()
            }
            .navigationTitle("圖片生成")
            // The engine belongs to this view, so the Models tab cannot set its
            // slots directly; it records the choice and the change lands here.
            .onAppear { mirrorAppliedBundle() }
            .onChange(of: store.appliedBundleId) { _, _ in mirrorAppliedBundle() }
            // The id is published before the library refresh that makes the
            // bundle itself visible, so listen for the list too.
            .onChange(of: store.bundles) { _, _ in mirrorAppliedBundle() }
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
            .alert("儲存為預設", isPresented: $isPresetNamePrompt) {
                TextField("名稱", text: $presetName)
                Button("儲存") { engine.savePreset(named: presetName) }
                Button("取消", role: .cancel) {}
            } message: {
                Text("記下目前的模型插槽與取樣參數；同名會覆蓋。")
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

    private func mirrorAppliedBundle() {
        guard let id = store.appliedBundleId, id != mirroredBundleId,
            let bundle = store.bundles.first(where: { $0.id == id })
        else { return }
        apply(bundle)
    }

    private func assign(_ path: String, to slot: Slot) {
        switch slot {
        case .textEncoder: engine.textEncoder = path
        case .mainModel:   engine.mainModel = path
        case .clipL:       engine.clipL = path
        case .clipG:       engine.clipG = path
        case .t5:          engine.t5 = path
        case .vae:         engine.vae = path
        case .upscaler:    engine.upscaler = path
        case .refiner:     engine.refinerModel = path
        case .lora:
            // Append as path|<current strength> so several can accumulate.
            let entry = "\(path)|1.0"
            engine.loras = engine.loras.isEmpty ? entry : engine.loras + ";" + entry
        }
    }

    /// The model in use, chosen from the library rather than assembled by hand.
    /// Everything below the picker is the advanced escape hatch.
    private var modelCard: some View {
        GroupBox("模型") {
            VStack(alignment: .leading, spacing: 10) {
                Picker("模型", selection: bundleBinding) {
                    Text("手動選擇").tag("")
                    ForEach(store.bundles) { bundle in
                        Text(bundle.name).tag(bundle.id)
                    }
                }
                .pickerStyle(.menu)

                if let bundle = appliedBundle {
                    Text(bundle.companionSummary)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else if store.bundles.isEmpty {
                    Text("Models 分頁還沒有可用的圖片模型。下載一組模型後會出現在這裡。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else if !engine.mainModel.isEmpty {
                    Text(URL(fileURLWithPath: engine.mainModel).lastPathComponent)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                mlxHint

                if let note = importNote {
                    Text(note)
                        .font(.caption2)
                        .foregroundStyle(note.hasPrefix("已") ? .green : .red)
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

                advancedDisclosure
            }
        }
    }

    /// Says which decoder the current slots will use. This is the whole reason
    /// the VAE slot is worth importing for a single-file checkpoint: without it
    /// the engine decodes on the CPU, which is an order of magnitude slower.
    @ViewBuilder
    private var mlxHint: some View {
        if !engine.mainModel.isEmpty && engine.vae.isEmpty {
            Label(
                "這個模型沒有獨立 VAE：只能用 CPU 解碼（慢 20 倍以上）。在進階匯入 VAE 就能改用 MLX 解碼。",
                systemImage: "exclamationmark.triangle.fill"
            )
            .font(.caption2)
            .foregroundStyle(.orange)
        } else if !engine.vae.isEmpty {
            let name = URL(fileURLWithPath: engine.vae).lastPathComponent
            let problem = mlxIncompatibility
            let mode = problem == nil
                ? (engine.vaeOnMLX ? "MLX" : (engine.vaeOnCPU ? "CPU" : "引擎"))
                : "引擎"
            Label(
                problem.map { "解碼：\(mode) · \($0)" } ?? "解碼：\(mode) · \(name)",
                systemImage: problem == nil ? (engine.vaeOnMLX ? "bolt.fill" : "cpu")
                                           : "exclamationmark.triangle.fill"
            )
            .font(.caption2)
            .foregroundStyle(problem == nil ? (engine.vaeOnMLX ? .green : .secondary) : .orange)
        }
    }

    /// Why the selected VAE cannot use the MLX decoder, in a few words. Reading
    /// the header is cheap, so this can be asked on every redraw.
    private var mlxIncompatibility: String? {
        guard engine.vaeOnMLX, !engine.vae.isEmpty else { return nil }
        guard let info = try? MLXVae.inspect(engine.vae) else { return "讀不到這個 VAE" }
        switch info.layout {
        case .diffusers2D:
            return info.problem
        case .unknown:
            return "這個檔案沒有 VAE 解碼器"
        case .wan3D:
            guard info.problem != nil else { return nil }
            return "\(info.latentChannels) 通道，MLX 只支援 16 通道"
        }
    }

    private var advancedDisclosure: some View {
        DisclosureGroup(isExpanded: $showAdvanced) {
            VStack(alignment: .leading, spacing: 10) {
                Text("支援 .gguf 與 .safetensors（也接受 diffusers 資料夾）")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                slotRow(.textEncoder, value: engine.textEncoder)
                slotRow(.mainModel, value: engine.mainModel)
                slotRow(.clipL, value: engine.clipL)
                slotRow(.clipG, value: engine.clipG)
                slotRow(.t5, value: engine.t5)
                slotRow(.vae, value: engine.vae)
                slotRow(.upscaler, value: engine.upscaler)
                slotRow(.refiner, value: engine.refinerModel)
                HStack {
                    Text("精修強度")
                    Slider(value: $engine.refinerStrength, in: 0.05...0.8)
                    Text(String(format: "%.2f", engine.refinerStrength)).monospacedDigit()
                }
                .font(.callout)
                slotRow(.lora, value: engine.loras)
                Toggle("放大用 GPU", isOn: $engine.upscaleOnGPU)
                    .font(.callout)
                Text("放大的模型與倍率在「放大」區塊設定。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 6)
        } label: {
            Text("進階：個別元件")
                .font(.callout)
        }
    }

    private var appliedBundle: ModelBundle? {
        guard let id = store.appliedBundleId else { return nil }
        return store.bundles.first { $0.id == id }
    }

    /// "手動選擇" detaches without touching the slots, so a hand-tuned setup is
    /// never wiped by accident.
    private var bundleBinding: Binding<String> {
        Binding(
            get: { store.appliedBundleId ?? "" },
            set: { id in
                guard !id.isEmpty else {
                    store.detachBundle()
                    return
                }
                guard let bundle = store.bundles.first(where: { $0.id == id }) else { return }
                apply(bundle)
            }
        )
    }

    /// Copies a bundle's files into the slots.
    ///
    /// Slots the bundle does not carry are cleared, except the VAE and the
    /// upscaler: a single-file checkpoint has no VAE of its own, and a VAE the
    /// user imported for MLX decoding is exactly what should survive selecting
    /// another all-in-one model.
    private func apply(_ bundle: ModelBundle) {
        engine.textEncoder = bundle.textEncoder?.path ?? ""
        engine.mainModel = bundle.mainModel.path
        engine.clipL = bundle.clipL?.path ?? ""
        engine.clipG = bundle.clipG?.path ?? ""
        engine.t5 = bundle.t5?.path ?? ""
        if let vae = bundle.vae { engine.vae = vae.path }
        if let upscaler = bundle.upscaler { engine.upscaler = upscaler.path }
        mirroredBundleId = bundle.id
        importNote = "已套用：\(bundle.name)"
        store.apply(bundle)
    }

    // The path is only ever set by the file picker: there is no text field
    // for it, so nothing here can put a path in that the user typed.
    private func slotRow(_ slot: Slot, value: String) -> some View {
        let firstPath = value.split(separator: "|").first.map(String.init) ?? value
        let readable = !value.isEmpty && FileManager.default.isReadableFile(atPath: firstPath)
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(slot.title).font(.callout)
                Spacer()
                if !value.isEmpty {
                    Button("清除") { clear(slot) }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
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

    private func clear(_ slot: Slot) {
        switch slot {
        case .textEncoder: engine.textEncoder = ""
        case .mainModel:   engine.mainModel = ""
        case .clipL:       engine.clipL = ""
        case .clipG:       engine.clipG = ""
        case .t5:          engine.t5 = ""
        case .vae:         engine.vae = ""
        case .upscaler:    engine.upscaler = ""
        case .refiner:     engine.refinerModel = ""
        case .lora:        engine.loras = ""
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

    /// What the hires pass will end up at, computed the same way the engine
    /// call computes it (scale, then round down to a multiple of 8).
    private var hiresTargetText: String {
        let w = max(8, Int(Float(engine.width) * engine.hiresScale) / 8 * 8)
        let h = max(8, Int(Float(engine.height) * engine.hiresScale) / 8 * 8)
        return "\(w) × \(h)"
    }

    /// Hires: a second pass on upscaled latents, all inside one generate call.
    /// The final size is the base canvas times the scale, rounded down to 8.
    private var hiresCard: some View {
        GroupBox("精修（Hires）") {
            VStack(alignment: .leading, spacing: 8) {
                Toggle("第二輪精修", isOn: $engine.hiresEnabled)
                if engine.hiresEnabled {
                    HStack {
                        Text("倍率")
                        Spacer()
                        Picker("倍率", selection: $engine.hiresScale) {
                            ForEach([Float(1.5), 2, 3], id: \.self) { v in
                                Text(String(format: "%gx", v)).tag(v)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                    stepper("第二輪步數", value: $engine.hiresSteps, range: 0...40, step: 1)
                    HStack {
                        Text("重繪強度")
                        Slider(value: $engine.hiresStrength, in: 0.1...0.8)
                        Text(String(format: "%.2f", engine.hiresStrength)).monospacedDigit()
                    }
                    Text("第一輪完成後把 latent 放大 \(String(format: "%g", engine.hiresScale)) 倍再跑一輪，目標 \(hiresTargetText)。代價是第二輪取樣加上目標尺寸的解碼；強度低於約 0.3 幾乎不會改變畫面。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else {
                    Text("開啟後先出小圖再放大重繪，細節比直接生成大圖好，但要多跑一輪。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, 4)
        }
    }

    /// img2img: the prompt still describes the result, this only adds a starting
    /// image and how much of it to keep. strength is the engine's own parameter
    /// (0 keeps the source, 1 ignores it).
    private var img2imgCard: some View {
        GroupBox("圖生圖") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Text(engine.initImage == nil ? "選擇來源圖片" : "換一張來源圖片")
                    }
                    .onChange(of: photoItem) { _, item in
                        guard let item else { return }
                        Task {
                            guard let data = try? await item.loadTransferable(type: Data.self),
                                  let ui = UIImage(data: data) else {
                                appLog("img2img: could not decode the picked item", source: "app")
                                return
                            }
                            // Same cap as the chat attach: a full-resolution
                            // asset decodes to ~190 MB and is redrawn anyway.
                            engine.initImage = ui.scaled(maxDimension: 1280)
                        }
                    }
                    Spacer()
                    if engine.initImage != nil {
                        Button("清除") {
                            engine.initImage = nil
                            photoItem = nil
                        }
                    }
                }
                if let source = engine.initImage {
                    HStack(spacing: 10) {
                        Image(uiImage: source)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 64)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        Text("\(Int(source.size.width)) × \(Int(source.size.height))")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Text("重繪強度")
                    Slider(value: $engine.strength, in: 0.05...1)
                        .disabled(engine.initImage == nil)
                    Text(String(format: "%.2f", engine.strength)).monospacedDigit()
                }
                Text("0 保留原圖、1 完全重繪。強度越低越接近來源，步數要夠高才看得出變化；來源圖片會被拉伸到上面的寬高。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 4)
        }
    }

    private var paramsCard: some View {
        GroupBox("參數") {
            VStack(alignment: .leading, spacing: 8) {
                presetRow
                Divider()
                sizeRow
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
                Divider()
                samplerRow
                schedulerRow
                stepper("Clip skip", value: $engine.clipSkip, range: 1...4, step: 1)
                Divider()
                vaeTilingRows
                metalRows
            }
        }
    }

    /// Presets: the built-ins first (with the one that matches the loaded model
    /// pulled out as 建議), then the user's saved snapshots, then save/delete.
    /// A preset is applied with one tap and never merges — it is a snapshot.
    private var presetRow: some View {
        HStack {
            Text("預設")
            Spacer()
            Menu {
                if let recommended = engine.recommendedPreset {
                    Section("建議（依目前模型）") {
                        Button(recommended.name) { engine.apply(recommended) }
                    }
                }
                Section("內建") {
                    ForEach(GenPreset.builtins.filter { $0.name != engine.recommendedPreset?.name }) { preset in
                        Button(preset.name) { engine.apply(preset) }
                    }
                }
                Section("我的預設") {
                    if engine.presets.isEmpty {
                        Text("尚無")
                    }
                    ForEach(engine.presets) { preset in
                        Button(preset.name) { engine.apply(preset) }
                    }
                }
                Divider()
                Button("儲存目前設定…") {
                    presetName = ""
                    isPresetNamePrompt = true
                }
                if !engine.presets.isEmpty {
                    Menu("刪除我的預設") {
                        ForEach(engine.presets) { preset in
                            Button(preset.name, role: .destructive) { engine.deletePreset(preset) }
                        }
                    }
                }
            } label: {
                Text("選擇預設")
            }
        }
    }

    /// Common canvas sizes, matched back to the width/height fields rather than
    /// stored separately: the engine only ever reads width/height, so a preset
    /// that fell out of sync with them would be a lie in the menu.
    private static let sizePresets: [(label: String, w: Int, h: Int)] = [
        ("256 × 256", 256, 256),
        ("384 × 384", 384, 384),
        ("512 × 512", 512, 512),
        ("512 × 768", 512, 768),
        ("768 × 512", 768, 512),
        ("768 × 768", 768, 768),
        ("768 × 1024", 768, 1024),
        ("1024 × 768", 1024, 768),
        ("1024 × 1024", 1024, 1024),
    ]

    private var sizeRow: some View {
        HStack {
            Text("尺寸")
            Spacer()
            Picker("尺寸", selection: sizeBinding) {
                ForEach(Self.sizePresets.map { $0.label }, id: \.self) { Text($0).tag($0) }
                Text("自訂").tag("自訂")
            }
            .pickerStyle(.menu)
        }
    }

    private var sizeBinding: Binding<String> {
        Binding(
            get: {
                Self.sizePresets.first { $0.w == engine.width && $0.h == engine.height }?.label
                    ?? "自訂"
            },
            set: { label in
                guard let preset = Self.sizePresets.first(where: { $0.label == label }) else { return }
                engine.width = preset.w
                engine.height = preset.h
            }
        )
    }

    private var vaeTilingRows: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("VAE 用 MLX 解碼", isOn: $engine.vaeOnMLX)
                .onChange(of: engine.vaeOnMLX) { _, on in
                    if on { engine.vaeOnCPU = false }
                }
            Text("引擎只取樣並輸出 latent，解碼交給 MLX，用同一份 safetensors 逐段執行。實測 512×512 約 2 秒（引擎內 GPU 解碼要十幾分鐘，還會被 watchdog 中斷）。需要獨立載入的 VAE 檔；單檔模型會自動退回 CPU 解碼。需重新載入模型。")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Toggle("VAE 用 CPU 解碼", isOn: $engine.vaeOnCPU)
                .onChange(of: engine.vaeOnCPU) { _, on in
                    if on { engine.vaeOnMLX = false }
                }
            Text("把 VAE 解碼整段搬到 CPU，完全不經過 GPU watchdog：大圖解碼跑到一半被中斷（進度條停在解碼階段後重啟）時用這個。擴散模型仍在 GPU。代價是 CPU 時間；開啟後分塊解碼無意義。改動需重新載入模型。")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Toggle("VAE 分塊解碼", isOn: $engine.vaeTiling)
                .disabled(engine.vaeOnCPU || engine.vaeOnMLX)
            Text("大圖解碼時把 VAE 切成重疊小塊，降低尖峰記憶體。注意：分塊越小 → 塊數越多，每塊都要整跑一次 VAE，解碼時間成倍上升（1024×576 用 128 px 會切成 120 塊）。除非不加分塊會爆記憶體，否則關掉最快；要開就用塞得下的最大塊。")
                .font(.caption2)
                .foregroundStyle(.secondary)
            if engine.vaeTiling && !engine.vaeOnCPU && !engine.vaeOnMLX {
                HStack {
                    Text("分塊大小")
                    Spacer()
                    TextField("256", value: $engine.vaeTileSize, format: .number)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .monospacedDigit()
                        .frame(width: 72)
                    Text("px")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // Pulled once from the engine's own tables rather than hard-coded, so the
    // menu cannot offer a sampler the build does not have (the whole list,
    // including lcm/tcd/ddim_trailing, comes straight from the library).
    private static let samplerNames  = SdContext.sampleMethodNames()
    private static let schedulerNames = SdContext.schedulerNames()

    private var samplerRow: some View {
        HStack {
            Text("取樣器")
            Spacer()
            Picker("取樣器", selection: $engine.sampleMethod) {
                ForEach(Array(Self.samplerNames.enumerated()), id: \.offset) { i, name in
                    Text(name).tag(i)
                }
            }
            .pickerStyle(.menu)
        }
    }

    private var schedulerRow: some View {
        HStack {
            Text("排程器")
            Spacer()
            Picker("排程器", selection: $engine.scheduler) {
                ForEach(Array(Self.schedulerNames.enumerated()), id: \.offset) { i, name in
                    Text(name).tag(i)
                }
            }
            .pickerStyle(.menu)
        }
    }

    // Collapsed by default: these are the backend read-at-init knobs, not part
    // of a normal generation. They exist because a full-resolution graph is the
    // one workload the Metal defaults were not tuned for.
    private var metalRows: some View {
        DisclosureGroup("Metal 進階") {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("命令緩衝區 (n_cb)")
                    Spacer()
                    Picker("n_cb", selection: $engine.metalCommandBuffers) {
                        ForEach([1, 2, 3, 4, 6, 8], id: \.self) { v in
                            Text("\(v)").tag(v)
                        }
                    }
                    .pickerStyle(.menu)
                }
                Toggle("Metal fusion", isOn: $engine.metalFusion)
                Toggle("Metal 並行編碼", isOn: $engine.metalConcurrency)
                Text("大圖每步太久會被 GPU watchdog 中斷：把 n_cb 調高，一步會切成更多較短的命令緩衝區。若出現 GPU 錯誤（status 5）可試著關閉並行或 fusion。改動需重新載入模型。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 4)
        }
        .font(.callout)
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
            // The bar outlives the run on purpose: filled green when the image
            // was produced, filled red with the engine's message when it was not,
            // so the outcome is readable without opening the log.
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
                Text(msg)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            case .idle:
                EmptyView()
            }
        }
    }

    /// One entry in the display-image dropdown: either the bundled model or an
    /// ESRGAN file that arrived with a bundle.
    private struct UpscaleOption: Identifiable, Hashable {
        let id: String
        let name: String
        let isBundled: Bool
    }

    /// The bundled model comes first so a fresh install has something selected;
    /// anything a downloaded bundle carried is appended after it.
    private var upscaleOptions: [UpscaleOption] {
        var options: [UpscaleOption] = []
        var seen: Set<String> = []

        func add(_ path: String, _ name: String, bundled: Bool) {
            guard !path.isEmpty, seen.insert(path).inserted else { return }
            options.append(UpscaleOption(id: path, name: name, isBundled: bundled))
        }

        if let bundled = SdEngine.bundledUpscalerPath {
            add(bundled, "內建 Real-ESRGAN 4x（anime 6B）", bundled: true)
        }
        for bundle in store.bundles {
            if let upscaler = bundle.upscaler {
                add(upscaler.path, upscaler.lastPathComponent, bundled: false)
            }
        }
        // Downloaded on demand (放大 > 下載更多模型). Scanned rather than tracked
        // in state so a file downloaded in an earlier session is still there.
        if let files = try? FileManager.default.contentsOfDirectory(
            at: UpscalerSource.directory, includingPropertiesForKeys: nil) {
            for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                add(file.path, file.lastPathComponent, bundled: false)
            }
        }
        if !engine.upscaler.isEmpty {
            add(engine.upscaler,
                URL(fileURLWithPath: engine.upscaler).lastPathComponent,
                bundled: SdEngine.bundledUpscalerPath == engine.upscaler)
        }
        return options
    }

    /// The on-demand upscalers. Each is ~67 MB and the right one depends on the
    /// picture, so they are listed with a one-line character note instead of
    /// being bundled; a downloaded file goes to Documents/Upscalers and shows up
    /// in the picker above on the next pass.
    private var upscalerDownloads: some View {
        DisclosureGroup("下載更多模型") {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(UpscalerSource.catalog) { source in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(source.name).font(.callout)
                            Spacer()
                            if let installed = source.installedPath {
                                Button("使用") { engine.upscaler = installed }
                                    .font(.caption)
                            } else if downloadingUpscaler == source.id {
                                ProgressView(value: upscalerProgress ?? 0, total: 1)
                                    .frame(width: 80)
                            } else {
                                Button("下載") { download(source) }
                                    .font(.caption)
                            }
                        }
                        Text("\(source.note) · \(source.megabytes) MB")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                if let upscalerError {
                    Text(upscalerError).font(.caption2).foregroundStyle(.red)
                }
                Text("來源是 Hugging Face（Kim2091/UltraSharp、ai-forever/Real-ESRGAN）。全都會被縮成 4x 再依上面的倍率縮小。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 4)
        }
        .font(.callout)
    }

    private func download(_ source: UpscalerSource) {
        downloadingUpscaler = source.id
        upscalerProgress = nil
        upscalerError = nil
        Task {
            do {
                let url = try await UpscalerSource.download(source) { fraction in
                    Task { @MainActor in upscalerProgress = fraction }
                }
                await MainActor.run {
                    engine.upscaler = url.path
                    downloadingUpscaler = nil
                    appLog("upscaler: downloaded \(source.file)", source: "app")
                }
            } catch {
                await MainActor.run {
                    downloadingUpscaler = nil
                    upscalerError = "\(source.name) 下載失敗：\(error.localizedDescription)"
                    appLog("upscaler: download failed — \(error.localizedDescription)", source: "app")
                }
            }
        }
    }

    private var upscaleCard: some View {
        GroupBox("放大") {
            VStack(alignment: .leading, spacing: 10) {
                Picker("模型", selection: $engine.upscaler) {
                    if upscaleOptions.isEmpty {
                        Text("沒有可用的放大模型").tag("")
                    }
                    ForEach(upscaleOptions) { option in
                        Text(option.name).tag(option.id)
                    }
                }
                .pickerStyle(.menu)

                HStack {
                    Text("倍率")
                    Picker("倍率", selection: $engine.upscaleScale) {
                        ForEach([2, 3, 4], id: \.self) { Text("\($0)×").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Text(upscaleHint)
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                Toggle("用 GPU", isOn: $engine.upscaleOnGPU)
                    .font(.callout)

                HStack {
                    Button("精修最後一張（用上面的精修模型）") { engine.refineLast() }
                        .disabled(engine.images.isEmpty || engine.refinerModel.isEmpty
                                  || engine.phase == .loading)
                    Button("放大最後一張") {
                        if let last = engine.images.last { engine.upscale(last) }
                    }
                    .disabled(engine.images.isEmpty || engine.upscaler.isEmpty || engine.isUpscaling)
                    if engine.isUpscaling {
                        ProgressView()
                        Text("放大中…").font(.caption2).foregroundStyle(.secondary)
                    }
                }

                Divider()

                // A separate path: Core AI runs converted .aimodel graphs on the
                // Neural Engine and only exists from iOS 27, so the button stays
                // grey everywhere else rather than disappearing.
                HStack {
                    Button("用 Core AI 放大（神經引擎）") {
                        if let last = engine.images.last { engine.coreAIUpscale(last) }
                    }
                    .disabled(!CoreAIUpscaler.canRun || engine.images.isEmpty)
                    Spacer()
                    Text(CoreAIUpscaler.status)
                        .font(.caption2)
                        .foregroundStyle(CoreAIUpscaler.isAvailable ? .secondary : .tertiary)
                }
                if let error = engine.coreAIUpscaleError {
                    Text(error).font(.caption2).foregroundStyle(.secondary)
                }
                if let error = engine.upscaleError {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                upscalerDownloads
            }
        }
    }

    /// The model has one native factor (4x for the bundled one); a smaller
    /// choice resamples that result down rather than running another network.
    private var upscaleHint: String {
        let model = engine.upscaler.isEmpty
            ? "尚未選擇放大模型"
            : URL(fileURLWithPath: engine.upscaler).lastPathComponent
        if engine.upscaleScale == 4 {
            return "\(model) · 原生 4×，輸出為原圖四倍"
        }
        return "\(model) · 原生 4×，先放大再縮回 \(engine.upscaleScale)×"
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
                        VStack(alignment: .leading, spacing: 6) {
                            Image(uiImage: img)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .contextMenu {
                                    ShareLink(item: Image(uiImage: img),
                                              preview: SharePreview("generated", image: Image(uiImage: img)))
                                }
                            HStack(spacing: 10) {
                                Button {
                                    engine.upscale(img)
                                } label: {
                                    Label("放大 \(engine.upscaleScale)×",
                                          systemImage: "arrow.up.left.and.arrow.down.right")
                                }
                                .disabled(engine.upscaler.isEmpty || engine.isUpscaling)
                                if engine.isUpscaling {
                                    ProgressView()
                                    Text("放大中…").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

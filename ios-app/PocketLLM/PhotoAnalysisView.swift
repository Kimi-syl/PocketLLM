import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Photo analysis backed by a vision-language model.
///
/// The image is copied into the container before it is used, for the same reason
/// the image-generation slots are: a PhotosPicker item is not a stable URL.
struct PhotoAnalysisView: View {
    @StateObject private var engine = VLMEngine()
    @State private var pickingModel = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var imageURL: URL?
    @State private var preview: UIImage?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    modelCard
                    imageCard
                    askCard
                    resultCard
                }
                .padding()
            }
            .navigationTitle("照片分析")
            .fileImporter(isPresented: $pickingModel,
                          allowedContentTypes: [.folder],
                          allowsMultipleSelection: false) { result in
                guard case .success(let urls) = result, let url = urls.first else { return }
                let local = Self.copyIntoContainer(url) ?? url
                Task { await engine.load(directory: local) }
            }
            .onChange(of: pickerItem) { _, item in
                guard let item else { return }
                Task { await loadImage(item) }
            }
        }
    }

    private var modelCard: some View {
        GroupBox("視覺模型") {
            VStack(alignment: .leading, spacing: 8) {
                Text("選擇 MLX VLM 資料夾，例如 Qwen2.5-VL-3B-Instruct-4bit")
                    .font(.caption2).foregroundStyle(.secondary)
                HStack {
                    Text(engine.directory?.lastPathComponent ?? "尚未選擇")
                        .font(.callout).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Button("選擇…") { pickingModel = true }
                }
                HStack {
                    Text(engine.status).font(.caption)
                        .foregroundStyle(engine.isReady ? .green : .secondary)
                    Spacer()
                    Button("卸載") { engine.unload() }.disabled(!engine.isReady)
                }
            }
        }
    }

    private var imageCard: some View {
        GroupBox("照片") {
            VStack(alignment: .leading, spacing: 8) {
                if let preview {
                    Image(uiImage: preview)
                        .resizable().scaledToFit()
                        .frame(maxHeight: 280)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label(preview == nil ? "選擇照片" : "更換照片", systemImage: "photo")
                }
            }
        }
    }

    private var askCard: some View {
        GroupBox("問題") {
            VStack(alignment: .leading, spacing: 8) {
                TextField("要問這張照片什麼？", text: $engine.prompt, axis: .vertical)
                    .lineLimit(2...5)
                HStack {
                    Button("分析") {
                        if let imageURL { engine.analyze(image: imageURL) }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!engine.isReady || imageURL == nil || engine.isBusy)
                    if engine.isBusy { ProgressView() }
                }
            }
        }
    }

    private var resultCard: some View {
        Group {
            if !engine.answer.isEmpty {
                GroupBox("結果") {
                    Text(engine.answer)
                        .font(.callout)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    /// PhotosPicker hands back an item, not a path; load it, persist it, then
    /// use the on-disk copy so nothing depends on a transient provider.
    private func loadImage(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let ui = UIImage(data: data) else { return }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("photo-\(Int(Date().timeIntervalSince1970)).png")
        try? data.write(to: url)
        preview = ui
        imageURL = url
    }

    private static func copyIntoContainer(_ url: URL) -> URL? {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if url.path.hasPrefix(docs.path) { return url }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let dir = docs.appendingPathComponent("Models", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(url.lastPathComponent)
        if fm.fileExists(atPath: dest.path) { return dest }
        return (try? fm.copyItem(at: url, to: dest)) != nil ? dest : nil
    }
}

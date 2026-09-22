import Foundation

/// The on-device model library: what is installed, what is downloading, and what
/// the Hugging Face Hub has to offer.
///
/// Models live in `Documents/Models`, which survives app updates and is reachable
/// through the Files app. The llama.cpp bridge mmaps whatever path it is handed,
/// and files the app wrote itself need no security scope, so a downloaded model
/// loads exactly like an imported one.
///
/// Not `@MainActor`: the downloader's delegate fires on a background queue, and
/// every `@Published` mutation here is dispatched to the main thread instead.
final class ModelStore: ObservableObject {

    static let shared = ModelStore()

    enum Kind: String, Hashable, CaseIterable {
        case gguf
        case mlx

        /// The Hub library tag that narrows a search to repositories carrying
        /// this format, across the whole Hub rather than one organisation.
        var searchFilter: String { self == .gguf ? "gguf" : "mlx" }
        var label: String { self == .gguf ? "GGUF (llama.cpp)" : "MLX (Apple)" }
    }

    struct InstalledModel: Identifiable, Hashable {
        let url: URL
        let kind: Kind
        let size: Int64

        var id: String { url.path }
        var name: String { url.lastPathComponent }
    }

    struct ActiveDownload: Identifiable, Hashable {
        /// `repoId::path`, so a row in the browser can find its own progress.
        let id: String
        let label: String
        var progress: Double = 0
        var failure: String?
    }

    @Published private(set) var installed: [InstalledModel] = []
    @Published private(set) var downloads: [ActiveDownload] = []
    @Published private(set) var results: [HuggingFaceClient.SearchResult] = []
    @Published private(set) var isSearching = false
    @Published var notice: String?

    let root: URL
    /// Built per access so a token saved in Settings applies to the next
    /// request without relaunching the app.
    var client: HuggingFaceClient {
        HuggingFaceClient(token: AppSettings.shared.hfToken)
    }

    /// What an MLX model directory needs to load: weights plus the config and
    /// tokenizer files that go with them.
    private static let mlxExtensions = [
        ".safetensors", ".json", ".model", ".txt", ".jinja",
    ]

    private init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        root = documents.appendingPathComponent("Models", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        refresh()
    }

    // MARK: - Installed models

    /// Rescans the model directory. Main thread only.
    func refresh() {
        let fileManager = FileManager.default
        var found: [InstalledModel] = []

        let entries = (try? fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
            options: [.skipsHiddenFiles])) ?? []

        for entry in entries {
            let values = try? entry.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            if values?.isDirectory == true {
                // An MLX model is a directory of weights plus a config.json.
                // Requiring at least one weight file too keeps a directory that
                // is still downloading out of the list — its config arrives
                // early, and loading a half-fetched model fails confusingly.
                let config = entry.appendingPathComponent("config.json")
                guard fileManager.fileExists(atPath: config.path),
                    hasWeights(in: entry)
                else { continue }
                found.append(
                    InstalledModel(url: entry, kind: .mlx, size: directorySize(entry)))
            } else if entry.pathExtension.lowercased() == "gguf" {
                found.append(
                    InstalledModel(
                        url: entry, kind: .gguf, size: Int64(values?.fileSize ?? 0)))
            }
        }

        found.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        installed = found
    }

    func delete(_ model: InstalledModel) {
        do {
            try FileManager.default.removeItem(at: model.url)
            notice = "deleted \(model.name)"
        } catch {
            notice = "could not delete \(model.name): \(error.localizedDescription)"
        }
        refresh()
    }

    // MARK: - Hub search

    func search(_ query: String, kind: Kind, sort: HuggingFaceClient.Sort) {
        isSearching = true
        notice = nil
        let client = self.client
        Task {
            do {
                let found = try await client.search(
                    query, filter: kind.searchFilter, sort: sort)
                DispatchQueue.main.async {
                    self.results = found
                    self.isSearching = false
                    if found.isEmpty {
                        self.notice = "no \(kind.rawValue.uppercased()) repositories matched"
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    self.notice = error.localizedDescription
                    self.isSearching = false
                }
            }
        }
    }

    func listFiles(repoId: String, kind: Kind) async throws -> [HuggingFaceClient.FileEntry] {
        try await client.listFiles(
            repoId: repoId,
            extensions: kind == .gguf ? [".gguf"] : Self.mlxExtensions)
    }

    // MARK: - Downloads

    func downloadGGUF(repoId: String, file: HuggingFaceClient.FileEntry) {
        guard let url = HuggingFaceClient.downloadURL(repoId: repoId, path: file.path) else {
            notice = "could not build a download URL for \(file.path)"
            return
        }
        let name = (file.path as NSString).lastPathComponent
        begin(
            id: Self.downloadId(repoId: repoId, path: file.path),
            label: name,
            request: client.request(for: url),
            destination: uniqueDestination(named: name))
    }

    /// Downloads every file of an MLX repository into its own directory, since a
    /// MLX model is the whole folder rather than a single weight file.
    func downloadMLX(repoId: String, files: [HuggingFaceClient.FileEntry]) {
        let directoryName = repoId.replacingOccurrences(of: "/", with: "--")
        let directory = root.appendingPathComponent(directoryName, isDirectory: true)
        var queued = 0
        for file in files {
            guard let url = HuggingFaceClient.downloadURL(repoId: repoId, path: file.path) else {
                continue
            }
            let name = (file.path as NSString).lastPathComponent
            begin(
                id: Self.downloadId(repoId: repoId, path: file.path),
                label: "\(directoryName)/\(name)",
                request: client.request(for: url),
                destination: directory.appendingPathComponent(name))
            queued += 1
        }
        notice = queued == 0 ? "nothing to download in \(repoId)" : "downloading \(queued) file(s)"
    }

    func downloadState(id: String) -> ActiveDownload? {
        downloads.first { $0.id == id }
    }

    static func downloadId(repoId: String, path: String) -> String {
        "\(repoId)::\(path)"
    }

    // MARK: - Internals

    private func begin(id: String, label: String, request: URLRequest, destination: URL) {
        upsert(ActiveDownload(id: id, label: label))
        FileDownloader.shared.download(
            request,
            to: destination,
            onProgress: { fraction in
                DispatchQueue.main.async { self.setProgress(id: id, fraction: fraction) }
            },
            onComplete: { result in
                DispatchQueue.main.async {
                    switch result {
                    case .success:
                        self.downloads.removeAll { $0.id == id }
                        self.notice = "downloaded \(label)"
                        self.refresh()
                    case .failure(let error):
                        self.setFailure(id: id, message: error.localizedDescription)
                    }
                }
            })
    }

    /// Two quantisations of the same model often share a filename across
    /// repositories, so an existing file is kept and the new one named beside it
    /// rather than silently overwriting a model the user already has.
    private func uniqueDestination(named name: String) -> URL {
        let candidate = root.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let stem = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var index = 2
        while true {
            let alternative = root.appendingPathComponent("\(stem) \(index).\(ext)")
            if !FileManager.default.fileExists(atPath: alternative.path) { return alternative }
            index += 1
        }
    }

    private func upsert(_ download: ActiveDownload) {
        if let index = downloads.firstIndex(where: { $0.id == download.id }) {
            downloads[index] = download
        } else {
            downloads.append(download)
        }
    }

    private func setProgress(id: String, fraction: Double) {
        guard let index = downloads.firstIndex(where: { $0.id == id }) else { return }
        downloads[index].progress = fraction
    }

    private func setFailure(id: String, message: String) {
        guard let index = downloads.firstIndex(where: { $0.id == id }) else { return }
        downloads[index].failure = message
        notice = message
    }

    private func hasWeights(in directory: URL) -> Bool {
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil)) ?? []
        return entries.contains { $0.pathExtension.lowercased() == "safetensors" }
    }

    private func directorySize(_ url: URL) -> Int64 {
        var total: Int64 = 0
        guard
            let enumerator = FileManager.default.enumerator(
                at: url, includingPropertiesForKeys: [.fileSizeKey])
        else { return 0 }
        for case let fileURL as URL in enumerator {
            let values = try? fileURL.resourceValues(forKeys: [.fileSizeKey])
            total += Int64(values?.fileSize ?? 0)
        }
        return total
    }
}

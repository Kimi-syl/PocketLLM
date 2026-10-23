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
        /// Text-to-image repositories. Not a file format: the browser still shows
        /// the repository as a tree, but only weight files are offered, and the
        /// bundle matcher decides which slot each one takes.
        case image

        /// Hub library tags narrowing a search by format. Several are sent
        /// together and the Hub ANDs them: MLX repositories are converted from
        /// transformers checkpoints and carry both tags, so asking for `mlx`
        /// alone misses the ones that were not tagged that way.
        var searchFilters: [String] {
            switch self {
            case .gguf: return ["gguf"]
            case .mlx: return ["mlx", "transformers"]
            case .image: return []
            }
        }

        /// Pipeline tags to search, one request each.
        ///
        /// The Hub ANDs repeated filters and a repository carries exactly one
        /// pipeline tag, so text and vision models cannot be asked for in a
        /// single request — their union needs separate calls. Both are included
        /// because llama.cpp and MLX load text and vision models alike; the
        /// vision-only switch drops the text tag rather than switching category.
        var pipelineTags: [String] {
            switch self {
            case .gguf, .mlx: return ["text-generation", "image-text-to-text"]
            case .image: return ["text-to-image"]
            }
        }

        var label: String {
            switch self {
            case .gguf: return "GGUF (llama.cpp)"
            case .mlx: return "MLX (Apple)"
            case .image: return "Image"
            }
        }

        /// What the browser should offer from a repository. Image repos are
        /// filtered to weights: configs and READMEs are not slots the engine reads.
        func accepts(path: String) -> Bool {
            let lower = path.lowercased()
            switch self {
            case .gguf:
                return lower.hasSuffix(".gguf")
            case .mlx:
                return ModelStore.mlxExtensions.contains { lower.hasSuffix($0) }
            case .image:
                // .pth/.pt are here for ESRGAN upscalers, which only ship in
                // those formats; the diffusion weights themselves are GGUF or
                // safetensors.
                return [".gguf", ".safetensors", ".sft", ".pth", ".pt"].contains {
                    lower.hasSuffix($0)
                }
            }
        }
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
    /// Repositories whose downloaded files matched a loadable slot set.
    @Published private(set) var bundles: [ModelBundle] = []
    /// Image repositories that are recognisably image models but lack a
    /// conditioner or a VAE. Listed with what to fetch instead of vanishing
    /// until every file happens to arrive.
    @Published private(set) var incomplete: [ModelBundle] = []
    /// Which bundle was last applied to the image engine, so the row can say so.
    @Published private(set) var appliedBundleId: String?
    @Published private(set) var downloads: [ActiveDownload] = []
    @Published private(set) var results: [HuggingFaceClient.SearchResult] = []
    /// Narrows results to vision models; the plain search covers both tags.
    @Published var visionOnly = false
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
    static let mlxExtensions = [
        ".safetensors", ".json", ".model", ".txt", ".jinja",
    ]

    private static let appliedBundleKey = "models.appliedBundle"
    private static let imageReposKey = "models.imageRepos"

    /// How many files of a repository are still in flight. Used to fire the
    /// bundle scan exactly once, after the last one lands.
    private var pendingByRepo: [String: Int] = [:]
    private var kindByRepo: [String: Kind] = [:]
    /// Folder names downloaded under the Image category. A folder holding one
    /// weight file is otherwise indistinguishable from a text model, and only
    /// these are allowed to become a bundle on the strength of that file alone.
    private var imageRepos: Set<String> = []

    private init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        root = documents.appendingPathComponent("Models", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        appliedBundleId = UserDefaults.standard.string(forKey: Self.appliedBundleKey)
        imageRepos = Set(UserDefaults.standard.stringArray(forKey: Self.imageReposKey) ?? [])
        refresh()
    }

    // MARK: - Importing from the device

    /// Copies a picked item into the library and reports what happened.
    ///
    /// A copy rather than a reference: the security-scoped grant a file importer
    /// hands over does not survive a relaunch, and the overlay reads models
    /// across launches, so anything that only works while the picker grant is
    /// alive is not worth offering.
    ///
    /// Returns nil on success, or the reason it refused. Main thread only.
    func importModel(from url: URL) async -> String? {
        let fileManager = FileManager.default
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }

        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
            return "that item no longer exists"
        }

        // Something already inside the library needs no copy, and copying a
        // folder into itself would recurse until the disk ran out.
        if url.path.hasPrefix(root.path + "/") {
            refresh()
            return nil
        }

        if isDirectory.boolValue {
            return await importFolder(url)
        }
        guard url.pathExtension.lowercased() == "gguf" else {
            return "not a .gguf file or an MLX model folder"
        }
        // A diffusion GGUF is half of a model, so it is filed into a folder of
        // its own and remembered as an image repo; that is what lets the bundle
        // scan pair it with a text encoder and a VAE imported later.
        if isImageGGUF(url) {
            let stem = url.deletingPathExtension().lastPathComponent
            let destination = uniqueDirectory(named: stem)
            try? fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
            let failure = await performCopy(
                source: url, destination: destination.appendingPathComponent(url.lastPathComponent),
                label: url.lastPathComponent)
            if failure == nil {
                markImageRepo(destination)
                notice = imageImportReport(folder: destination)
                refresh()
            }
            return failure
        }
        return await importGGUFFile(url)
    }

    /// Classifies a picked directory instead of assuming MLX: a diffusion
    /// bundle (its files stay together so the slot matcher can pair them), an
    /// MLX model, or a folder of chat GGUFs the normal scan will pick up.
    private func importFolder(_ url: URL) async -> String? {
        let fileManager = FileManager.default
        let hasConfig = fileManager.fileExists(
            atPath: url.appendingPathComponent("config.json").path)
        if hasConfig && hasWeights(in: url) {
            return await importMLXFolder(url)
        }
        guard containsWeights(url) else {
            return "\(url.lastPathComponent) has no .gguf or .safetensors weights"
        }

        let destination = uniqueDirectory(named: url.lastPathComponent)
        let failure = await performCopy(source: url, destination: destination,
                                       label: url.lastPathComponent)
        guard failure == nil else { return failure }

        if let bundle = ModelInspector.bundle(in: destination),
           isImageBundle(bundle) {
            markImageRepo(destination)
            notice = imageImportReport(folder: destination)
        }
        refresh()
        return nil
    }

    /// A bundle belongs to the image engine when there are companion slots, or
    /// when its main file is a known diffusion architecture (or at least named
    /// like one).
    private func isImageBundle(_ bundle: ModelBundle) -> Bool {
        if bundle.hasConditioner || bundle.vae != nil { return true }
        if let image = ggufArchitectureIsImage(bundle.mainModel) { return image }
        return ModelAudit.isImageWeight(bundle.mainModel.lastPathComponent)
    }

    private func isImageGGUF(_ url: URL) -> Bool {
        ggufArchitectureIsImage(url) ?? false
    }

    /// Reads `general.architecture`; nil when the file is not a GGUF whose
    /// header can be read.
    private func ggufArchitectureIsImage(_ url: URL) -> Bool? {
        guard url.pathExtension.lowercased() == "gguf",
              let info = GGUFHeader.read(url),
              let arch = info.architecture?.lowercased() else { return nil }
        return ModelAudit.imageArchitectures.contains(arch)
    }

    private func containsWeights(_ folder: URL) -> Bool {
        guard let enumerator = FileManager.default.enumerator(
            at: folder, includingPropertiesForKeys: nil) else { return false }
        for case let url as URL in enumerator {
            let ext = url.pathExtension.lowercased()
            if ext == "gguf" || ext == "safetensors" || ext == "sft" { return true }
        }
        return false
    }

    private func markImageRepo(_ folder: URL) {
        imageRepos.insert(folder.lastPathComponent)
        UserDefaults.standard.set(Array(imageRepos), forKey: Self.imageReposKey)
    }

    /// What to say after importing a folder the image engine may want: either
    /// the slots that were filled, or the parts still missing.
    private func imageImportReport(folder: URL) -> String {
        guard let bundle = ModelInspector.bundle(in: folder) else {
            return "imported \(folder.lastPathComponent): no image weights matched"
        }
        let missing = ModelAudit.missingParts(in: bundle)
        guard missing.isEmpty else {
            return "imported \(bundle.name), but it is missing "
                + missing.map(\.label).joined(separator: "、")
        }
        configure(bundle, automatic: true)
        return "imported \(bundle.name) — \(bundle.companionSummary)"
    }

    private func importMLXFolder(_ url: URL) async -> String? {
        let fileManager = FileManager.default
        let config = url.appendingPathComponent("config.json")
        guard fileManager.fileExists(atPath: config.path) else {
            return "\(url.lastPathComponent) has no config.json, so it is not an MLX model"
        }
        guard hasWeights(in: url) else {
            return "\(url.lastPathComponent) contains no .safetensors weights"
        }

        let destination = uniqueDirectory(named: url.lastPathComponent)
        return await performCopy(source: url, destination: destination,
                                 label: url.lastPathComponent)
    }

    private func importGGUFFile(_ url: URL) async -> String? {
        let destination = uniqueDestination(named: url.lastPathComponent)
        return await performCopy(source: url, destination: destination,
                                 label: url.lastPathComponent)
    }

    /// Runs the copy off the main thread and reports the outcome. Multi-gigabyte
    /// weights take long enough that doing this on the main actor freezes the tab
    /// with no explanation, which reads as a crash.
    private func performCopy(source: URL, destination: URL, label: String) async -> String? {
        notice = "copying \(label)…"
        let failure = await Task.detached(priority: .userInitiated) { () -> String? in
            let fileManager = FileManager.default
            do {
                if fileManager.fileExists(atPath: destination.path) {
                    try fileManager.removeItem(at: destination)
                }
                try fileManager.copyItem(at: source, to: destination)
                return nil
            } catch {
                // Leave half a model behind and it shows up in the list as a
                // loadable entry that fails to load, so clean up on failure.
                try? fileManager.removeItem(at: destination)
                return error.localizedDescription
            }
        }.value

        if let failure {
            notice = "could not import \(label): \(failure)"
            return failure
        }
        notice = "imported \(label)"
        refresh()
        return nil
    }

    /// A name free of collisions inside the library, so importing a model that is
    /// already there keeps both copies rather than replacing one silently.
    private func uniqueDirectory(named name: String) -> URL {
        var candidate = root.appendingPathComponent(name, isDirectory: true)
        let fileManager = FileManager.default
        var index = 2
        while fileManager.fileExists(atPath: candidate.path) {
            candidate = root.appendingPathComponent("\(name) \(index)", isDirectory: true)
            index += 1
        }
        return candidate
    }

    // MARK: - Installed models

    /// Rescans the model directory. Main thread only.
    ///
    /// Downloads now live in `Models/<repo>/`, so GGUF files are found by
    /// walking the tree rather than by looking at the top level. A file inside a
    /// recognised image bundle is left out of `installed`: its weights are
    /// catalogued as a bundle, and offering a Qwen-Image quant as a chat model
    /// would only lead to a load failure.
    func refresh() {
        let fileManager = FileManager.default
        var found: [InstalledModel] = []
        var foundBundles: [ModelBundle] = []
        var foundIncomplete: [ModelBundle] = []

        let entries = (try? fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
            options: [.skipsHiddenFiles])) ?? []

        for entry in entries {
            let values = try? entry.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            guard values?.isDirectory == true else { continue }
            // An MLX model is a directory of weights plus a config.json.
            // Requiring at least one weight file too keeps a directory that
            // is still downloading out of the list — its config arrives
            // early, and loading a half-fetched model fails confusingly.
            let config = entry.appendingPathComponent("config.json")
            guard fileManager.fileExists(atPath: config.path),
                hasWeights(in: entry)
            else { continue }
            found.append(InstalledModel(url: entry, kind: .mlx, size: directorySize(entry)))
        }

        // A repository folder is a bundle candidate unless it is an MLX model
        // (config.json at its root) — that one is already an installed entry.
        for entry in entries {
            let values = try? entry.resourceValues(forKeys: [.isDirectoryKey])
            guard values?.isDirectory == true else { continue }
            if fileManager.fileExists(
                atPath: entry.appendingPathComponent("config.json").path) { continue }
            let repoName = entry.lastPathComponent.replacingOccurrences(of: "--", with: "/")
            guard let bundle = ModelInspector.bundle(in: entry, repoId: repoName) else { continue }
            // A lone GGUF is only an image bundle if it came from that
            // category; otherwise it is a text model that merely sits in its
            // own folder. Deciding this before reading tensors keeps a chat
            // model's header off the scan path.
            let listed = imageRepos.contains(bundle.id)
            if bundle.isAllInOne && !listed { continue }
            if ModelAudit.missingParts(in: bundle).isEmpty {
                foundBundles.append(bundle)
            } else if listed {
                foundIncomplete.append(bundle)
            }
        }

        // Files inside a half-downloaded bundle stay out of the chat list too:
        // they are image weights, not models anyone can chat with.
        let bundleFolders = Set((foundBundles + foundIncomplete).map {
            $0.folder.standardizedFileURL.path
        })
        if let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles])
        {
            for case let url as URL in enumerator {
                guard url.pathExtension.lowercased() == "gguf" else { continue }
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                guard values?.isRegularFile == true else { continue }
                let parent = url.deletingLastPathComponent().standardizedFileURL.path
                guard !bundleFolders.contains(parent) else { continue }
                found.append(
                    InstalledModel(url: url, kind: .gguf, size: Int64(values?.fileSize ?? 0)))
            }
        }

        found.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        installed = found
        bundles = foundBundles.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        incomplete = foundIncomplete.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    /// Records which bundle the image tab should use. The engine itself lives
    /// with `ImageGenView`, so the view mirrors this id into its slots when it
    /// changes — a publish from here would mean a second engine instance.
    func apply(_ bundle: ModelBundle) {
        configure(bundle, automatic: false)
    }

    /// Stops following a bundle without touching the slots the user has.
    func detachBundle() {
        appliedBundleId = nil
        UserDefaults.standard.removeObject(forKey: Self.appliedBundleKey)
    }

    private func configure(_ bundle: ModelBundle, automatic: Bool) {
        appliedBundleId = bundle.id
        UserDefaults.standard.set(bundle.id, forKey: Self.appliedBundleKey)
        notice = automatic ? "已自動配置 \(bundle.name)" : "已套用 \(bundle.name)"
        appLog(
            "bundle \(automatic ? "auto-configured" : "applied"): \(bundle.name) "
                + "(\(bundle.companionSummary))",
            source: "models")
    }

    func delete(_ bundle: ModelBundle) {
        do {
            try FileManager.default.removeItem(at: bundle.folder)
            if appliedBundleId == bundle.id {
                appliedBundleId = nil
                UserDefaults.standard.removeObject(forKey: Self.appliedBundleKey)
            }
            notice = "deleted \(bundle.name)"
        } catch {
            notice = "could not delete \(bundle.name): \(error.localizedDescription)"
        }
        refresh()
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
                // The vision toggle is a text-model narrowing; an image search
                // carries its own pipeline tag and must not inherit it.
                let tags: [String] =
                    (kind != .image && visionOnly) ? ["image-text-to-text"] : kind.pipelineTags
                let found = try await client.search(
                    query,
                    filters: kind.searchFilters,
                    sort: sort,
                    pipelineTags: tags)
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

    /// The repository's whole tree; the browser builds folders from the paths.
    func tree(repoId: String) async throws -> [HuggingFaceClient.FileEntry] {
        try await client.tree(repoId: repoId)
    }

    /// README text for the browser's completeness warning; nil is a normal
    /// answer (many repos have none).
    func readme(repoId: String) async -> String? {
        await client.readme(repoId: repoId)
    }

    // MARK: - Downloads

    /// Downloads the chosen files into `Models/<repo>/`, preserving the
    /// repository's own paths. When the last file of a repository lands, the
    /// folder is scanned and a matching bundle appears in the library.
    func download(repoId: String, paths: [String], kind: Kind) {
        let folderName = Self.repoFolderName(repoId)
        kindByRepo[repoId] = kind
        if kind == .image {
            imageRepos.insert(folderName)
            UserDefaults.standard.set(Array(imageRepos), forKey: Self.imageReposKey)
        }
        let folder = root.appendingPathComponent(folderName, isDirectory: true)
        var queued = 0
        for rawPath in paths {
            guard let path = Self.safeRelativePath(rawPath),
                let url = HuggingFaceClient.downloadURL(repoId: repoId, path: path)
            else { continue }
            pendingByRepo[repoId, default: 0] += 1
            begin(
                id: Self.downloadId(repoId: repoId, path: path),
                label: "\(folderName)/\(path)",
                request: client.request(for: url),
                destination: folder.appendingPathComponent(path),
                repoId: repoId)
            queued += 1
        }
        notice = queued == 0 ? "nothing to download" : "downloading \(queued) file(s)"
    }

    /// One directory per repository. Repo ids contain a slash, so it becomes
    /// the same `--` separator `downloadMLX` used before this tree existed.
    static func repoFolderName(_ repoId: String) -> String {
        repoId.replacingOccurrences(of: "/", with: "--")
    }

    /// Rejects absolute paths and `..` so an API response cannot write outside
    /// the repository's folder.
    static func safeRelativePath(_ path: String) -> String? {
        let components = path.split(separator: "/", omittingEmptySubsequences: true)
            .map(String.init)
        guard !components.isEmpty,
            !components.contains(where: { $0 == ".." || $0 == "." })
        else { return nil }
        return components.joined(separator: "/")
    }

    func downloadState(id: String) -> ActiveDownload? {
        downloads.first { $0.id == id }
    }

    static func downloadId(repoId: String, path: String) -> String {
        "\(repoId)::\(path)"
    }

    // MARK: - Internals

    private func begin(
        id: String, label: String, request: URLRequest, destination: URL,
        repoId: String? = nil
    ) {
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
                        if let repoId { self.finishRepo(repoId: repoId) }
                        self.refresh()
                    case .failure(let error):
                        self.setFailure(id: id, message: error.localizedDescription)
                        // Still dec the count: a retry would otherwise push it
                        // past zero and the auto-configuration never fires.
                        if let repoId { self.finishRepo(repoId: repoId) }
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

    /// Decrements the repository's in-flight count; at zero the scan has
    /// everything it needs, so the auto-configuration is decided once.
    private func finishRepo(repoId: String) {
        let remaining = max(0, (pendingByRepo[repoId] ?? 1) - 1)
        if remaining > 0 {
            pendingByRepo[repoId] = remaining
            return
        }
        pendingByRepo[repoId] = nil
        let kind = kindByRepo.removeValue(forKey: repoId)
        guard kind == .image else { return }
        let folderName = Self.repoFolderName(repoId)
        let folder = root.appendingPathComponent(folderName, isDirectory: true)
        guard let bundle = ModelInspector.bundle(in: folder, repoId: repoId) else {
            notice = "\(repoId): 下載完成，但找不到可用的主模型檔"
            return
        }
        // Do not point the engine at half a model: a bare diffusion
        // transformer loads far enough to fail inside the engine, and the
        // missing parts are a download the user can still make.
        let missing = ModelAudit.missingParts(in: bundle)
        guard missing.isEmpty else {
            notice = "\(repoId) 還缺少 " + missing.map(\.label).joined(separator: "、")
                + "；在該 repo 頁面補下載後會自動配置"
            refresh()
            return
        }
        // The repository the user just finished downloading is the one they
        // want to generate with, so the slots follow it without another tap.
        configure(bundle, automatic: true)
        refresh()
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

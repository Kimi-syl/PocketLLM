import SwiftUI
import UniformTypeIdentifiers

/// A repository chosen in the browser, carrying the format alongside it so the
/// file list knows what to request — and whether one file or the whole folder is
/// the thing to download.
struct RepoSelection: Hashable {
    let repoId: String
    let kind: ModelStore.Kind
}

/// A folder or file in a repository, rebuilt from the flat recursive listing so
/// the browser can offer folders as well as individual files.
private struct RepoNode: Identifiable, Hashable {
    let name: String
    let path: String
    let isDirectory: Bool
    let size: Int
    var children: [RepoNode]

    var id: String { path }

    /// OutlineGroup stops drawing a chevron on `nil`; an empty array still gets one.
    var childNodes: [RepoNode]? { children.isEmpty ? nil : children }

    /// Every file under this node, itself included. Folder checkboxes act on it.
    var filePaths: [String] {
        isDirectory ? children.flatMap(\.filePaths) : [path]
    }
}

/// Builds the hierarchy in one pass over the flat paths.
private enum RepoTree {

    private final class Builder {
        let name: String
        let path: String
        var file: HuggingFaceClient.FileEntry?
        var children: [String: Builder] = [:]

        init(name: String, path: String) {
            self.name = name
            self.path = path
        }

        /// Folders first, then files, both by name — the order a file manager uses.
        func nodes() -> [RepoNode] {
            children.values
                .map { child in
                    if let file = child.file, child.children.isEmpty {
                        return RepoNode(
                            name: child.name, path: child.path, isDirectory: false,
                            size: file.realSize, children: [])
                    }
                    let kids = child.nodes()
                    return RepoNode(
                        name: child.name, path: child.path, isDirectory: true,
                        size: 0, children: kids)
                }
                .sorted { lhs, rhs in
                    if lhs.isDirectory != rhs.isDirectory { return lhs.isDirectory }
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                }
        }
    }

    static func build(from files: [HuggingFaceClient.FileEntry]) -> [RepoNode] {
        let root = Builder(name: "", path: "")
        for file in files {
            let parts = file.path.split(separator: "/").map(String.init)
            guard let leaf = parts.last else { continue }
            var cursor = root
            for segment in parts.dropLast() {
                let path = cursor.path.isEmpty ? segment : cursor.path + "/" + segment
                if let next = cursor.children[segment] {
                    cursor = next
                } else {
                    let next = Builder(name: segment, path: path)
                    cursor.children[segment] = next
                    cursor = next
                }
            }
            let path = cursor.path.isEmpty ? leaf : cursor.path + "/" + leaf
            let node = Builder(name: leaf, path: path)
            node.file = file
            cursor.children[leaf] = node
        }
        return root.nodes()
    }
}

/// The model library: what is installed, what is in flight, and the Hugging Face
/// browser that fills it.
struct ModelsView: View {
    @EnvironmentObject var store: ModelStore
    @EnvironmentObject var settings: AppSettings
    @State private var query = ""
    @State private var kind: ModelStore.Kind = .gguf
    @State private var showImporter = false

    var body: some View {
        NavigationStack {
            List {
                if kind == .image { bundlesSection }
                installedSection
                if !store.downloads.isEmpty {
                    downloadsSection
                }
                searchSection
            }
            .navigationTitle("Models")
            .navigationDestination(for: RepoSelection.self) { selection in
                RepoFilesView(selection: selection)
            }
            .refreshable { store.refresh() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Import") { showImporter = true }
                }
            }
            // .folder alongside .data: an MLX model is a directory of weights,
            // config and tokenizer files, and a picker limited to .data cannot
            // return one.
            //
            // This is the single-selection overload, which delivers
            // Result<URL, Error>. It must not be replaced by the
            // allowsMultipleSelection: false form of the other overload: that
            // one delivers Result<[URL], Error> regardless of the flag's value,
            // so binding a bare URL from it does not compile.
            .fileImporter(
                isPresented: $showImporter,
                allowedContentTypes: [.folder, .data]
            ) { result in
                guard case .success(let url) = result else { return }
                Task {
                    if let reason = await store.importModel(from: url) {
                        store.notice = "import refused: \(reason)"
                    }
                }
            }
        }
    }

    private var bundlesSection: some View {
        Section {
            if store.bundles.isEmpty {
                Text("No image models yet. Pick Image below; a download that comes with its text encoder and VAE is matched automatically when the last file lands.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(store.bundles) { bundle in
                VStack(alignment: .leading, spacing: 3) {
                    Text(bundle.name).font(.body).lineLimit(2)
                    Text(bundle.companionSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    HStack(spacing: 6) {
                        Text(humanReadableBytes(Int(bundle.size)))
                        if store.appliedBundleId == bundle.id {
                            Label("已自動配置", systemImage: "checkmark.seal.fill")
                                .foregroundStyle(.green)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
                .onTapGesture { store.apply(bundle) }
                .swipeActions {
                    Button(role: .destructive) {
                        store.delete(bundle)
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        } header: {
            Text("Image models")
        } footer: {
            Text("Tap a bundle to point every image-engine slot at its files. Matching is by filename: a main model plus a text encoder and a VAE, or a single all-in-one checkpoint.")
                .font(.footnote)
        }
    }

    private var installedSection: some View {
        Section {
            if store.installed.isEmpty {
                Text("No models yet. Search Hugging Face below, or import a file from the Chat tab.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(store.installed) { model in
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.name).font(.body).lineLimit(2)
                    Text("\(model.kind.rawValue.uppercased()) · \(humanReadableBytes(Int(model.size)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .swipeActions {
                    Button(role: .destructive) {
                        store.delete(model)
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        } header: {
            Text("Installed")
        } footer: {
            Text("Import takes a .gguf file or an MLX model folder already on this device; both are copied into the app's own library so they load after a relaunch.")
                .font(.footnote)
        }
    }

    private var downloadsSection: some View {
        Section {
            ForEach(store.downloads) { download in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(download.label).font(.callout).lineLimit(1)
                        Spacer()
                        if download.failure == nil {
                            Text("\(Int(download.progress * 100))%")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    ProgressView(value: download.progress)
                    if let failure = download.failure {
                        Text(failure).font(.caption).foregroundStyle(.red).lineLimit(2)
                    }
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("Downloading")
        }
    }

    private var searchSection: some View {
        Section {
            Picker("Format", selection: $kind) {
                ForEach(ModelStore.Kind.allCases, id: \.self) { option in
                    Text(option.label).tag(option)
                }
            }
            .pickerStyle(.segmented)

            // The default search asks for text-generation and image-text-to-text
            // together, since both loaders handle text and vision models. This
            // narrows the result set rather than being a third format.
            if kind != .image {
                Toggle("Only vision models", isOn: $store.visionOnly)
                    .font(.footnote)
            }

            Picker("Sort by", selection: sortBinding) {
                ForEach(HuggingFaceClient.Sort.allCases) { option in
                    Text(option.label).tag(option)
                }
            }
            .pickerStyle(.menu)

            HStack {
                TextField(placeholder, text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { runSearch() }
                if store.isSearching {
                    ProgressView()
                } else {
                    Button("Search") { runSearch() }
                }
            }

            ForEach(store.results) { result in
                NavigationLink(value: RepoSelection(repoId: result.repoId, kind: kind)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.repoId).font(.body).lineLimit(2)
                        Text("\(result.downloads ?? 0) downloads · \(result.likes ?? 0) likes")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let notice = store.notice {
                Text(notice).font(.footnote).foregroundStyle(.secondary).lineLimit(3)
            }
        } header: {
            Text("Hugging Face")
        }
    }

    /// The ordering persists in AppSettings as a raw string, so the settings file
    /// never has to know a view-owned type; the bridge lives here instead.
    private var sortBinding: Binding<HuggingFaceClient.Sort> {
        Binding(
            get: { HuggingFaceClient.Sort(rawValue: settings.searchSort) ?? .relevance },
            set: { settings.searchSort = $0.rawValue }
        )
    }

    private var placeholder: String {
        switch kind {
        case .gguf: return "Search GGUF models"
        case .mlx: return "Search MLX models"
        case .image: return "Search image models"
        }
    }

    private func runSearch() {
        store.search(query, kind: kind,
                     sort: HuggingFaceClient.Sort(rawValue: settings.searchSort) ?? .relevance)
    }
}

/// The repository's tree, with a checkbox per folder and per file. The unit is
/// the file: checking a folder checks everything under it. Downloads keep their
/// repository paths inside `Models/<repo>/`, so a bundle folder ends up shaped
/// like the repo it came from.
struct RepoFilesView: View {
    let selection: RepoSelection
    @EnvironmentObject var store: ModelStore
    @State private var files: [HuggingFaceClient.FileEntry] = []
    @State private var roots: [RepoNode] = []
    @State private var selected: Set<String> = []
    @State private var failure: String?
    @State private var isLoading = true

    private var repoId: String { selection.repoId }

    var body: some View {
        List {
            if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowSeparator(.hidden)
            }
            if let failure {
                Text(failure).font(.footnote).foregroundStyle(.red)
            }
            if !files.isEmpty {
                selectionBar
            }
            OutlineGroup(roots, children: \.childNodes) { node in
                row(node)
            }
            if !isLoading && files.isEmpty && failure == nil {
                Text("No files of the selected format in this repository.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(repoId)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var selectionBar: some View {
        Section {
            HStack {
                Button("Select all") { selected = Set(files.map(\.path)) }
                Spacer()
                Button("Clear") { selected.removeAll() }
                Spacer()
                Button("Download \(selected.count)") { download() }
                    .disabled(selected.isEmpty)
            }
            .font(.callout)
            .buttonStyle(.borderless)
        } footer: {
            Text("Files land in Models/\(ModelStore.repoFolderName(repoId))/ keeping the repository's folders. When the last file of the repository arrives, a matching model appears under Image models.")
                .font(.footnote)
        }
    }

    /// One request for the whole tree, then the same format filter the flat
    /// listing used, so the tree only offers what the loader can take.
    @MainActor
    private func load() async {
        isLoading = true
        failure = nil
        do {
            let all = try await store.tree(repoId: repoId)
            let accepted = all.filter { selection.kind.accepts(path: $0.path) }
            files = accepted
            roots = RepoTree.build(from: accepted)
            // Nothing preselected: a GGUF repo holds every quantisation, and
            // selecting them all by default would queue several gigabytes.
            selected = []
            if accepted.isEmpty && !all.isEmpty {
                failure = "Every file here is outside the \(selection.kind.label) filter."
            }
        } catch {
            failure = error.localizedDescription
        }
        isLoading = false
    }

    private func download() {
        store.download(repoId: repoId, paths: selected.sorted())
    }

    private func row(_ node: RepoNode) -> some View {
        let mark = symbol(for: node)
        return HStack(spacing: 10) {
            Image(systemName: node.isDirectory ? "folder" : "doc")
                .foregroundStyle(node.isDirectory ? Color.accentColor : Color.secondary)
            Text(node.name).font(.callout).lineLimit(1)
            Spacer()
            if !node.isDirectory {
                Text(humanReadableBytes(node.size))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                if let state = store.downloadState(
                    id: ModelStore.downloadId(repoId: repoId, path: node.path))
                {
                    Text("\(Int((state.progress) * 100))%")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(state.failure == nil ? .secondary : .red)
                }
            }
            Button {
                toggle(node)
            } label: {
                Image(systemName: mark)
                    .foregroundStyle(mark == "circle" ? Color.secondary : Color.accentColor)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 2)
    }

    private func toggle(_ node: RepoNode) {
        let paths = node.filePaths
        if paths.allSatisfy({ selected.contains($0) }) {
            selected.subtract(paths)
        } else {
            selected.formUnion(paths)
        }
    }

    /// Checked, unchecked, or partly checked for a folder with only some of its
    /// files selected.
    private func symbol(for node: RepoNode) -> String {
        let paths = node.filePaths
        let count = paths.filter { selected.contains($0) }.count
        if count == 0 { return "circle" }
        return count == paths.count ? "checkmark.circle.fill" : "minus.circle.fill"
    }
}

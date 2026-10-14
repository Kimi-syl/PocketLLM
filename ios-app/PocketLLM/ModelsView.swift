import SwiftUI
import UniformTypeIdentifiers

/// A repository chosen in the browser, carrying the format alongside it so the
/// file list knows what to request — and whether one file or the whole folder is
/// the thing to download.
struct RepoSelection: Hashable {
    let repoId: String
    let kind: ModelStore.Kind
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
        kind == .gguf ? "Search GGUF models" : "Search MLX models"
    }

    private func runSearch() {
        store.search(query, kind: kind,
                     sort: HuggingFaceClient.Sort(rawValue: settings.searchSort) ?? .relevance)
    }
}

/// The files inside one repository, each downloadable. For MLX the unit is the
/// whole folder, so the individual rows are read-only and one button takes all of
/// them.
struct RepoFilesView: View {
    let selection: RepoSelection
    @EnvironmentObject var store: ModelStore
    @State private var files: [HuggingFaceClient.FileEntry] = []
    @State private var failure: String?
    @State private var isLoading = true

    private var repoId: String { selection.repoId }
    private var kind: ModelStore.Kind { selection.kind }

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
            if kind == .mlx && !files.isEmpty {
                Button("Download all \(files.count) file(s)") {
                    store.downloadMLX(repoId: repoId, files: files)
                }
            }
            ForEach(files) { file in
                fileRow(file)
            }
            if !isLoading && files.isEmpty && failure == nil {
                Text("No downloadable files in this repository.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(repoId)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    /// Isolated to the main actor: `.task` hands its closure to the generic
    /// executor, and assigning `@State` from there is what produces SwiftUI's
    /// "publishing changes from background threads" warnings.
    @MainActor
    private func load() async {
        isLoading = true
        failure = nil
        do {
            files = try await store.listFiles(repoId: repoId, kind: kind)
        } catch {
            failure = error.localizedDescription
        }
        isLoading = false
    }

    private func fileRow(_ file: HuggingFaceClient.FileEntry) -> some View {
        let state = store.downloadState(
            id: ModelStore.downloadId(repoId: repoId, path: file.path))
        let inFlight = state != nil && state?.failure == nil
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(file.path).font(.callout).lineLimit(2)
                Spacer()
                Text(humanReadableBytes(file.realSize))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                if kind == .gguf {
                    if inFlight {
                        Text("\(Int((state?.progress ?? 0) * 100))%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    } else {
                        Button("Get") { store.downloadGGUF(repoId: repoId, file: file) }
                            .buttonStyle(.bordered)
                    }
                }
            }
            if let state {
                ProgressView(value: state.progress)
                if let downloadFailure = state.failure {
                    Text(downloadFailure).font(.caption).foregroundStyle(.red).lineLimit(3)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

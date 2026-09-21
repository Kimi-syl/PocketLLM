import Foundation

/// Hugging Face Hub access: model search, repository file listing, and
/// resolve-URL construction.
///
/// Mirrors the Android core's `HuggingFaceClient` endpoint for endpoint, so the
/// two apps offer the same library from the same queries. The API is public and
/// unauthenticated for open repos; `token` is only needed for gated models.
struct HuggingFaceClient {

    struct SearchResult: Decodable, Identifiable, Hashable {
        let modelId: String?
        let downloads: Int?
        let likes: Int?

        /// The Hub reports the repository twice, as `id` and `modelId`. Both are
        /// decoded, but `id` cannot back `Identifiable` directly: it is optional,
        /// and nil across several rows would give SwiftUI duplicate identities.
        private let rawId: String?

        enum CodingKeys: String, CodingKey {
            case modelId, downloads, likes
            case rawId = "id"
        }

        var repoId: String { modelId ?? rawId ?? "" }
        var id: String { repoId }
    }

    struct FileEntry: Decodable, Identifiable, Hashable {
        struct LFS: Decodable {
            let size: Int?
        }

        let type: String?
        let path: String
        let size: Int?
        let lfs: LFS?

        /// LFS-backed weights report their pointer size in `size` and the real
        /// length in `lfs.size`; sorting on the wrong one buries the small
        /// quantisations at the top of the list.
        var realSize: Int { lfs?.size ?? size ?? 0 }
        var isFile: Bool { (type ?? "file") == "file" }
        var id: String { path }
    }

    enum ClientError: LocalizedError {
        case http(Int, String)
        case badURL(String)

        var errorDescription: String? {
            switch self {
            case .http(let code, let body):
                let detail = body.isEmpty ? "" : " — \(body.prefix(160))"
                return "Hugging Face returned HTTP \(code)\(detail)"
            case .badURL(let raw):
                return "could not build a request URL for \(raw)"
            }
        }
    }

    var token: String?

    private static let api = "https://huggingface.co/api/models"
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        return d
    }()

    // MARK: - Search

    /// Searches the Hub. `filter` narrows to a file format (e.g. `gguf`) and
    /// `author` to a single organisation — MLX conversions live under
    /// `mlx-community`, and searching "safetensors" instead would return every
    /// unconverted checkpoint on the Hub.
    func search(
        _ query: String, filter: String? = "gguf", author: String? = nil, limit: Int = 25
    ) async throws -> [SearchResult] {
        var components = URLComponents(string: Self.api)!
        var items: [URLQueryItem] = [
            URLQueryItem(name: "sort", value: "downloads"),
            URLQueryItem(name: "direction", value: "-1"),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            items.append(URLQueryItem(name: "search", value: trimmed))
        }
        if let filter, !filter.isEmpty {
            items.append(URLQueryItem(name: "filter", value: filter))
        }
        if let author, !author.isEmpty {
            items.append(URLQueryItem(name: "author", value: author))
        }
        components.queryItems = items
        guard let url = components.url else { throw ClientError.badURL(Self.api) }

        let data = try await get(url)
        return try Self.decoder.decode([SearchResult].self, from: data)
    }

    // MARK: - Repository listing

    /// Lists the files in a repository, keeping those whose path ends in one of
    /// `extensions`, smallest first.
    func listFiles(
        repoId: String, revision: String = "main", extensions: [String]
    ) async throws -> [FileEntry] {
        let url = URL(string: "\(Self.api)/\(repoId)/tree/\(revision)?recursive=false")
        guard let url else { throw ClientError.badURL(repoId) }
        let data = try await get(url)
        let all = try Self.decoder.decode([FileEntry].self, from: data)
        return all
            .filter { entry in
                entry.isFile
                    && extensions.contains { entry.path.lowercased().hasSuffix($0.lowercased()) }
            }
            .sorted { $0.realSize < $1.realSize }
    }

    // MARK: - Downloads

    static func downloadURL(repoId: String, path: String, revision: String = "main") -> URL? {
        let trimmed = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let raw = "https://huggingface.co/\(repoId)/resolve/\(revision)/"
            + trimmed.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)!
        return URL(string: raw)
    }

    /// A request that carries the bearer token when one is configured.
    func request(for url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.timeoutInterval = 60
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    // MARK: - Internals

    private func get(_ url: URL) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request(for: url))
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw ClientError.http(
                http.statusCode, String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }
}

/// Byte counts as a readable string, for file lists and progress rows.
func humanReadableBytes(_ bytes: Int) -> String {
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    formatter.allowedUnits = [.useMB, .useGB]
    return formatter.string(fromByteCount: Int64(bytes))
}

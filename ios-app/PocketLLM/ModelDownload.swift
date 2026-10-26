import Foundation

/// One downloadable model file: a direct URL plus the size the UI shows.
struct RemoteModelFile: Identifiable, Equatable {
    let id: String
    /// Name it is saved under, so a re-download replaces the old copy.
    let file: String
    let megabytes: Int
    let url: URL

    /// `https://huggingface.co/<repo>/resolve/main/<path>`, which redirects to
    /// the CDN and needs no token on these repos.
    static func hf(_ repo: String, _ path: String) -> URL {
        URL(string: "https://huggingface.co/\(repo)/resolve/main/\(path)")!
    }
}

/// Streaming downloads for model files, shared by the upscaler catalogue and
/// the video bundle.
///
/// Hand-rolled on `URLSession.bytes` rather than `URLSession.download(for:)`
/// because the fraction has to reach the UI: a silent multi-gigabyte wait looks
/// exactly like a hang, and a user who force-quits during one of these gets a
/// half-written file.
enum ModelDownload {
    static func directory(_ name: String) -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(name, isDirectory: true)
    }

    /// Path of this file under `directory` when it is already there.
    static func installed(_ file: RemoteModelFile, in directory: URL) -> String? {
        let path = directory.appendingPathComponent(file.file).path
        return FileManager.default.isReadableFile(atPath: path) ? path : nil
    }

    /// Downloads into `directory`, replacing any previous copy. `progress` is a
    /// fraction, or nil when the server sends no length.
    @discardableResult
    static func download(_ file: RemoteModelFile, into directory: URL,
                         progress: @escaping (Double?) -> Void) async throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(file.file)
        // A throwaway name until the last byte lands: a killed download must not
        // leave something the loaders would happily try to read.
        let partial = destination.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: partial)

        let (bytes, response) = try await URLSession.shared.bytes(from: file.url)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        let expected = response.expectedContentLength > 0
            ? Double(response.expectedContentLength) : nil

        FileManager.default.createFile(atPath: partial.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: partial.path) else {
            throw URLError(.cannotCreateFile)
        }
        defer { try? handle.close() }
        var buffer = [UInt8](repeating: 0, count: 1 << 20)
        var written = 0
        var iterator = bytes.makeAsyncIterator()
        while true {
            let read = try await readChunk(&iterator, into: &buffer)
            if read <= 0 { break }
            try handle.write(contentsOf: buffer[0..<read])
            written += read
            progress(expected.map { Double(written) / $0 })
        }
        try handle.close()
        guard written > 0 else {
            try? FileManager.default.removeItem(at: partial)
            throw URLError(.zeroByteResource)
        }
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: partial, to: destination)
        progress(1)
        return destination
    }

    /// One buffer's worth from an async byte stream, or 0 at the end. Generic
    /// over the iterator so it does not depend on how `URLSession.AsyncBytes`
    /// spells its own iterator type.
    private static func readChunk<I: AsyncIteratorProtocol>(_ stream: inout I,
                                                           into buffer: inout [UInt8]) async throws -> Int
    where I.Element == UInt8 {
        var count = 0
        while count < buffer.count, let byte = try await stream.next() {
            buffer[count] = byte
            count += 1
        }
        return count
    }
}

import Foundation

/// Upscaler models that can be fetched on demand, and the tiny bit of
/// bookkeeping needed to keep them on disk.
///
/// The app ships one Real-ESRGAN so 放大 works on a fresh install; these are the
/// alternatives, and they are deliberately *not* bundled: each is ~67 MB and the
/// right one depends on the picture (illustration vs photo, sharp vs smooth).
/// Every entry is a 4x model, because `SdEngine` treats the upscaler's own
/// factor as 4 and resamples down from there for a 2x or 3x choice.
struct UpscalerSource: Identifiable, Equatable {
    let id: String
    /// File name the download is saved under, so a re-download overwrites
    /// instead of piling up copies.
    let file: String
    let name: String
    let note: String
    let megabytes: Int
    let url: URL

    /// `https://huggingface.co/<repo>/resolve/main/<path>`, which redirects to the
    /// CDN and needs no token on these repos.
    static func hf(_ repo: String, _ path: String) -> URL {
        URL(string: "https://huggingface.co/\(repo)/resolve/main/\(path)")!
    }

    static let catalog: [UpscalerSource] = [
        UpscalerSource(id: "ultrasharp", file: "4x-UltraSharp.pth",
                       name: "4x-UltraSharp",
                       note: "插畫、線條最銳利",
                       megabytes: 67, url: hf("Kim2091/UltraSharp", "4x-UltraSharp.pth")),
        UpscalerSource(id: "ultramix-balanced",
                       file: "4x-UltraMix_Balanced.pth",
                       name: "4x-UltraMix Balanced",
                       note: "UltraSharp 與其他模型的折衷",
                       megabytes: 67,
                       url: hf("Kim2091/UltraSharp", "Interpolations/4x-UltraMix_Balanced.pth")),
        UpscalerSource(id: "ultramix-smooth",
                       file: "4x-UltraMix_Smooth.pth",
                       name: "4x-UltraMix Smooth",
                       note: "平滑，較少過度銳化",
                       megabytes: 67,
                       url: hf("Kim2091/UltraSharp", "Interpolations/4x-UltraMix_Smooth.pth")),
        UpscalerSource(id: "real-esrgan-x4", file: "RealESRGAN_x4.pth",
                       name: "Real-ESRGAN x4",
                       note: "照片、寫實素材的通用款",
                       megabytes: 67,
                       url: hf("ai-forever/Real-ESRGAN", "RealESRGAN_x4.pth")),
    ]

    /// Where downloads live: alongside the imported models, so the upscaler
    /// picker finds them the same way it finds anything else.
    static var directory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Upscalers", isDirectory: true)
    }

    /// Path of this entry if it has already been downloaded, else nil.
    var installedPath: String? {
        let path = Self.directory.appendingPathComponent(file).path
        return FileManager.default.isReadableFile(atPath: path) ? path : nil
    }

    /// Downloads into `directory`, replacing any previous copy. Progress is
    /// reported as a fraction, or nil when the server sends no length.
    static func download(_ source: UpscalerSource,
                         progress: @escaping (Double?) -> Void) async throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(source.file)

        // Hand-rolled rather than URLSession.download(for:): the fraction has to
        // reach the UI, and these files are large enough that a silent 67 MB
        // wait looks like a hang.
        let (bytes, response) = try await URLSession.shared.bytes(from: source.url)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        let expected = response.expectedContentLength > 0
            ? Double(response.expectedContentLength) : nil
        var data = Data()
        if let expected { data.reserveCapacity(Int(expected)) }
        var buffer = [UInt8](repeating: 0, count: 1 << 16)
        var stream = bytes.makeAsyncIterator()
        while true {
            let read = try await readChunk(&stream, into: &buffer)
            if read <= 0 { break }
            data.append(contentsOf: buffer[0..<read])
            progress(expected.map { Double(data.count) / $0 })
        }
        guard !data.isEmpty else { throw URLError(.zeroByteResource) }
        try data.write(to: destination, options: .atomic)
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

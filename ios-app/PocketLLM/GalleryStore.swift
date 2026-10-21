import Foundation
import SwiftUI

/// On-disk archive of everything the image tab produces.
///
/// Generated images used to live only in `SdEngine.images`, so leaving the tab
/// lost them. Each image is now written to `Documents/Gallery` as a PNG with a
/// 512 px thumbnail and a JSON sidecar, which also keeps the grid cheap: it
/// loads the thumbnail, never the full image.
@MainActor
final class GalleryStore: ObservableObject {
    static let shared = GalleryStore()

    struct Meta: Codable, Hashable, Sendable {
        var prompt = ""
        var negative = ""
        var width = 0
        var height = 0
        var steps = 0
        var seed: Int64 = -1
        var model = ""
        /// "generated" or "upscaled".
        var kind = "generated"
        /// Enlargement factor, only for upscaled entries.
        var scale: Int?
        var createdAt = Date()
    }

    struct Item: Identifiable, Hashable {
        let id: String
        let url: URL
        let thumbURL: URL
        let meta: Meta

        var isUpscaled: Bool { meta.kind == "upscaled" }
        var createdAt: Date { meta.createdAt }
    }

    @Published private(set) var items: [Item] = []
    @Published var notice: String?

    /// Beside `Models/`, so the two kinds of user data stay separable.
    nonisolated static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Gallery", isDirectory: true)
    }

    private init() {
        try? FileManager.default.createDirectory(at: Self.folder, withIntermediateDirectories: true)
        reload()
    }

    // MARK: - Reading

    func reload() {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: Self.folder.path) else {
            items = []
            return
        }
        let decoder = JSONDecoder()
        var found: [Item] = []
        for name in names where name.hasSuffix(".png") {
            let stem = String(name.dropLast(4))
            let url = Self.folder.appendingPathComponent(name)
            var meta = Meta()
            if let data = try? Data(contentsOf: Self.folder.appendingPathComponent(stem + ".json")),
               let decoded = try? decoder.decode(Meta.self, from: data) {
                meta = decoded
            } else if let date = try? url.resourceValues(forKeys: [.creationDateKey]).creationDate {
                // Older entries, or a write that died between the two files.
                meta.createdAt = date
            }
            found.append(Item(id: stem, url: url,
                              thumbURL: Self.folder.appendingPathComponent(stem + "-thumb.jpg"),
                              meta: meta))
        }
        items = found.sorted { $0.createdAt > $1.createdAt }
    }

    // MARK: - Writing

    /// Encodes and writes off-main; PNG of a 1024 px canvas is not free.
    func save(_ image: UIImage, meta: Meta) {
        let folder = Self.folder
        var meta = meta
        if meta.createdAt.timeIntervalSince1970 == 0 { meta.createdAt = Date() }
        let stem = Self.stem(for: meta.createdAt)
        let transfer = Transfer(image: image)

        Task.detached(priority: .utility) { [weak self] in
            var failure: String?
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                guard let png = transfer.image.pngData() else {
                    throw SaveError.encode
                }
                try png.write(to: folder.appendingPathComponent(stem + ".png"), options: .atomic)
                if let thumb = Self.thumbnail(transfer.image, maxPixel: 512),
                   let jpeg = thumb.jpegData(compressionQuality: 0.8) {
                    try jpeg.write(to: folder.appendingPathComponent(stem + "-thumb.jpg"),
                                   options: .atomic)
                }
                try JSONEncoder().encode(meta)
                    .write(to: folder.appendingPathComponent(stem + ".json"), options: .atomic)
            } catch {
                failure = error.localizedDescription
            }
            await MainActor.run {
                guard let self else { return }
                if let failure {
                    self.notice = "無法儲存到圖庫：\(failure)"
                    appLog("gallery save failed: \(failure)", source: "sd")
                } else {
                    self.notice = nil
                    self.reload()
                }
            }
        }
    }

    func delete(_ item: Item) {
        let fm = FileManager.default
        let base = Self.folder.appendingPathComponent(item.id)
        for suffix in [".png", "-thumb.jpg", ".json"] {
            try? fm.removeItem(at: URL(fileURLWithPath: base.path + suffix))
        }
        items.removeAll { $0.id == item.id }
        reload()
    }

    // MARK: - Helpers

    private enum SaveError: LocalizedError {
        case encode
        var errorDescription: String? { "無法編碼圖片" }
    }

    /// UIImage is not Sendable but is only read here, after encoding finished.
    private struct Transfer: @unchecked Sendable {
        let image: UIImage
    }

    private static func stem(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let suffix = String(UUID().uuidString.prefix(6))
        return formatter.string(from: date) + "-" + suffix
    }

    /// Draws into a square-ish box no larger than `maxPixel`. A JPEG of this size
    /// decodes in a few milliseconds, which is what lets the grid load on demand.
    private nonisolated static func thumbnail(_ image: UIImage, maxPixel: Int) -> UIImage? {
        guard let cg = image.cgImage else { return nil }
        let longest = max(cg.width, cg.height)
        guard longest > 0 else { return nil }
        let ratio = min(1, Double(maxPixel) / Double(longest))
        let w = max(1, Int(Double(cg.width) * ratio))
        let h = max(1, Int(Double(cg.height) * ratio))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let out = ctx.makeImage() else { return nil }
        return UIImage(cgImage: out)
    }
}

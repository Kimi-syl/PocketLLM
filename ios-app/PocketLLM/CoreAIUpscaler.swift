import Foundation
import UIKit

/// Optional Neural-Engine upscaler built on Apple's Core AI framework.
///
/// Core AI is a *separate* path from `SdUpscaler`, not a replacement: it only
/// runs `.aimodel` files converted from PyTorch on a Mac, while the app ships
/// ESRGAN `.pth` weights that the engine loads directly. So this type never
/// makes the normal upscaler unavailable — it only decides whether the extra
/// button may be tapped.
///
/// Everything framework-specific sits behind `#if canImport(CoreAI)`, which is
/// false on the Xcode 26 toolchain the release build uses, so nothing here
/// references Core AI in that build at all. The runtime gate is separate on
/// purpose: an Xcode 27 build still has to run on a device older than iOS 27.
enum CoreAIUpscaler {

    /// True only when the build has the framework *and* the running OS has it.
    static var isAvailable: Bool {
        #if canImport(CoreAI)
        if #available(iOS 27.0, *) {
            return true
        }
        #endif
        return false
    }

    /// Short, user-facing reason the button is grey.
    static var status: String {
        if !isAvailable { return "需要 iOS 27" }
        if installedModel == nil { return "需要 .aimodel 模型" }
        return "可用"
    }

    /// Core AI's converted-model format.
    static let modelExtension = "aimodel"

    /// Converted models are looked up in the library, beside the GGUF weights.
    /// They are not downloadable from Hugging Face in this format, so the file
    /// has to be produced with `coreai-torch` and copied in by the user.
    static var installedModel: URL? {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let models = docs.appendingPathComponent("Models", isDirectory: true)
        guard let walker = FileManager.default.enumerator(
            at: models, includingPropertiesForKeys: nil) else { return nil }
        for case let url as URL in walker
        where url.pathExtension.lowercased() == modelExtension {
            return url
        }
        return nil
    }

    /// Whether the extra upscale row can run at all.
    static var canRun: Bool { isAvailable && installedModel != nil }

    enum Failure: LocalizedError {
        case unavailable(String)
        case inferenceNotImplemented

        var errorDescription: String? {
            switch self {
            case .unavailable(let why): return "Core AI 無法使用：\(why)"
            case .inferenceNotImplemented:
                return "Core AI 推論尚未接上：匯出 .aimodel 需要 Mac 上的 coreai-torch，等模型有了才會實作這一段"
            }
        }
    }

    /// Entry point for the button. The gate and the discovery are real; the
    /// inference is not, because no `.aimodel` exists to test against yet.
    static func upscale(_ image: UIImage) throws -> UIImage {
        guard isAvailable else { throw Failure.unavailable(status) }
        guard installedModel != nil else { throw Failure.unavailable(status) }
        throw Failure.inferenceNotImplemented
    }
}

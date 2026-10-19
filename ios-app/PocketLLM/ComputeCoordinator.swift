import Foundation

/// Serialises ownership of the GPU between the native and MLX engines.
///
/// Every on-device engine holds Metal: llama.cpp and stable-diffusion.cpp each
/// ship their own copy of ggml and its Metal backend, and MLX drives Metal
/// directly. Two live ggml-Metal backends in one process kill llama at
/// `llama_context` creation (inside `ggml_metal_buffer_is_shared`), and an MLX
/// load started while sdcpp still owns several GB of VRAM is what killed the
/// app on a chat/image tab switch. So at most one side may hold the GPU at a
/// time: loading an image model tears every text/vision engine down first, and
/// loading any text/vision engine tears the image engine down first.
///
/// The engines are per-tab `StateObject`s that never see each other, so they
/// register teardown closures here instead. The hand-off happens inside the
/// load call, before the new context is created, so the frees must be
/// synchronous: an async teardown would race the context it is making room for.
///
/// Closures are invoked on whatever thread called `freeImageEngine`/
/// `freeTextEngines` (the main thread for every current call site, since each
/// load is reached from a SwiftUI action), which is why the registry is
/// lock-guarded rather than actor-isolated. Engines whose teardown must run on
/// the main actor wrap it in `MainActor.assumeIsolated`.
final class ComputeCoordinator {
    static let shared = ComputeCoordinator()

    /// A "kind" rather than an object identity: views are recreated freely and
    /// a new engine of the same kind must replace the old registration instead
    /// of piling up stale closures that capture a dead object.
    enum Kind: String {
        case llama
        case mlx
        case vlm
    }

    private let lock = NSLock()
    private var textTeardowns: [Kind: () -> Void] = [:]
    private var imageTeardown: (() -> Void)?

    private init() {}

    /// Registers a text/vision engine (llama.cpp, MLX, or the MLX VLM). All of
    /// them must give up the GPU before the image engine builds its context.
    func registerTextEngine(_ kind: Kind, teardown: @escaping () -> Void) {
        lock.lock()
        textTeardowns[kind] = teardown
        lock.unlock()
    }

    /// Registers the image engine. It must give up the GPU before any text or
    /// vision engine builds its context.
    func registerImageEngine(teardown: @escaping () -> Void) {
        lock.lock()
        imageTeardown = teardown
        lock.unlock()
    }

    /// Frees every text/vision engine and its Metal buffers. Must complete
    /// before sdcpp builds its own Metal context.
    func freeTextEngines() {
        lock.lock()
        let teardowns = Array(textTeardowns.values)
        lock.unlock()
        for teardown in teardowns { teardown() }
    }

    /// The reverse: frees the image engine before a text/vision engine takes
    /// the GPU.
    func freeImageEngine() {
        lock.lock()
        let teardown = imageTeardown
        lock.unlock()
        teardown?()
    }
}

import Foundation

/// Serialises ownership of the GPU between the two native engines.
///
/// llama.cpp and stable-diffusion.cpp each ship their own copy of ggml and its
/// Metal backend. Two live ggml-Metal backends in one process kill llama at
/// `llama_context` creation (inside `ggml_metal_buffer_is_shared`), so at most
/// one engine may hold a GPU context at a time. Switching to image generation
/// tears the LLM down first; loading an LLM tears the image engine down first.
///
/// The engines are per-tab `StateObject`s that never see each other, so they
/// register a teardown closure here instead. The hand-off happens inside the
/// load/generate call, before the new context is created, so the frees must be
/// synchronous: an async teardown would race the context it is making room for.
///
/// Closures are invoked on whatever thread called `freeLLM`/`freeSD` (the main
/// thread for both current call sites), which is why the registry is lock-guarded
/// rather than actor-isolated.
final class ComputeCoordinator {
    static let shared = ComputeCoordinator()

    private let lock = NSLock()
    private var llmTeardown: (() -> Void)?
    private var sdTeardown: (() -> Void)?

    private init() {}

    func registerLLMTeardown(_ teardown: @escaping () -> Void) {
        lock.lock()
        llmTeardown = teardown
        lock.unlock()
    }

    func registerSDTeardown(_ teardown: @escaping () -> Void) {
        lock.lock()
        sdTeardown = teardown
        lock.unlock()
    }

    /// Frees the LLM and its Metal buffers. Must complete before SD builds its
    /// own Metal context.
    func freeLLM() {
        lock.lock()
        let teardown = llmTeardown
        lock.unlock()
        teardown?()
    }

    /// The reverse: frees the image engine before an LLM takes the GPU.
    func freeSD() {
        lock.lock()
        let teardown = sdTeardown
        lock.unlock()
        teardown?()
    }
}

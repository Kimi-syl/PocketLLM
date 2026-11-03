import Foundation

/// Decides whether the files in a model's slots can actually be loaded together.
///
/// The crash this exists to prevent: `taesd_path` is a slot on `new_sd_ctx`
/// like any other, and stable-diffusion.cpp merges whatever is in it into the
/// model it is already building (`model_loader.init_from_file(taesd, "tae.")`).
/// Handing it Wan 2.2's `taew2_2.safetensors` while the main model is SDXL does
/// not fail cleanly — the loader matches tensor *names* and then calls
/// `ggml_new_tensor` on the shapes it expected, which trips a `GGML_ABORT`
/// ("wrong shape in model metadata"). An abort is not an exception: it takes
/// the process down before any Swift error can be thrown, and the log line the
/// user sees is the crash report.
///
/// So the check has to happen before `new_sd_ctx`, and it has to be based on
/// what the files contain rather than what they are called. Two slots are
/// checked, because both are merged the same way and both can poison a load:
///
/// - `taesd`: the tiny decoder. Wan 2.2's is 3D (`encoder.<N>`/`decoder.<N>`
///   with 5-D convs); TAESD for SD/Flux is 2D.
/// - `vae`: a Wan VAE is 3D, every image VAE is 2D. Putting `wan_2.1_vae` in
///   the VAE slot of an SD1.5 model fails the same way.
///
/// The family signal is tensor *rank*, not names or architecture tags: a 3D
/// model's convolutions are 5-D, a 2D model's are 4-D. That holds for Wan,
/// Qwen-Image's 3D VAE, and every 2D checkpoint, and it cannot be fooled by a
/// renamed file or a missing `general.architecture`.
enum SlotCompat {

    /// What a weight file is shaped to decode.
    enum Family: String {
        /// 2D: SD1.5, SDXL, SD3, Flux, Z-Image and friends. Top tensor rank 4.
        case image2D
        /// 3D: Wan 2.x and the other video architectures. 5-D convolutions.
        case video3D
        /// Header unreadable, or no tensor of a decisive rank was sampled. Treated
        /// as "do not block": a file we cannot read is a load error the engine
        /// already reports clearly, and refusing to start on a guess would break
        /// formats that have not been seen yet.
        case unknown
    }

    enum Slot: String {
        case vae = "VAE"
        case tae = "微型解碼器 (TAE)"
    }

    /// The verdict for one load attempt.
    struct Verdict {
        /// Nil when the slots can be loaded together.
        var problem: String?
        var mainFamily: Family = .unknown
        var vaeFamily: Family = .unknown
        var taeFamily: Family = .unknown
        var isOK: Bool { problem == nil }
    }

    // MARK: - Family detection

    /// Classifies a weight file by the highest tensor rank in its header.
    ///
    /// Cached by path + size + mtime inside `ModelAudit`, so a slot edit cannot
    /// pick up stale facts after the file at that path is replaced.
    static func family(of url: URL) -> Family {
        let facts = ModelAudit.facts(of: url)
        switch facts.maxTensorRank {
        case 5...: return .video3D
        case 4: return .image2D
        default: return .unknown
        }
    }

    static func family(ofPath path: String) -> Family {
        guard !path.isEmpty else { return .unknown }
        return family(of: URL(fileURLWithPath: path))
    }

    // MARK: - Validation

    /// Checks every slot that gets merged into the main model.
    ///
    /// - Parameters:
    ///   - main: the diffusion model, or the all-in-one checkpoint.
    ///   - vae: `vae_path`; may be empty.
    ///   - tae: `taesd_path`; may be empty.
    static func validate(main: String, vae: String, tae: String) -> Verdict {
        var verdict = Verdict()

        verdict.mainFamily = family(ofPath: main)
        verdict.vaeFamily = family(ofPath: vae)
        verdict.taeFamily = family(ofPath: tae)

        // The TAE is the slot that actually crashed. Compare it first so its
        // message wins over the VAE's when both are wrong.
        if let problem = mismatch(slot: .tae, slotPath: tae, slotFamily: verdict.taeFamily,
                                  mainPath: main, mainFamily: verdict.mainFamily) {
            verdict.problem = problem
            return verdict
        }
        if let problem = mismatch(slot: .vae, slotPath: vae, slotFamily: verdict.vaeFamily,
                                  mainPath: main, mainFamily: verdict.mainFamily) {
            verdict.problem = problem
            return verdict
        }

        // A tiny decoder in the VAE slot (or the reverse) is the same mistake
        // wearing the other label, and the engine accepts both silently until a
        // shape check fails. Say what the file actually is.
        if !tae.isEmpty, ModelAudit.facts(of: URL(fileURLWithPath: tae)).hasVAE {
            verdict.problem =
                "「\(fileName(tae))」放進了微型解碼器 (TAE) 插槽，但它是一個完整 VAE。"
                + "請把它移到 VAE 插槽，或改放真正的 TAE（例如 taew2_2.safetensors）。"
            return verdict
        }

        return verdict
    }

    private static func mismatch(slot: Slot,
                                 slotPath: String,
                                 slotFamily: Family,
                                 mainPath: String,
                                 mainFamily: Family) -> String? {
        guard slotFamily != .unknown, mainFamily != .unknown, slotFamily != mainFamily else {
            return nil
        }
        let slotKind = slotFamily == .video3D ? "3D（影片）" : "2D（圖片）"
        let mainKind = mainFamily == .video3D ? "3D（影片）" : "2D（圖片）"
        let replacement = slot == .tae ? "taew2_2.safetensors 等 3D 版本" : "3D 的 wan VAE"
        let imageNote = "2D 的 VAE / TAESD（SD1.5、SDXL、Flux 等）"
        return """
        \(slot.rawValue) 插槽與主模型架構不符：載入會在 C++ 引擎內中止（wrong shape in model metadata），所以先擋下來。

        \(slot.rawValue)：「\(fileName(slotPath))」是 \(slotKind) 模型
        主模型：「\(fileName(mainPath))」是 \(mainKind) 模型

        圖片模型需要 \(imageNote)；Wan 影片模型需要 \(replacement)。請換掉 \(slot.rawValue) 插槽再試一次。
        """
    }

    private static func fileName(_ path: String) -> String {
        guard !path.isEmpty else { return "（空）" }
        return URL(fileURLWithPath: path).lastPathComponent
    }
}

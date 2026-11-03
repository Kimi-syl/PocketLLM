import Foundation

/// Decides whether the files in a model's slots can be loaded together.
///
/// The crash this originally existed to prevent: `taesd_path` is merged into the
/// model stable-diffusion.cpp is already building (`init_from_file(taesd, "tae.")`),
/// and a file from another family reaches `ggml_new_tensor` with the wrong shape
/// and hits `GGML_ABORT`. That is a process abort, not a NULL return, so nothing
/// downstream can recover — the check has to run before `new_sd_ctx`.
///
/// ## What the first version got wrong
///
/// It classified files by tensor *rank*: 5-D convolution meant "3D/video", 4-D
/// meant "2D/image". That is wrong for the very bundle it needed to protect, and
/// it rejected it on device with a "mismatched" error. The real Wan 2.2 tiny
/// decoder, `taew2_2.safetensors`, is **4-D convolutions throughout**
/// (`decoder.1.weight = [256, 48, 3, 3]`, 128 tensors, max rank 4); only the DiT
/// carries a single 5-D `patch_embedding` weight. Rank therefore separates
/// nothing here.
///
/// ## What it uses instead
///
/// Two signals that are actually in the files, both checked against the real
/// Wan bundle from `jc-builds/Wan2.2-TI2V-5B-Turbo-iOS`:
///
/// - **`general.architecture`** from the GGUF header. The real DiT says `wan`.
///   No guessing: when the tag is absent or unrecognised the family is
///   `.unknown` and nothing is blocked.
/// - **Tensor naming** for tiny decoders: Wan's is `decoder.<N>.…` with a
///   numeric top-level block (`decoder.10.conv.0.weight`), TAESD's is
///   `decoder.layers.<N>.…`, and a full VAE says `up_blocks`/`conv_in`. Those
///   three are distinguishable without a device.
///
/// The rule is deliberately conservative: reject only a pair that is certainly
/// wrong, and stay out of the way otherwise. A false rejection breaks a working
/// configuration, which is worse than the crash it is trying to prevent.
enum SlotCompat {

    /// What a weight file is, as far as its own header says.
    enum Family: String {
        /// A video architecture: Wan, LTX-V, AnimateDiff.
        case video
        /// A 2D image architecture.
        case image
        /// The header did not say, or said something unrecognised. Never blocks.
        case unknown
    }

    /// What a decoder file is, by tensor naming.
    enum DecoderKind: String {
        /// Wan 2.2's tiny decoder: `decoder.<N>.…` with numeric top-level blocks.
        case wanTiny
        /// TAESD for SD/SDXL/Flux: `decoder.layers.<N>.…`.
        case taesd
        /// A full VAE (`up_blocks`, `conv_in`, `first_stage_model.`).
        case fullVAE
        /// Not recognisable as any decoder.
        case other
    }

    enum Slot: String {
        case vae = "VAE"
        case tae = "微型解碼器 (TAE)"
    }

    struct Verdict {
        var problem: String?
        var mainFamily: Family = .unknown
        var taeKind: DecoderKind = .other
        var vaeKind: DecoderKind = .other
        var isOK: Bool { problem == nil }
    }

    // MARK: - Facts

    /// Architectures the engine treats as video.
    private static let videoArchitectures: Set<String> = [
        "wan", "wan21", "wan22", "wan2.1", "wan2.2", "ltxv", "ltx", "animatediff", "lingbot",
    ]

    /// Architectures that are 2D image models. Anything not listed stays unknown
    /// rather than being assumed 2D.
    private static let imageArchitectures: Set<String> = [
        "flux", "sd1", "sd2", "sdxl", "sd3", "stable-diffusion", "z-image",
        "zimage", "qwen_image", "qwen-image", "hunyuan", "chroma", "moss", "iris", "pid",
    ]

    static func family(of file: Facts) -> Family {
        if let arch = file.architecture?.lowercased() {
            if videoArchitectures.contains(arch) { return .video }
            if imageArchitectures.contains(arch) { return .image }
        }
        return .unknown
    }

    static func family(ofPath path: String) -> Family {
        guard !path.isEmpty else { return .unknown }
        return family(of: ModelAudit.facts(of: URL(fileURLWithPath: path)))
    }

    /// Classifies a decoder by its tensor names — the only signal that separates
    /// Wan's 4-D tiny decoder from a 4-D TAESD.
    static func decoderKind(of file: Facts) -> DecoderKind {
        let names = file.tensorNames
        guard !names.isEmpty else { return .other }

        if file.hasVAE { return .fullVAE }

        // Wan's tiny decoder: a numeric block directly under decoder./encoder.
        var numericBlocks = 0
        var layerBlocks = 0
        for name in names {
            for stem in ["decoder.", "encoder."] where name.hasPrefix(stem) {
                let rest = name.dropFirst(stem.count)
                if let first = rest.first, first.isNumber { numericBlocks += 1 }
                if rest.hasPrefix("layers.") { layerBlocks += 1 }
                break
            }
        }
        if numericBlocks >= 8 { return .wanTiny }
        if layerBlocks >= 4 { return .taesd }
        return .other
    }

    // MARK: - Validation

    static func validate(main: String, vae: String, tae: String) -> Verdict {
        var verdict = Verdict()
        let mainFacts = main.isEmpty ? Facts() : ModelAudit.facts(of: URL(fileURLWithPath: main))
        let vaeFacts = vae.isEmpty ? Facts() : ModelAudit.facts(of: URL(fileURLWithPath: vae))
        let taeFacts = tae.isEmpty ? Facts() : ModelAudit.facts(of: URL(fileURLWithPath: tae))

        verdict.mainFamily = family(of: mainFacts)
        verdict.taeKind = tae.isEmpty ? .other : decoderKind(of: taeFacts)
        verdict.vaeKind = vae.isEmpty ? .other : decoderKind(of: vaeFacts)

        // A full VAE in the tiny-decoder slot is wrong whatever the architecture:
        // the engine merges it as "tae." and the shapes cannot match. This one is
        // decidable from the file alone.
        if !tae.isEmpty, verdict.taeKind == .fullVAE {
            verdict.problem = """
            「\(fileName(tae))」放進了微型解碼器 (TAE) 插槽，但它是一個完整 VAE（有 up_blocks / conv_in）。
            請把它移到 VAE 插槽，或改放真正的 TAE。
            """
            return verdict
        }

        // Cross-architecture checks only when the main model's tag is known.
        switch (verdict.mainFamily, verdict.taeKind) {
        case (.image, .wanTiny):
            verdict.problem = """
            主模型「\(fileName(main))」是圖片模型，但 TAE 插槽放的是 Wan 的 3D 微型解碼器「\(fileName(tae))」。
            兩者形狀不合，載入會在引擎內中止，所以先擋下來。
            """
            return verdict
        case (.video, .taesd):
            verdict.problem = """
            主模型「\(fileName(main))」是影片模型，但 TAE 插槽放的是 SD/Flux 的 TAESD「\(fileName(tae))」。
            請改用 Wan 的 taew2_2.safetensors。
            """
            return verdict
        default:
            break
        }

        return verdict
    }

    private static func fileName(_ path: String) -> String {
        guard !path.isEmpty else { return "（空）" }
        return URL(fileURLWithPath: path).lastPathComponent
    }
}

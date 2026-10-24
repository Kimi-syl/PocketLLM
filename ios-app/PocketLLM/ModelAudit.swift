import Foundation

/// Whether a downloaded or imported image model is actually loadable, and what
/// to do about it when it is not.
///
/// The engine needs a diffusion model plus a conditioner and a VAE. Repositories
/// distribute those in three shapes, and only the files tell them apart:
///
/// - a single checkpoint that carries the other two inside it (all-in-one);
/// - a split repository with `diffusion_models/`, `text_encoders/` and `vae/`;
/// - a bare diffusion transformer — the most common GGUF case — whose README
///   says which companions to fetch from other repositories.
///
/// This type answers both questions: from a repository listing (before the
/// download) and from the files on disk (after it).
struct RepoAudit {
    enum Part: String, CaseIterable, Identifiable {
        case textEncoder, vae

        var id: String { rawValue }
        var label: String {
            switch self {
            case .textEncoder: return "文字編碼器 (TE)"
            case .vae: return "VAE"
            }
        }
    }

    struct Link: Identifiable, Hashable {
        let title: String
        let url: URL
        var id: String { url.absoluteString }
    }

    /// The listing looks like an image repository at all.
    var isImageRepo = false
    /// The repository is distributed as separate parts (or its README documents
    /// companions), so a lone diffusion file is not enough.
    var isSplit = false
    /// Repository paths that hold each companion, when the repo ships it.
    var encoderPaths: [String] = []
    var vaePaths: [String] = []
    /// Companion download pages found in the README, for parts the repo lacks.
    var links: [Link] = []

    /// Parts the repository itself does not provide, so the README's links are
    /// the answer.
    var absentParts: [Part] {
        guard isSplit else { return [] }
        var parts: [Part] = []
        if encoderPaths.isEmpty { parts.append(.textEncoder) }
        if vaePaths.isEmpty { parts.append(.vae) }
        return parts
    }

    /// Parts missing from a specific selection of repository paths.
    func missing(in selection: Set<String>) -> [Part] {
        guard isSplit, !selection.isEmpty else { return [] }
        let chosen = selection.map { $0.lowercased() }
        func covered(_ paths: [String]) -> Bool {
            paths.contains { path in chosen.contains { $0.hasPrefix(path.lowercased()) } }
        }
        var parts: [Part] = []
        if !encoderPaths.isEmpty && !covered(encoderPaths) { parts.append(.textEncoder) }
        if !vaePaths.isEmpty && !covered(vaePaths) { parts.append(.vae) }
        return parts
    }
}

enum ModelAudit {

    /// `general.architecture` values written by diffusion converters. Anything
    /// else — llama, qwen2, gemma3, qwen2vl — is a chat or vision model.
    static let imageArchitectures: Set<String> = [
        "flux", "flux2", "flux3", "sd1", "sd2", "sd3", "sdxl", "qwen_image",
        "wan", "hunyuan_video", "hidream", "chroma", "kolors", "pixart",
        "aura", "lens", "ovis", "mage_flow", "sefi", "z_image", "anima",
        "longcat", "krea2", "minimax_h3", "moss_image", "boogu_image",
        "ernie_image", "llada_image", "iris", "pid", "ideogram4",
    ]

    /// File-name markers for the diffusion weights themselves, used when the
    /// browser was not already in the Image category.
    private static let mainMarkers = [
        "diffusion", "unet", "transformer", "flux", "qwen-image", "qwen_image",
        "sd3", "wan2", "hunyuan", "kolors", "chroma", "hidream", "pixart",
    ]

    /// Tensor prefixes that only diffusion weights carry. sd.cpp's GGUF
    /// converter writes **no** `general.architecture` at all (a real SD1.5
    /// all-in-one GGUF has zero KV pairs), and an all-in-one checkpoint's file
    /// name says nothing either, so the tensors are the only honest evidence.
    private static let diffusionPrefixes = [
        "model.diffusion_model.", "first_stage_model.", "cond_stage_model.",
        "conditioner.embedders.", "text_encoders.", "double_blocks.",
        "single_blocks.", "joint_blocks.", "input_blocks.", "output_blocks.",
        "middle_block.", "unet.", "transformer.", "vae.",
    ]

    /// True when the file's own tensors say it is diffusion weights. Reads a
    /// header only; the name and the extension are not trusted.
    static func isDiffusionFile(_ url: URL) -> Bool {
        let names: [String]
        switch url.pathExtension.lowercased() {
        case "gguf":
            guard let info = GGUFHeader.read(url, tensorSample: 100_000) else { return false }
            if let arch = info.architecture?.lowercased(),
               imageArchitectures.contains(arch) { return true }
            names = info.tensorNames.map { $0.lowercased() }
        case "safetensors", "sft":
            guard let keys = safetensorsKeys(url) else { return false }
            names = keys.map { $0.lowercased() }
        default:
            return false
        }
        return diffusionPrefixes.contains { prefix in
            names.contains { $0.hasPrefix(prefix) }
        }
    }

    static func isImageWeight(path: String) -> Bool {
        let lower = path.lowercased()
        return mainMarkers.contains { lower.contains($0) }
    }

    // MARK: - Repository listing

    /// What a repository provides, from its file list and README. `kind` is the
    /// browser category the user searched in.
    static func audit(
        files: [HuggingFaceClient.FileEntry],
        readme: String?,
        kind: ModelStore.Kind
    ) -> RepoAudit {
        var audit = RepoAudit()
        let paths = files.map(\.path)
        // The Image category is explicit; elsewhere only a recognisable
        // diffusion weight opts in, so a chat repo is never audited.
        let looksImage = kind == .image || paths.contains(where: isImageWeight)
        guard looksImage else { return audit }
        audit.isImageRepo = true

        let main = paths.filter(isMainWeight)
        guard !main.isEmpty else { return audit }

        audit.encoderPaths = paths.filter(isTextEncoderPath)
        audit.vaePaths = paths.filter(isVAEPath)

        // A `diffusion_models/` (or Comfy's `split_files/`) folder is the
        // repository saying the parts are separate. The README is the fallback
        // for repos that put the transformer at the root (unsloth's GGUFs).
        let folderSplit = paths.contains {
            let lower = $0.lowercased()
            return lower.hasPrefix("diffusion_models/") || lower.hasPrefix("split_files/")
        }
        audit.isSplit = folderSplit || readmeRequiresCompanions(readme)
        guard audit.isSplit else { return audit }

        audit.links = linkSuggestions(readme: readme, parts: audit.absentParts)
        return audit
    }

    static func isMainWeight(_ path: String) -> Bool {
        let lower = path.lowercased()
        guard ModelStore.Kind.image.accepts(path: path) else { return false }
        if isTextEncoderPath(path) || isVAEPath(path) { return false }
        if lower.contains("lora") || lower.contains("controlnet")
            || lower.contains("mmproj") || lower.contains("upscal") { return false }
        return true
    }

    static func isTextEncoderPath(_ path: String) -> Bool {
        let lower = path.lowercased()
        let name = (lower as NSString).lastPathComponent
        if lower.contains("text_encoder") || lower.contains("text-encoder") { return true }
        if name.contains("qwen-image") || name.contains("qwen_image") { return false }
        let markers = [
            "t5", "umt5", "clip_l", "clip-l", "clip_g", "clip-g",
            "qwen3", "qwen2.5-vl", "qwen2_5_vl", "qwen2.5_vl", "llm",
            "gemma", "llama_3", "llama-3",
        ]
        return markers.contains { name.contains($0) }
    }

    /// `ae.safetensors` is FLUX's name for the VAE, and `flux_ae` is what a
    /// downloaded copy is often called; token matching keeps names like
    /// `diffusion_pytorch_model.safetensors` out.
    static func isVAEName(_ name: String) -> Bool {
        let lower = name.lowercased()
        if lower.contains("vae") || lower.contains("autoencoder") { return true }
        let stem = (lower as NSString).deletingPathExtension
        return stem.split(whereSeparator: { $0 == "_" || $0 == "-" || $0 == "." })
            .contains("ae")
    }

    static func isVAEPath(_ path: String) -> Bool {
        let lower = path.lowercased()
        return lower.contains("vae/") || isVAEName((lower as NSString).lastPathComponent)
    }

    // MARK: - README

    /// True when the README tells the reader to fetch companions as their own
    /// files. Only lines that also mention a file or a Hub link count, so prose
    /// like "the VAE is baked in" does not trigger it.
    static func readmeRequiresCompanions(_ readme: String?) -> Bool {
        guard let readme else { return false }
        for line in readme.split(separator: "\n") {
            let lower = line.lowercased()
            let mentionsVae = lower.contains("vae") || lower.contains("ae.safetensors")
            let mentionsEncoder = lower.contains("text encoder") || lower.contains("text_encoder")
                || lower.contains("te.gguf") || lower.contains("t5") || lower.contains("clip-l")
                || lower.contains("clip_l") || lower.contains("qwen3-vl")
            guard mentionsVae || mentionsEncoder else { continue }
            if lower.contains("baked") || lower.contains("no separate")
                || lower.contains("included in") || lower.contains("built-in") { continue }
            let namesAFile = lower.contains(".safetensors") || lower.contains(".gguf")
            if namesAFile || lower.contains("huggingface.co") { return true }
        }
        return false
    }

    /// Download pages for the companions a repository does not ship. Links are
    /// taken from the README and matched to a part by the words around them.
    static func linkSuggestions(readme: String?, parts: [RepoAudit.Part]) -> [RepoAudit.Link] {
        guard let readme, !parts.isEmpty else { return [] }
        var found: [RepoAudit.Link] = []
        var seen = Set<String>()

        for line in readme.split(separator: "\n") {
            let lower = line.lowercased()
            let part: RepoAudit.Part
            if lower.contains("vae") || lower.contains("ae.safetensors") {
                part = .vae
            } else if lower.contains("text encoder") || lower.contains("text_encoder")
                || lower.contains("t5") || lower.contains("clip") || lower.contains("qwen3-vl") {
                part = .textEncoder
            } else {
                continue
            }
            guard parts.contains(part) else { continue }
            for raw in urls(in: line) where raw.contains("huggingface.co") {
                guard seen.insert(raw).inserted else { continue }
                guard let url = URL(string: raw) else { continue }
                found.append(RepoAudit.Link(title: shortTitle(for: raw, part: part), url: url))
            }
        }
        return Array(found.prefix(4))
    }

    private static func urls(in line: Substring) -> [String] {
        guard let regex = try? NSRegularExpression(
            pattern: #"https://[^\s)\]"'<>]+"#) else { return [] }
        let text = String(line)
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap {
            Range($0.range, in: text).map { String(text[$0]) }
        }
    }

    private static func shortTitle(for raw: String, part: RepoAudit.Part) -> String {
        let path = raw.replacingOccurrences(of: "https://huggingface.co/", with: "")
            .replacingOccurrences(of: "/tree/main", with: "")
            .replacingOccurrences(of: "/resolve/main", with: "")
        let trimmed = path.split(separator: "/").prefix(3).joined(separator: " / ")
        return "\(part.label)：\(trimmed)"
    }

    // MARK: - Files on disk

    /// Parts still missing for a bundle that `ModelInspector` built from a
    /// repository folder. Empty means it can load.
    ///
    /// A single weight is only "all-in-one" when its own tensor list says so:
    /// the engine's single-file checkpoints carry `first_stage_model.` /
    /// `cond_stage_model.` (safetensors) or `decoder.*` (GGUF) inside the file,
    /// while a bare diffusion transformer carries only its own blocks.
    static func missingParts(in bundle: ModelBundle) -> [RepoAudit.Part] {
        guard bundle.isAllInOne else {
            var parts: [RepoAudit.Part] = []
            if !bundle.hasConditioner { parts.append(.textEncoder) }
            if bundle.vae == nil { parts.append(.vae) }
            return parts
        }
        // One weight: the slot matcher cannot see inside it, so its own tensor
        // names decide. An unreadable header is treated as loadable rather than
        // nagging about every file the reader does not know.
        if let evidence = Self.bundledParts(of: bundle.mainModel) {
            return evidence ? [] : [.textEncoder, .vae]
        }
        return []
    }

    /// VAE prefixes, taken from the engine's own name lists
    /// (`first_stage_model_prefix_vec` in `name_conversion.cpp`) plus the
    /// diffusers spelling a converted all-in-one file keeps.
    private static let vaePrefixes = [
        "first_stage_model.", "vae.", "autoencoder.", "decoder.",
    ]

    /// Conditioner prefixes: `cond_stage_model*` and `text_encoders.*` are the
    /// engine's; `conditioner.embedders.` is what an untouched SDXL checkpoint
    /// uses, and `te.`/`text_encoder.` are the diffusers spellings.
    private static let conditionerPrefixes = [
        "cond_stage_model.", "conditioner.embedders.", "text_encoders.",
        "text_encoder.", "text_encoder_2.", "te.", "te1.", "te2.", "llm.",
    ]

    private static func hasPrefix(_ names: [String], _ prefixes: [String]) -> Bool {
        names.contains { name in prefixes.contains { name.hasPrefix($0) } }
    }

    /// True when the file contains its own conditioner/VAE, false when it is a
    /// bare transformer, nil when that cannot be read.
    private static func bundledParts(of url: URL) -> Bool? {
        let names: [String]
        switch url.pathExtension.lowercased() {
        case "gguf":
            // Every tensor name, not a sample: an all-in-one SD3.5 Large puts
            // its VAE and encoder after several thousand diffusion tensors.
            guard let info = GGUFHeader.read(url, tensorSample: 100_000) else { return nil }
            names = info.tensorNames.map { $0.lowercased() }
        case "safetensors", "sft":
            guard let keys = safetensorsKeys(url) else { return nil }
            names = keys.map { $0.lowercased() }
        default:
            return nil
        }
        return hasPrefix(names, vaePrefixes) && hasPrefix(names, conditionerPrefixes)
    }

    /// Key names from a safetensors header; only the names, no tensor data.
    private static func safetensorsKeys(_ url: URL) -> [String]? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let lengthBytes = try? handle.read(upToCount: 8), lengthBytes.count == 8 else {
            return nil
        }
        let length = lengthBytes.withUnsafeBytes { $0.loadUnaligned(as: UInt64.self) }
        guard length > 2, length < 64 * 1024 * 1024,
              let json = try? handle.read(upToCount: Int(length)),
              let object = try? JSONSerialization.jsonObject(with: json) as? [String: Any]
        else { return nil }
        return object.keys.filter { $0 != "__metadata__" }
    }
}

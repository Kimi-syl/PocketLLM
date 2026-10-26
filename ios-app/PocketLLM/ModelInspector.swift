import Foundation

/// What a model can actually do, read from its own metadata rather than guessed
/// from its name.
struct ModelCapabilities {
    var isVision = false
    var architectures: [String] = []
    var modelType = ""
    var evidence = ""

    var summary: String {
        isVision ? "支援視覺 · 可分析圖片" : "純文字模型"
    }
}

enum ModelInspector {

    /// Keys that appear only on multimodal checkpoints. `vision_config` covers
    /// most Qwen-VL / Llava / SmolVLM layouts, `mm_projector_type` the Llava
    /// family, and the image-token keys the remainder.
    private static let visionKeys = [
        "vision_config", "image_token_index", "vision_feature_layer",
        "mm_projector_type", "vision_feature_select_strategy",
        "image_seq_length", "vision_start_token_id",
    ]

    /// Inspects an MLX model directory (config.json + weights).
    static func inspect(directory: URL) -> ModelCapabilities {
        var caps = ModelCapabilities()
        let config = directory.appendingPathComponent("config.json")
        guard let data = try? Data(contentsOf: config),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            caps.evidence = "找不到 config.json"
            return caps
        }

        caps.modelType = (json["model_type"] as? String) ?? ""
        caps.architectures = (json["architectures"] as? [String]) ?? []

        let found = visionKeys.filter { json[$0] != nil }
        let arch = caps.architectures.map { $0.lowercased() }
        let archVision = arch.contains {
            $0.contains("vl") || $0.contains("vision") || $0.contains("llava") || $0.contains("paligemma")
        }
        caps.isVision = !found.isEmpty || archVision

        if !found.isEmpty {
            caps.evidence = "config.json 含 " + found.joined(separator: ", ")
        } else if archVision {
            caps.evidence = "architectures = " + caps.architectures.joined(separator: ", ")
        } else {
            caps.evidence = "config.json 未見視覺欄位"
        }
        return caps
    }

    /// A GGUF file cannot be inspected the same way, but llama.cpp's multimodal
    /// builds pair a text GGUF with a separate projection (mmproj) file, so we
    /// look for one next to it.
    static func companionProjection(for model: URL) -> URL? {
        let dir = model.deletingLastPathComponent()
        guard let entries = try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil) else { return nil }
        return entries.first {
            let n = $0.lastPathComponent.lowercased()
            return n.hasSuffix(".gguf") && (n.contains("mmproj") || n.contains("projector"))
        }
    }
}

/// One repository's downloaded files, matched into the slots the image engine
/// takes. A single-file checkpoint matches as "all in one": the main model alone
/// and no companions, which is exactly how `SdEngine` loads it.
struct ModelBundle: Identifiable, Hashable {
    let id: String
    let name: String
    let folder: URL
    let mainModel: URL
    let textEncoder: URL?
    let vae: URL?
    /// A tiny decoder (`taesd_path`). Wan 2.2's bundle carries one because a
    /// full VAE decode of a dozen frames is the chain that trips the GPU
    /// watchdog; it is not a VAE and does not go in the vae slot.
    let tae: URL?
    /// `tokenizer.json`, for the models whose conditioner is an LLM the engine
    /// cannot tokenize itself (MOSS-Image's Qwen3.5, PiD, Lens).
    let tokenizer: URL?
    let clipL: URL?
    let clipG: URL?
    let t5: URL?
    /// Optional ESRGAN weights, picked up from the generated image's folder so
    /// the upscaler slot is configured by the same download.
    let upscaler: URL?
    let size: Int64
    let isAllInOne: Bool
    /// Set when the folder is recognisable but the engine cannot load it, e.g.
    /// a diffusers pipeline directory of sharded safetensors. nil otherwise.
    var problem: String?

    /// A conditioner is any of the encoder slots: the LLM/Qwen slot for newer
    /// models, T5 or CLIP-L/G for SD3/SDXL-style ones.
    var hasConditioner: Bool {
        textEncoder != nil || t5 != nil || clipL != nil || clipG != nil
    }

    /// Slots this bundle can fill. All-in-one means "this file needs nothing
    /// else"; whether that is *true* for a bare diffusion transformer is decided
    /// from its tensor names by `ModelAudit`.
    var isComplete: Bool { isAllInOne || (hasConditioner && vae != nil) }

    /// `author/repo`. Scans rebuild the name from the folder, which encodes the
    /// slash as `--`.
    var repoId: String {
        name.contains("/") ? name : name.replacingOccurrences(of: "--", with: "/")
    }

    /// What "已自動配置" is claiming, spelled out for the row.
    var companionSummary: String {
        if isAllInOne { return "單檔完整模型" }
        var parts: [String] = []
        if let textEncoder { parts.append("TE: \(textEncoder.lastPathComponent)") }
        if let vae { parts.append("VAE: \(vae.lastPathComponent)") }
        if clipL != nil { parts.append("CLIP-L") }
        if clipG != nil { parts.append("CLIP-G") }
        if t5 != nil { parts.append("T5") }
        if let upscaler { parts.append("ESRGAN: \(upscaler.lastPathComponent)") }
        return parts.joined(separator: " · ")
    }
}

extension ModelInspector {

    /// Scans one repository folder and returns a bundle when it holds enough to
    /// load: a main model plus a text encoder and a VAE, or a single all-in-one
    /// checkpoint. Uses names and extensions only — the engine itself is the
    /// final authority on whether a file loads.
    static func bundle(in folder: URL, repoId: String? = nil, evidence: Bool = false) -> ModelBundle? {
        let fileManager = FileManager.default
        var weights: [URL] = []
        // Not a weight, but a load requirement for the LLM-conditioned models
        // (MOSS-Image's Qwen3.5, PiD, Lens), so it is collected on the same walk.
        var tokenizer: URL?
        guard let enumerator = fileManager.enumerator(
            at: folder, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey])
        else { return nil }
        for case let url as URL in enumerator {
            let ext = url.pathExtension.lowercased()
            // .pth/.pt only ever qualify as an ESRGAN model; diffusion weights
            // in those formats are not something the engine loads.
            if url.lastPathComponent.lowercased() == "tokenizer.json" {
                tokenizer = url
                continue
            }
            guard ["gguf", "safetensors", "sft", "pth", "pt"].contains(ext) else { continue }
            weights.append(url)
        }
        guard !weights.isEmpty else { return nil }

        var mains: [URL] = []
        var textEncoders: [URL] = []
        var vaes: [URL] = []
        var taes: [URL] = []
        var upscalers: [URL] = []
        var clipL: URL?
        var clipG: URL?
        var t5: URL?

        for url in weights {
            let name = url.lastPathComponent.lowercased()
            let ext = url.pathExtension.lowercased()
            let relative = url.path.replacingOccurrences(of: folder.path + "/", with: "")
                .lowercased()

            // Not weights the image engine takes as a slot.
            if name.contains("mmproj") || name.contains("projector")
                || name.contains("lora") || name.contains("controlnet") { continue }

            if name.contains("esrgan") || name.contains("upscal")
                || name.contains("ultrasharp") {
                upscalers.append(url)
            } else if !["gguf", "safetensors", "sft"].contains(ext) {
                // A .pth that is not an upscaler is not loadable here.
                continue
            } else if relative.contains("vae/") || ModelAudit.isVAEName(name) {
                vaes.append(url)
            } else if name.contains("clip_l") || name.contains("clip-l") {
                clipL = url
            } else if name.contains("clip_g") || name.contains("clip-g") {
                clipG = url
            } else if name.contains("t5") || name.contains("umt5") {
                t5 = url
            } else if isTextEncoder(name: name, relative: relative) {
                textEncoders.append(url)
            } else if !SdEngine.isVideoCheckpoint(url.lastPathComponent) {
                mains.append(url)
            }
        }

        // Files whose name said nothing are the ones a VAE hides in: `ae`,
        // `kl-f8-anime2`, `taesd`, or a plain `diffusion_pytorch_model` inside a
        // `vae/` folder. Their tensors decide. Only the smallest candidates are
        // read (a VAE is small), so a folder of twenty quants does not pay for
        // twenty headers.
        if evidence {
            let candidates = Set(mains
                .filter { !ModelAudit.isImageWeight(path: $0.lastPathComponent) }
                .sorted { fileSize($0) < fileSize($1) }
                .prefix(8)
                .map(\.path))
            var resolved: [URL] = []
            for url in mains {
                guard candidates.contains(url.path) else {
                    resolved.append(url)
                    continue
                }
                switch ModelAudit.facts(of: url).role {
                case .vae: vaes.append(url)
                case .tae: taes.append(url)
                case .textEncoder: textEncoders.append(url)
                default: resolved.append(url)
                }
            }
            mains = resolved
        }

        guard let main = mains.max(by: { fileSize($0) < fileSize($1) }) else { return nil }
        let encoder = textEncoders.max(by: { fileSize($0) < fileSize($1) })
        let vae = vaes.max(by: { fileSize($0) < fileSize($1) })
        let tae = taes.max(by: { fileSize($0) < fileSize($1) })
        let upscaler = upscalers.max(by: { fileSize($0) < fileSize($1) })
        let allInOne = encoder == nil && vae == nil

        let bundle = ModelBundle(
            id: folder.lastPathComponent,
            name: repoId ?? folder.lastPathComponent.replacingOccurrences(of: "--", with: "/"),
            folder: folder,
            mainModel: main,
            textEncoder: encoder,
            vae: vae,
            tae: tae,
            tokenizer: tokenizer,
            clipL: clipL,
            clipG: clipG,
            t5: t5,
            upscaler: upscaler,
            size: directorySize(folder),
            isAllInOne: allInOne,
            problem: nil)

        // Incomplete sets are returned too. Import has to recognise a lone
        // diffusion transformer as an image model (that is what a FLUX or
        // FLUX.3 repository is), and the library shows it as missing its
        // companions instead of hiding it; callers that need a loadable set
        // check `ModelAudit.missingParts` or `isComplete`.
        return bundle
    }

    /// Names that mark a repository's conditioner rather than its diffusion
    /// model. `qwen-image` is checked first because Qwen-Image's *diffusion*
    /// weights also contain "qwen".
    private static func isTextEncoder(name: String, relative: String) -> Bool {
        // "text_encoders" is the folder name in the split-file image repos, so
        // match the prefix without a trailing slash.
        if relative.contains("text_encoder") || relative.contains("text-encoder") { return true }
        if name.contains("qwen-image") || name.contains("qwen_image") { return false }
        let markers = [
            "text_encoder", "text-encoder", "textencoder", "llm",
            "qwen3", "qwen2.5-vl", "qwen2_5_vl", "qwen2.5_vl",
            // Image models do not all use T5: Sana/Lumina use Gemma-2 and
            // HiDream uses Llama-3.1.
            "gemma", "llama_3", "llama-3",
        ]
        return markers.contains { name.contains($0) }
    }

    private static func fileSize(_ url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private static func directorySize(_ url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: url, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in enumerator {
            total += fileSize(file)
        }
        return total
    }
}

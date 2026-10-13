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

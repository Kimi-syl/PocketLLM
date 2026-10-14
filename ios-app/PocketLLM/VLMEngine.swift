import Foundation
import MLX
import MLXLMCommon
import MLXVLM
import Tokenizers

/// Photo analysis through a vision-language model.
///
/// Uses MLXVLM, which is the VLM half of mlx-swift-lm. Models are ordinary MLX
/// directories (config.json + safetensors), e.g.
/// mlx-community/Qwen2.5-VL-3B-Instruct-4bit — no conversion step.
@MainActor
final class VLMEngine: ObservableObject {

    @Published private(set) var status = "未載入模型"
    @Published private(set) var answer = ""
    @Published private(set) var isBusy = false
    @Published var prompt = "請詳細描述這張照片的內容"

    private var container: ModelContainer?
    private var session: ChatSession?
    private(set) var directory: URL?

    var isReady: Bool { session != nil }

    /// MLX VLMs need more than weights: the image preprocessing constants
    /// (size, mean, std, patch size) live in preprocessor_config.json. Without
    /// it the image path has no processor to build, and respond(to:image:)
    /// fails deep inside MLX with nothing useful to report. Checking up front
    /// turns that crash into a message naming the missing file.
    var prerequisiteProblem: String? {
        guard let dir = directory else { return "尚未選擇模型目錄" }
        let fm = FileManager.default
        for name in ["config.json", "preprocessor_config.json"] {
            if !fm.fileExists(atPath: dir.appendingPathComponent(name).path) {
                return "缺少 \(name)"
            }
        }
        let entries = (try? fm.contentsOfDirectory(atPath: dir.path)) ?? []
        if !entries.contains(where: { $0.hasSuffix(".safetensors") }) {
            return "目錄中沒有 .safetensors 權重"
        }
        return nil
    }

    func load(directory url: URL) async {
        isBusy = true
        status = "載入中 \(url.lastPathComponent)…"
        appLogMemory("vlm pre-load", source: "vlm")
        do {
            appLog("vlm: entering loadContainer \(url.lastPathComponent)", source: "vlm")
            let loaded = try await VLMModelFactory.shared.loadContainer(
                from: url,
                using: LocalTokenizerLoader())
            container = loaded
            session = ChatSession(loaded)
            directory = url
            // Same cap as the LLM engine: a phone has far less headroom than a
            // Mac, and an unbounded cache is what turns a load into a jetsam kill.
            GPU.set(cacheLimit: 256 * 1024 * 1024)
            status = "\(url.lastPathComponent) · VLM"
            appLog("vlm loaded \(url.lastPathComponent)", source: "vlm")
        } catch {
            container = nil
            session = nil
            directory = nil
            status = "載入失敗：\(error.localizedDescription)"
            appLog("vlm load failed: \(error.localizedDescription)", source: "vlm")
        }
        isBusy = false
    }

    func analyze(image: URL) {
        guard let session else {
            status = "尚未載入模型"
            return
        }
        if let problem = prerequisiteProblem {
            status = "無法分析圖片：\(problem)"
            appLog("vlm: refusing to analyze — \(problem)", source: "vlm")
            return
        }
        isBusy = true
        answer = ""
        appLog(String(format: "vlm: analyze begin, prompt chars=%d image=%@ · %@",
                      prompt.count, image.lastPathComponent,
                      LogStore.memoryDescription()), source: "vlm")
        Task {
            let started = Date()
            do {
                appLog("vlm: entering respond(to:image:)", source: "vlm")
                let text = try await session.respond(to: prompt, image: .url(image))
                appLog(String(format: "vlm: respond returned %d chars · %@",
                              text.count, LogStore.memoryDescription()), source: "vlm")
                let secs = Date().timeIntervalSince(started)
                self.answer = text
                self.status = String(format: "完成 · %.1fs · %@",
                                     secs, LogStore.memoryDescription())
                appLog(String(format: "vlm analyzed in %.1fs", secs), source: "vlm")
            } catch {
                self.status = "分析失敗：\(error.localizedDescription)"
                appLog("vlm analyze failed: \(error.localizedDescription)", source: "vlm")
            }
            self.isBusy = false
        }
    }

    func unload() {
        session = nil
        container = nil
        directory = nil
        status = "未載入模型"
    }
}

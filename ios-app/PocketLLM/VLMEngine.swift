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

    func load(directory url: URL) async {
        isBusy = true
        status = "載入中 \(url.lastPathComponent)…"
        appLogMemory("vlm pre-load", source: "vlm")
        do {
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
        isBusy = true
        answer = ""
        appLogMemory("vlm pre-analyze", source: "vlm")
        Task {
            let started = Date()
            do {
                let text = try await session.respond(to: prompt, image: .url(image))
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

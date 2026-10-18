import SwiftUI
import UniformTypeIdentifiers

/// Per-model detail, opened from the small button beside the model in the chat
/// header. This is where per-model configuration belongs — not scattered in the
/// chat screen or duplicated in the Models tab.
struct ModelDetailsView: View {
    let modelName: String
    let modelURL: URL?
    let isMLX: Bool
    /// Hands a manually chosen projector to the loaded engine. The auto-detected
    /// one beside the model is attached during load; this covers the projector
    /// that sits elsewhere, which the picker previously only recorded.
    var onProjection: ((URL) -> Void)? = nil

    @State private var caps: ModelCapabilities?
    @State private var projection: URL?
    @State private var pickingProjection = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("模型") {
                    LabeledContent("名稱", value: modelName)
                    LabeledContent("後端", value: isMLX ? "MLX · GPU" : "llama.cpp · GGUF")
                    if let modelURL {
                        LabeledContent("檔案") {
                            Text(modelURL.lastPathComponent)
                                .lineLimit(1).truncationMode(.middle)
                        }
                    }
                }

                Section("能力") {
                    HStack(spacing: 8) {
                        Image(systemName: (caps?.isVision ?? false) ? "eye.fill" : "text.alignleft")
                            .foregroundStyle((caps?.isVision ?? false) ? Color.green : Color.secondary)
                        Text(caps?.summary ?? "偵測中…")
                    }
                    if let e = caps?.evidence, !e.isEmpty {
                        Text(e).font(.caption2).foregroundStyle(.secondary)
                    }
                    if let t = caps?.modelType, !t.isEmpty {
                        Text("model_type: \(t)").font(.caption2).foregroundStyle(.secondary)
                    }
                    if let a = caps?.architectures, !a.isEmpty {
                        Text(a.joined(separator: ", ")).font(.caption2).foregroundStyle(.secondary)
                    }
                }

                Section {
                    if let projection {
                        LabeledContent("已偵測") {
                            Text(projection.lastPathComponent)
                                .lineLimit(1).truncationMode(.middle)
                        }
                        Text("同目錄下的這個投影檔會在載入模型時自動接上，供 llama.cpp（libmtmd）做視覺推理。")
                            .font(.caption2).foregroundStyle(.secondary)
                    } else {
                        Text("此模型旁沒有 mmproj／projector 檔案。")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Button("手動指定投影模型…") { pickingProjection = true }
                } header: {
                    Text("投影模型（mmproj）")
                } footer: {
                    Text("純文字 GGUF 加上一個 mmproj 就有視覺能力。MLX 模型不需要 —— 投影層在模型目錄內。")
                        .font(.caption2)
                }
            }
            .navigationTitle("模型設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .fileImporter(isPresented: $pickingProjection,
                          allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let u = urls.first {
                    projection = u
                    onProjection?(u)
                    appLog("mmproj selected: \(u.lastPathComponent)", source: "vision")
                }
            }
            .task {
                if let modelURL {
                    caps = isMLX ? ModelInspector.inspect(directory: modelURL)
                                 : ModelCapabilities(isVision: ModelInspector.companionProjection(for: modelURL) != nil,
                                                     evidence: "同目錄下的 mmproj 檔")
                    if !isMLX { projection = ModelInspector.companionProjection(for: modelURL) }
                }
            }
        }
    }
}

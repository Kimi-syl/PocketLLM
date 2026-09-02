import SwiftUI
import PocketLLMKit

/// Live demo of the shared Kotlin agent core: routes a message through the
/// same ToolRouter the Android app and desktop CLI use, entirely on-device.
struct AgentKitView: View {
    static let allTools = ["web_search", "read_url", "calculate", "datetime", "run_code", "write_file"]
    @State private var input = ""
    @State private var enabled: Set<String> = Set(Self.allTools)
    @State private var result: String?

    var body: some View {
        Form {
            Section("Message") {
                TextField("e.g. What is 23*7+4?", text: $input)
                Button("Route through ToolRouter") { route() }
            }
            Section("Enabled tools") {
                ForEach(Self.allTools, id: \.self) { tool in
                    Toggle(tool, isOn: Binding(
                        get: { enabled.contains(tool) },
                        set: { if $0 { enabled.insert(tool) } else { enabled.remove(tool) } }
                    ))
                }
            }
            if let result {
                Section("Result") {
                    Text(result).font(.footnote.monospaced())
                }
            }
        }
        .navigationTitle("AgentKit")
    }

    private func route() {
        let router = ToolRouter()
        let category = router.route(
            userMessage: input,
            enabledTools: Set(enabled)
        )
        if let category {
            let tools = (category.tools as? [String])?.joined(separator: ", ") ?? "?"
            result = "routed tools: \(tools)\n\nhint:\n\(category.hint)"
        } else {
            result = "no route — the agent answers directly"
        }
    }
}

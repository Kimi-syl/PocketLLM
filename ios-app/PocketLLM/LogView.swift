import SwiftUI

struct LogView: View {
    @ObservedObject private var store = LogStore.shared
    @State private var filter = ""

    private var shown: [LogEntry] {
        guard !filter.isEmpty else { return store.entries }
        return store.entries.filter {
            $0.message.localizedCaseInsensitiveContains(filter)
                || $0.source.localizedCaseInsensitiveContains(filter)
        }
    }

    var body: some View {
        NavigationStack {
            List(shown.reversed()) { entry in
                Text(entry.line)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
            }
            .listStyle(.plain)
            .searchable(text: $filter, prompt: "Filter")
            .navigationTitle("Logs")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ShareLink(item: store.text) {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                        Button(role: .destructive) {
                            store.clear()
                        } label: {
                            Label("Clear", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .overlay {
                if store.entries.isEmpty {
                    ContentUnavailableView(
                        "No logs yet", systemImage: "doc.text",
                        description: Text("Load or run a model and entries appear here."))
                }
            }
        }
    }
}

import Foundation

/// One timestamped line in the Logs tab.
struct LogEntry: Identifiable, Hashable {
    let id = UUID()
    let date: Date
    let source: String
    let message: String

    var line: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return "\(f.string(from: date))  [\(source)]  \(message)"
    }
}

/// App-wide in-memory log.
///
/// Bounded and non-persistent by design: a phone log is a debugging aid, and an
/// unbounded or persisted one becomes its own source of confusion.
@MainActor
final class LogStore: ObservableObject {
    static let shared = LogStore()

    @Published private(set) var entries: [LogEntry] = []

    private let limit = 500

    private init() {}

    func log(_ message: String, source: String = "app") {
        entries.append(LogEntry(date: Date(), source: source, message: message))
        if entries.count > limit {
            entries.removeFirst(entries.count - limit)
        }
    }

    func clear() {
        entries.removeAll()
    }

    /// Whole log as text, for the share sheet.
    var text: String {
        entries.map(\.line).joined(separator: "\n")
    }
}

/// Fire-and-forget logging that is safe from any context.
func appLog(_ message: String, source: String = "app") {
    Task { @MainActor in LogStore.shared.log(message, source: source) }
}

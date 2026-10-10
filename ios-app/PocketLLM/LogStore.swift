import Foundation

/// One timestamped line in the Logs tab.
struct LogEntry: Identifiable, Hashable {
    let id = UUID()
    let date: Date
    let source: String
    let message: String

    var line: String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return "\(f.string(from: date))  [\(source)]  \(message)"
    }
}

/// App-wide log. In memory for the Logs tab, mirrored to a file on disk.
///
/// Persistence exists because memory-pressure failures happen during a load and
/// the useful evidence is gone the moment the app is killed — an in-memory-only
/// log is exactly the thing that vanishes when you need it. The file is capped
/// and trimmed from the front so it cannot grow without bound.
@MainActor
final class LogStore: ObservableObject {
    static let shared = LogStore()

    @Published private(set) var entries: [LogEntry] = []

    private let memoryLimit = 500
    private let fileByteLimit = 1_000_000
    private lazy var fileURL: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("pocketllm.log")
    }()

    private init() {
        loadFromDisk()
        log("launch · \(ProcessInfo.processInfo.operatingSystemVersionString) · \(Self.memoryDescription())",
            source: "app")
    }

    // MARK: - API

    func log(_ message: String, source: String = "app") {
        let entry = LogEntry(date: Date(), source: source, message: message)
        entries.append(entry)
        if entries.count > memoryLimit {
            entries.removeFirst(entries.count - memoryLimit)
        }
        appendToDisk(entry)
    }

    /// Convenience: current resident memory plus device headroom, which is the
    /// pair that matters when something dies under load.
    nonisolated static func memoryDescription() -> String {
        let resident = currentFootprintMB()
        let physical = Int(ProcessInfo.processInfo.physicalMemory / 1_048_576)
        return "resident \(resident)MB / physical \(physical)MB"
    }

    /// Resident memory footprint in MB via mach task info.
    nonisolated static func currentFootprintMB() -> Int {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(
            MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        guard kr == KERN_SUCCESS else { return -1 }
        return Int(info.resident_size / 1_048_576)
    }

    func clear() {
        entries.removeAll()
        try? FileManager.default.removeItem(at: fileURL)
    }

    var text: String {
        entries.map(\.line).joined(separator: "\n")
    }

    // MARK: - Disk

    private func appendToDisk(_ entry: LogEntry) {
        guard let data = (entry.line + "\n").data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: fileURL) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: fileURL)
        }
        trimIfNeeded()
    }

    private func loadFromDisk() {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
        let tail = text.split(separator: "\n").suffix(memoryLimit)
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        entries = tail.compactMap { raw in
            let line = String(raw)
            guard line.count > 24 else { return nil }
            let stamp = String(line.prefix(23))
            guard let date = f.date(from: stamp) else { return nil }
            var rest = String(line.dropFirst(25))
            var source = "app"
            if rest.hasPrefix("[") {
                if let close = rest.firstIndex(of: "]") {
                    source = String(rest[rest.index(after: rest.startIndex)..<close])
                    rest = String(rest[rest.index(after: close)...])
                        .trimmingCharacters(in: .whitespaces)
                }
            }
            return LogEntry(date: date, source: source, message: rest)
        }
    }

    private func trimIfNeeded() {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let size = attrs[.size] as? Int, size > fileByteLimit,
              let text = try? String(contentsOf: fileURL, encoding: .utf8)
        else { return }
        let kept = text.split(separator: "\n").suffix(memoryLimit).joined(separator: "\n")
        try? (kept + "\n").data(using: .utf8)?.write(to: fileURL)
    }
}

/// Fire-and-forget logging that is safe from any context.
func appLog(_ message: String, source: String = "app") {
    Task { @MainActor in LogStore.shared.log(message, source: source) }
}

/// Log a line annotated with current memory. Use at points where allocation
/// spikes, so a later OOM has a trace of how close it was.
func appLogMemory(_ message: String, source: String = "app") {
    appLog("\(message) · \(LogStore.memoryDescription())", source: source)
}

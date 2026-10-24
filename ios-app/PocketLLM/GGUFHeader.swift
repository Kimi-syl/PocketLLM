import Foundation

/// Reads just enough of a GGUF file to say what it is.
///
/// Import has to decide between "chat model" and "diffusion model" before the
/// file is anywhere near a loader, and the only honest source for that is the
/// header's `general.architecture`. The KV section is walked value by value and
/// arrays are skipped rather than materialised, because a text model's header
/// also carries its whole tokenizer (tens of thousands of entries).
enum GGUFHeader {
    struct Info {
        /// `general.architecture`, e.g. "llama", "qwen2", "flux".
        var architecture: String?
        /// `general.name` when the converter wrote one.
        var name: String?
        var tensorCount: Int = 0
        /// The first `tensorSample` tensor names, when requested. A single-file
        /// checkpoint carries its VAE (`first_stage_model.*`, `decoder.*`) or
        /// text encoder (`cond_stage_model.*`) inside the same file; a bare
        /// diffusion transformer carries neither.
        var tensorNames: [String] = []
    }

    private enum ValueType: UInt32 {
        case uint8 = 0, int8, uint16, int16, uint32, int32, float32, bool,
             string, array, uint64, int64, float64
    }

    /// The header is a few MB at most (a tokenizer is the large part) and the
    /// tensor list follows it; 64 MB leaves room for both without reading a
    /// multi-gigabyte weight file.
    private static let limit = 64 * 1024 * 1024

    static func read(_ url: URL, tensorSample: Int = 0) -> Info? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: limit), head.count > 24 else { return nil }

        var cursor = Reader(data: head)
        guard let magic = try? cursor.string(4), magic == "GGUF" else { return nil }
        _ = try? cursor.uint32()                      // version
        // A bogus count would overflow the Int conversion below; real files are
        // far under this.
        let tensorCount = min((try? cursor.uint64()) ?? 0, 10_000_000)
        guard let kvCount = try? cursor.uint64() else { return nil }
        guard kvCount < 100_000 else { return nil }   // implausible: not a header

        var info = Info()
        info.tensorCount = Int(tensorCount)
        for _ in 0..<kvCount {
            guard let key = try? cursor.stringValue(),
                  let raw = try? cursor.uint32(),
                  let type = ValueType(rawValue: raw)
            else { return info }

            if type == .string, key == "general.architecture" || key == "general.name" {
                guard let value = try? cursor.stringValue() else { return info }
                if key == "general.architecture" { info.architecture = value }
                else { info.name = value }
            } else {
                guard (try? cursor.skip(type)) != nil else { return info }
            }
        }

        // Tensor info follows the KVs: name, n_dims, dims[n_dims] (u64), type,
        // offset. Only the names are needed, and only a prefix of them.
        guard tensorSample > 0 else { return info }
        for _ in 0..<min(Int(tensorCount), tensorSample) {
            guard let name = try? cursor.stringValue(),
                  let dims = try? cursor.uint32() else { return info }
            guard (try? cursor.skipBytes(Int(dims) * 8 + 4 + 8)) != nil else { return info }
            info.tensorNames.append(name)
        }
        return info
    }

    /// Sequential reader over the prefix. Every failure is a thrown error so the
    /// caller can stop and report whatever it already learned.
    private struct Reader {
        let data: Data
        var offset = 0

        init(data: Data) { self.data = data }

        enum ReadError: Error { case truncated, unsupported(UInt32) }

        mutating func uint32() throws -> UInt32 {
            guard offset + 4 <= data.count else { throw ReadError.truncated }
            let value = data.withUnsafeBytes {
                $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)
            }
            offset += 4
            return UInt32(littleEndian: value)
        }

        mutating func uint64() throws -> UInt64 {
            guard offset + 8 <= data.count else { throw ReadError.truncated }
            let value = data.withUnsafeBytes {
                $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)
            }
            offset += 8
            return UInt64(littleEndian: value)
        }

        /// GGUF strings are a u64 length followed by raw bytes (no terminator).
        mutating func stringValue() throws -> String {
            let length = try uint64()
            guard length < 1 << 24, offset + Int(length) <= data.count else {
                throw ReadError.truncated
            }
            let raw = data[(data.startIndex + offset)..<(data.startIndex + offset + Int(length))]
            offset += Int(length)
            return String(decoding: raw, as: UTF8.self)
        }

        /// Reads a fixed ASCII tag without interpreting it as a length-prefixed
        /// string — GGUF's magic is four bytes, not a string value.
        mutating func string(_ count: Int) throws -> String {
            guard offset + count <= data.count else { throw ReadError.truncated }
            let raw = data[(data.startIndex + offset)..<(data.startIndex + offset + count)]
            offset += count
            return String(decoding: raw, as: UTF8.self)
        }

        mutating func skipBytes(_ count: Int) throws {
            guard count >= 0, offset + count <= data.count else { throw ReadError.truncated }
            offset += count
        }

        mutating func skip(_ type: ValueType) throws {
            switch type {
            case .uint8, .int8, .bool: offset += 1
            case .uint16, .int16:      offset += 2
            case .uint32, .int32, .float32: offset += 4
            case .uint64, .int64, .float64: offset += 8
            case .string:
                let length = try uint64()
                guard length < 1 << 24, offset + Int(length) <= data.count else {
                    throw ReadError.truncated
                }
                offset += Int(length)
            case .array:
                let raw = try uint32()
                guard let element = ValueType(rawValue: raw) else {
                    throw ReadError.unsupported(raw)
                }
                let count = try uint64()
                guard count < 1 << 24 else { throw ReadError.truncated }
                for _ in 0..<count { try skip(element) }
            }
            guard offset <= data.count else { throw ReadError.truncated }
        }
    }
}

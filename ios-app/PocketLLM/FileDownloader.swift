import Foundation

enum DownloadError: LocalizedError {
    case vanished

    var errorDescription: String? {
        "the transfer finished but the file could not be moved into place"
    }
}

/// Streams a file to disk with progress reporting.
///
/// Built on `URLSessionDownloadTask` rather than `URLSession.bytes`: the async
/// byte iterator hands over one byte at a time and cannot keep up with the
/// gigabyte-scale weights these downloads carry, while a download task writes to
/// a temporary file on URLSession's own queue.
///
/// Delegates fire on a background queue, so the progress and completion closures
/// are called off the main thread — callers that touch UI state must hop.
final class FileDownloader: NSObject, URLSessionDownloadDelegate {

    static let shared = FileDownloader()

    private final class Job {
        let destination: URL
        let onProgress: (Double) -> Void
        let onComplete: (Result<URL, Error>) -> Void
        var moved = false
        var moveError: Error?

        init(
            destination: URL,
            onProgress: @escaping (Double) -> Void,
            onComplete: @escaping (Result<URL, Error>) -> Void
        ) {
            self.destination = destination
            self.onProgress = onProgress
            self.onComplete = onComplete
        }
    }

    private var jobs: [Int: Job] = [:]
    private let lock = NSLock()

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 60
        // A 2 GB weight file over a poor mobile link legitimately takes hours;
        // the default seven-day resource timeout is fine but the request
        // timeout would otherwise fire between stalls.
        configuration.timeoutIntervalForResource = 60 * 60 * 12
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    /// Downloads to `destination`, creating its parent directory if needed.
    /// An existing file at that path is replaced on success only — the partial
    /// data lives in URLSession's temporary file until then.
    func download(
        _ request: URLRequest,
        to destination: URL,
        onProgress: @escaping (Double) -> Void,
        onComplete: @escaping (Result<URL, Error>) -> Void
    ) {
        let session = self.session
        let task = session.downloadTask(with: request)

        try? FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true)

        lock.lock()
        jobs[Int(task.taskIdentifier)] = Job(
            destination: destination, onProgress: onProgress, onComplete: onComplete)
        lock.unlock()

        task.resume()
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0, let job = job(for: downloadTask) else { return }
        let fraction = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
        job.onProgress(min(max(fraction, 0), 1))
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let job = job(for: downloadTask) else { return }

        // HTTP errors surface here as a successful transfer of an error body;
        // saving that as a .gguf produced a model that failed to load with no
        // explanation, so the status is checked before the file is kept. A gated
        // repository answers 401 with a JSON reason, which is worth showing.
        if let http = downloadTask.response as? HTTPURLResponse,
            !(200...299).contains(http.statusCode)
        {
            let body = (try? String(contentsOf: location, encoding: .utf8)) ?? ""
            job.moveError = HuggingFaceClient.ClientError.http(http.statusCode, body)
            return
        }

        let fileManager = FileManager.default
        do {
            if fileManager.fileExists(atPath: job.destination.path) {
                try fileManager.removeItem(at: job.destination)
            }
            try fileManager.moveItem(at: location, to: job.destination)
            job.moved = true
        } catch {
            job.moveError = error
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let job = jobs.removeValue(forKey: Int(task.taskIdentifier))
        lock.unlock()
        guard let job else { return }

        if let error {
            job.onComplete(.failure(error))
        } else if let moveError = job.moveError {
            job.onComplete(.failure(moveError))
        } else if job.moved {
            job.onComplete(.success(job.destination))
        } else {
            job.onComplete(.failure(DownloadError.vanished))
        }
    }

    private func job(for task: URLSessionTask) -> Job? {
        lock.lock()
        defer { lock.unlock() }
        return jobs[Int(task.taskIdentifier)]
    }
}

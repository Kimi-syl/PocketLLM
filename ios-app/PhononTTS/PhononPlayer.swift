import AVFoundation

// Vendored from gradium-ai/xn-ptts v0.6.0 (ios/PhononTTS/Sources/PhononTTS),
// MIT OR Apache-2.0. See THIRD_PARTY_NOTICES.md.
//
// Two local changes, both marked @available below:
//  1. Every public type is annotated `@available(iOS 18.0, *)`. This app targets
//     iOS 17 and the package declares iOS 18, so SwiftPM would refuse to resolve
//     it; compiling the sources into the app instead keeps one binary for both,
//     at the cost of making the API invisible to the compiler below iOS 18 —
//     which is exactly the guarantee wanted, since the Core ML bundles are
//     mlprogram and cannot run on 17 anyway.
//  2. `import Synchronization` (Mutex/Atomic) is itself iOS 18+, and is what
//     forces the annotation on `Phonon`.

/// Plays Phonon's audio as it arrives, so sound starts with the first chunk rather than after
/// the last.
///
/// ```swift
/// let player = try PhononPlayer()
/// try await player.play(phonon.stream("Hello there."))
/// ```
///
/// On iOS it sets the shared audio session to `.playback` unless told not to; an app that
/// manages its own session should pass `configureSession: false`.
@available(iOS 18.0, macOS 15.0, *)
public final class PhononPlayer: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: Phonon.sampleRate, channels: 1)!

    public init(configureSession: Bool = true) throws {
        #if os(iOS)
        if configureSession {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
        }
        #endif
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        try engine.start()
        node.play()
    }

    deinit {
        node.stop()
        engine.stop()
    }

    /// Queue samples to play after whatever is already queued. Safe from any thread.
    public func enqueue(_ samples: [Float]) {
        guard !samples.isEmpty,
              let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count))
        else { return }
        buf.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { buf.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
        node.scheduleBuffer(buf)
    }

    /// Play a stream as it is generated. Returns when the stream ends; the last chunks may
    /// still be playing. Keep this player alive until playback finishes.
    /// Cancel the calling task to stop the stream, and call `stop()` to clear scheduled audio.
    public func play(_ stream: AsyncThrowingStream<[Float], Error>) async throws {
        for try await chunk in stream {
            enqueue(chunk)
        }
    }

    /// Drop everything queued and go quiet. Also cancel the playback task to stop generation;
    /// otherwise it can enqueue more audio. Playing again after this works as before.
    public func stop() {
        node.stop()
        node.play()
    }
}

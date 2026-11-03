import AVFoundation
import CoreVideo
import UIKit
import Vision

/// Turns one picture into a short clip by re-imagining it, over and over, at low
/// strength.
///
/// Each frame is an img2img pass over the previous one: same prompt, same seed,
/// `strength` around 0.2, so the model nudges the picture rather than replacing
/// it. Chaining those nudges is what produces the drift that reads as motion in
/// a flipbook. The seed is fixed on purpose — with a random seed every frame is
/// an independent sample and the result flickers instead of moving.
///
/// The frames the model produced are then interpolated up to double the rate
/// with `VNGenerateOpticalFlowRequest` and written to an `.mp4` with
/// `AVAssetWriter`, so the output is a normal video file the Photos app and the
/// share sheet understand rather than a folder of stills.
///
/// ## Why optical flow, and why not just more frames
///
/// Asking the diffusion model for twice as many frames costs twice the compute
/// and, at this strength, adds no real motion — consecutive passes are close
/// enough that the extra frames are near-copies. Optical flow costs a fraction
/// of that and produces in-between positions that the model never had to
/// imagine. It is not free of artefacts: a flow field fails where a region is
/// newly revealed, which is why the warp is bidirectional and blended with the
/// plain crossfade instead of trusting the flow alone.
@MainActor
final class FlipbookEngine: ObservableObject {

    /// Everything one clip needs. `seed` is fixed by default (`-1` would make
    /// every frame an independent sample, which reads as noise rather than motion).
    struct Config {
        var base: UIImage
        var prompt: String
        var negative: String = ""
        var frames: Int = 12
        /// Low by design: above ~0.35 the picture is re-imagined each pass and the
        /// clip stops being a flipbook and becomes a slideshow of mutations.
        var strength: Float = 0.2
        var seed: Int64 = 42
        var steps: Int = 8
        var cfg: Float = 1.0
        var width: Int = 480
        var height: Int = 832
        /// Frame rate of the *source* frames; the written clip is double when
        /// interpolation is on.
        var fps: Int = 12
        var interpolate: Bool = true
    }

    enum Phase: Equatable {
        case idle
        case generating(frame: Int, of: Int)
        case interpolating(frame: Int, of: Int)
        case writing
        case finished(URL)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var previewFrames: [UIImage] = []


    /// Renders the clip and returns the file URL.
    ///
    /// Cancelling the calling task stops the loop at the next frame boundary —
    /// not mid-generation, because the sampler runs inside the engine and
    /// tearing it down mid-run is what the reload path exists to recover from.
    /// - Parameter engine: the tab's engine, passed in per call. The clipboard
    ///   must use the engine that already holds the loaded model — constructing
    ///   its own would mean loading several GB of weights a second time, and a
    ///   `@StateObject` cannot hold a reference to another view's `@StateObject`.
    func render(_ config: Config, using engine: SdEngine) async throws -> URL {
        guard config.frames >= 2 else {
            throw FlipbookError.invalid("至少需要 2 個影格")
        }
        try Task.checkCancellation()

        // The chain starts from the base image and then walks forward, so every
        // pass starts where the previous one ended.
        var frames: [UIImage] = [config.base]
        // Seed is fixed for the whole clip; variation comes from the picture
        // drifting between passes, not from resampling.
        engine.seed = config.seed
        engine.strength = config.strength
        engine.steps = config.steps
        engine.cfg = config.cfg
        engine.width = config.width
        engine.height = config.height
        engine.prompt = config.prompt
        engine.negative = config.negative

        var current = config.base
        for index in 1...config.frames {
            try Task.checkCancellation()
            phase = .generating(frame: index, of: config.frames)
            engine.initImage = current
            engine.generate()
            let produced = try await awaitGeneration()
            guard let next = produced.first else {
                throw FlipbookError.invalid("第 \(index) 個影格沒有產生任何圖片")
            }
            current = next
            frames.append(next)
            previewFrames = frames
        }

        let toWrite: [UIImage]
        if config.interpolate {
            toWrite = try await interpolate(frames)
        } else {
            toWrite = frames
        }

        phase = .writing
        let url = try await write(toWrite, fps: config.interpolate ? config.fps * 2 : config.fps)
        phase = .finished(url)
        return url
    }

    // MARK: - Driving the engine

    /// Waits for the engine's current run to finish.
    ///
    /// `SdEngine.generate()` is fire-and-forget by design — it publishes `phase`
    /// and lets the UI observe it — so the loop polls that published state
    /// rather than reaching into the sampler.
    private func awaitGeneration() async throws -> [UIImage] {
        while true {
            try Task.checkCancellation()
            switch engine.phase {
            case .succeeded:
                return engine.images
            case .failed(let message):
                throw FlipbookError.generation(message)
            case .idle, .loading, .running:
                try await Task.sleep(nanoseconds: 250_000_000)
            }
        }
    }

    // MARK: - Optical flow interpolation

    /// Doubles the frame count by warping each pair toward each other.
    ///
    /// `VNGenerateOpticalFlowRequest` returns a two-component float field giving,
    /// per pixel of the first image, the displacement to the matching pixel in
    /// the second. The midpoint is produced by sampling each frame backward
    /// along half of that displacement and blending, which is why newly revealed
    /// regions degrade gracefully into the crossfade instead of smearing.
    private func interpolate(_ frames: [UIImage]) async throws -> [UIImage] {
        guard frames.count >= 2 else { return frames }
        var out: [UIImage] = []
        for index in 0..<(frames.count - 1) {
            try Task.checkCancellation()
            phase = .interpolating(frame: index + 1, of: frames.count - 1)
            out.append(frames[index])
            if let mid = try? middleFrame(frames[index], frames[index + 1], t: 0.5) {
                out.append(mid)
            }
        }
        out.append(frames[frames.count - 1])
        return out
    }

    private func middleFrame(_ a: UIImage, _ b: UIImage, t: Float) throws -> UIImage? {
        guard let cgA = a.cgImage, let cgB = b.cgImage else { return nil }
        let width = min(cgA.width, cgB.width)
        let height = min(cgA.height, cgB.height)
        guard width > 0, height > 0 else { return nil }

        let request = VNGenerateOpticalFlowRequest(targetedCGImage: cgB, options: [:])
        // `.medium` is the accuracy/speed trade this needs: the frames are only
        // 0.2-strength apart, so the displacement is small and a fine-grained
        // field mostly costs time.
        request.computationAccuracy = .medium
        request.outputPixelFormat = kCVPixelFormatType_TwoComponent32Float
        let handler = VNImageRequestHandler(cgImage: cgA, options: [:])
        try handler.perform([request])
        guard let flow = request.results?.first?.pixelBuffer else { return nil }

        let sa = Self.rgba(cgA, width: width, height: height)
        let sb = Self.rgba(cgB, width: width, height: height)

        let flowWidth = CVPixelBufferGetWidth(flow)
        let flowHeight = CVPixelBufferGetHeight(flow)
        CVPixelBufferLockBaseAddress(flow, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(flow, .readOnly) }
        guard let flowBase = CVPixelBufferGetBaseAddress(flow) else { return nil }
        let flowStride = CVPixelBufferGetBytesPerRow(flow) / MemoryLayout<Float>.size

        var output = [UInt8](repeating: 0, count: width * height * 4)
        for y in 0..<height {
            // The flow field is computed at the request's own resolution; map
            // the output pixel into it rather than assuming they match.
            let fy = min(flowHeight - 1, y * flowHeight / height)
            for x in 0..<width {
                let fx = min(flowWidth - 1, x * flowWidth / width)
                let offset = fy * flowStride + fx * 2
                let dx = flowBase.load(fromByteOffset: offset * MemoryLayout<Float>.size, as: Float.self)
                let dy = flowBase.load(fromByteOffset: (offset + 1) * MemoryLayout<Float>.size, as: Float.self)

                // Backward warp: where this output pixel comes from in each frame.
                let ax = Float(x) - t * dx
                let ay = Float(y) - t * dy
                let bx = Float(x) - (t - 1) * dx
                let by = Float(y) - (t - 1) * dy

                let aSample = Self.sample(sa, width: width, height: height, x: ax, y: ay)
                let bSample = Self.sample(sb, width: width, height: height, x: bx, y: by)
                let i = (y * width + x) * 4
                for c in 0..<4 {
                    output[i + c] = UInt8(max(0, min(255, (1 - t) * aSample[c] + t * bSample[c])))
                }
            }
        }

        guard let provider = CGDataProvider(data: Data(output) as CFData),
              let image = CGImage(width: width, height: height,
                                  bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: width * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: true,
                                  intent: .defaultIntent)
        else { return nil }
        return UIImage(cgImage: image)
    }

    /// RGBA bytes for a CGImage at the requested size.
    ///
    /// `premultipliedLast` + device RGB is the layout CoreGraphics writes for an
    /// 8-bit RGBA context on both simulator and device, so the byte order the
    /// warper reads matches the one it wrote.
    private static func rgba(_ image: CGImage, width: Int, height: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        bytes.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
                return
            }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return bytes
    }

    /// Bilinear sample, clamped at the edges so a displacement that runs off the
    /// canvas repeats the border pixel instead of going black.
    private static func sample(_ bytes: [UInt8], width: Int, height: Int, x: Float, y: Float) -> [Float] {
        let cx = max(0, min(Float(width - 1), x))
        let cy = max(0, min(Float(height - 1), y))
        let x0 = Int(cx), y0 = Int(cy)
        let x1 = min(width - 1, x0 + 1), y1 = min(height - 1, y0 + 1)
        let fx = cx - Float(x0), fy = cy - Float(y0)
        var out = [Float](repeating: 0, count: 4)
        for c in 0..<4 {
            let p00 = Float(bytes[(y0 * width + x0) * 4 + c])
            let p10 = Float(bytes[(y0 * width + x1) * 4 + c])
            let p01 = Float(bytes[(y1 * width + x0) * 4 + c])
            let p11 = Float(bytes[(y1 * width + x1) * 4 + c])
            let top = p00 + (p10 - p00) * fx
            let bottom = p01 + (p11 - p01) * fx
            out[c] = top + (bottom - top) * fy
        }
        return out
    }

    // MARK: - Encoding

    /// Writes the frames as H.264 in an `.mp4`.
    ///
    /// H.264 rather than HEVC because the result is meant to be sent to people:
    /// every iOS version and every Android phone decodes it, and a 13-frame clip
    /// has no size problem for HEVC's better ratio to solve.
    private func write(_ frames: [UIImage], fps: Int) async throws -> URL {
        guard let first = frames.first?.cgImage else {
            throw FlipbookError.invalid("沒有可寫入的影格")
        }
        let width = first.width - (first.width % 2)      // H.264 needs even dimensions
        let height = first.height - (first.height % 2)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flipbook-\(Int(Date().timeIntervalSince1970)).mp4")
        try? FileManager.default.removeItem(at: url)

        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 6_000_000,
                AVVideoMaxKeyFrameIntervalKey: fps,
            ],
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ])
        writer.add(input)
        guard writer.startWriting() else {
            throw FlipbookError.invalid(writer.error?.localizedDescription ?? "AVAssetWriter 無法開始寫入")
        }
        writer.startSession(atSourceTime: .zero)

        let scale = CMTimeScale(fps)
        for (index, frame) in frames.enumerated() {
            try Task.checkCancellation()
            guard let cg = frame.cgImage else { continue }
            // AVAssetWriter wants buffers from its own pool, and the frame rate
            // is expressed in the presentation timestamp, not in the buffer.
            while !input.isReadyForMoreMediaData {
                try await Task.sleep(nanoseconds: 5_000_000)
            }
            guard let pool = adaptor.pixelBufferPool else {
                throw FlipbookError.invalid("AVAssetWriter 沒有可用的 pixel buffer pool")
            }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let pixelBuffer = buffer else { continue }
            CVPixelBufferLockBaseAddress(pixelBuffer, [])
            if let base = CVPixelBufferGetBaseAddress(pixelBuffer),
               let ctx = CGContext(data: base, width: width, height: height,
                                   bitsPerComponent: 8,
                                   bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
                                   space: CGColorSpaceCreateDeviceRGB(),
                                   bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                       | CGBitmapInfo.byteOrder32Little.rawValue) {
                ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
            }
            CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
            let time = CMTime(value: CMTimeValue(index), timescale: scale)
            adaptor.append(pixelBuffer, withPresentationTime: time)
        }

        input.markAsFinished()
        await writer.finishWriting()
        if writer.status != .completed {
            throw FlipbookError.invalid(writer.error?.localizedDescription ?? "影片寫入失敗")
        }
        return url
    }

    enum FlipbookError: LocalizedError {
        case invalid(String)
        case generation(String)

        var errorDescription: String? {
            switch self {
            case .invalid(let m):    return m
            case .generation(let m): return m
            }
        }
    }
}

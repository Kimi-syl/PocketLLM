import Foundation
import MLX
import MLXNN

/// Decoder-only Swift port of the 3D causal VAE used by Qwen-Image and Wan 2.1
/// (the VAE Anima ships as `qwen_image_vae.safetensors`).
///
/// Only the single-frame path is implemented, because image generation decodes
/// one latent frame. That matters for correctness: every `CausalConv3d` here has
/// a temporal kernel of 3 and pads two zero frames on the left, so with `T = 1`
/// the only temporal tap that touches real data is the last one. The whole
/// decoder therefore runs as 2D convolutions over the weights' last temporal
/// slice. That is exactly what the engine does internally for single frames
/// (`ggml_ext_conv_3d` with a one-frame input), not an approximation.
///
/// Layout note: mlx-swift's `conv2d` and `Conv2d` are **channels-last** (NHWC),
/// unlike mlx-python. Everything inside this file is NHWC; the public entry
/// point converts to and from `[N, C, H, W]`.
///
/// Weight names follow the engine's block paths (`conv2`, `decoder.middle.0`,
/// `decoder.upsamples.N`, ...), which is what the VAE files it accepts contain.
/// A leading wrapper prefix (e.g. `first_stage_model.` or `vae.`) is detected
/// from the file rather than assumed.
final class MLXVae {
    enum VaeError: LocalizedError {
        case unreadable(String)
        case missing(String)
        case unsupported(String)

        var errorDescription: String? {
            switch self {
            case .unreadable(let p): return "MLX VAE: could not read \(p)"
            case .missing(let k):    return "MLX VAE: tensor missing: \(k)"
            case .unsupported(let m): return "MLX VAE: \(m)"
            }
        }
    }

    private(set) var weights: [String: MLXArray]
    private let prefix: String

    let zDim: Int
    let dim: Int
    let dimMult: [Int]
    let numResBlocks: Int

    /// Qwen-Image / Wan 2.1 latent statistics (16 channels). The sampler's
    /// latents are un-normalized, so decode must undo these first.
    static let latentsMean: [Float] = [
        -0.7571, -0.7089, -0.9113, 0.1075, -0.1745, 0.9653, -0.1517, 1.5508,
         0.4134, -0.0715, 0.5517, -0.3632, -0.1922, -0.9497, 0.2503, -0.2921,
    ]
    static let latentsStd: [Float] = [
        2.8184, 1.4541, 2.3275, 2.6558, 1.2196, 1.7708, 2.6052, 2.0743,
        3.2687, 2.1526, 2.8652, 1.5579, 1.6382, 1.1253, 2.8251, 1.9160,
    ]

    init(vaePath: String, zDim: Int = 16, dim: Int = 96,
         dimMult: [Int] = [1, 2, 4, 4], numResBlocks: Int = 2) throws {
        guard FileManager.default.fileExists(atPath: vaePath) else {
            throw VaeError.unreadable(vaePath)
        }
        let arrays: [String: MLXArray]
        do {
            arrays = try loadArrays(url: URL(fileURLWithPath: vaePath))
        } catch {
            throw VaeError.unreadable("\(vaePath): \(error.localizedDescription)")
        }
        guard !arrays.isEmpty else { throw VaeError.unreadable(vaePath) }

        // Locate the wrapper prefix by the one key every Wan-family VAE has.
        let anchor = "decoder.conv1.weight"
        guard let key = arrays.keys.first(where: { $0 == anchor || $0.hasSuffix("." + anchor) }) else {
            throw VaeError.missing(anchor)
        }
        self.prefix = String(key.dropLast(anchor.count))
        self.weights = arrays
        self.zDim = zDim
        self.dim = dim
        self.dimMult = dimMult
        self.numResBlocks = numResBlocks
    }

    // MARK: - Tensor lookup

    private func has(_ name: String) -> Bool {
        weights[prefix + name] != nil
    }

    private func tensor(_ name: String) throws -> MLXArray {
        guard let a = weights[prefix + name] else { throw VaeError.missing(prefix + name) }
        return a
    }

    /// PyTorch keeps convolution weights as `[out, in, kt, kh, kw]`; mlx-swift
    /// wants `[out, kh, kw, in]`. For a single frame, the temporal dimension
    /// collapses to the last tap (two zero frames are padded on the left).
    private func convWeight(_ name: String) throws -> MLXArray {
        var w = try tensor(name)
        // The safetensors are bf16; MLX convolutions want one dtype throughout,
        // and the dumped latents are f32.
        w = w.asType(.float32)
        if w.ndim == 5 {
            w = w[0..., 0..., w.shape[2] - 1, 0..., 0...]
        }
        return w.transposed(0, 2, 3, 1)
    }

    // MARK: - Ops (NHWC throughout)

    private func conv(_ x: MLXArray, _ name: String, padding: Int) throws -> MLXArray {
        let w = try convWeight(name + ".weight")
        var y = conv2d(x, w, stride: 1, padding: padding)
        if let b = weights[prefix + name + ".bias"] {
            y = y + b.asType(.float32).reshaped([1, 1, 1, b.size])
        }
        return y
    }

    /// Matches the engine's RMS norm: divide by sqrt(mean(x^2) + eps) over the
    /// channel axis, then scale by `gamma`. (Over channels this is equivalent to
    /// diffusers' `L2-normalize * sqrt(C)`.)
    private func rmsNorm(_ x: MLXArray, _ gamma: MLXArray?) -> MLXArray {
        let meanSq = (x * x).mean(axis: -1, keepDims: true)
        var y = x / sqrt(meanSq + 1e-12)
        if let gamma {
            y = y * gamma.asType(.float32).reshaped([1, 1, 1, gamma.size])
        }
        return y
    }

    private func residualBlock(_ x: MLXArray, _ p: String) throws -> MLXArray {
        var h = x
        if has(p + "shortcut.weight") {
            h = try conv(x, p + "shortcut", padding: 0)
        }
        var y = rmsNorm(x, try? tensor(p + "residual.0.gamma"))
        y = silu(y)
        y = try conv(y, p + "residual.2", padding: 1)
        y = rmsNorm(y, try? tensor(p + "residual.3.gamma"))
        y = silu(y)
        y = try conv(y, p + "residual.6", padding: 1)
        return y + h
    }

    /// Single-head causal self-attention, mirroring diffusers' WanAttentionBlock:
    /// `softmax(q kᵀ / sqrt(C)) v` per frame, then a 1x1 projection.
    private func attention(_ x: MLXArray, _ p: String) throws -> MLXArray {
        let identity = x
        let (n, hh, ww, _) = (x.shape[0], x.shape[1], x.shape[2], x.shape[3])
        var y = rmsNorm(x, try? tensor(p + "norm.gamma"))
        y = try conv(y, p + "to_qkv", padding: 0)
        let c3 = y.shape[3]
        let c = c3 / 3
        y = y.reshaped([n, hh * ww, c3])                       // [n, hw, 3c]
        let qkv = split(y, parts: 3, axis: 2)
        let q = qkv[0], k = qkv[1], v = qkv[2]
        let scale = 1.0 / sqrt(Float(c))
        let probs = softmax(matmul(q * scale, k.transposed(0, 2, 1)), axis: 2)
        var out = matmul(probs, v)                             // [n, hw, c]
        out = out.reshaped([n, hh, ww, c])
        out = try conv(out, p + "proj", padding: 0)
        return out + identity
    }

    /// Nearest-neighbour 2x. Written with concatenate/reshape rather than the
    /// Upsample module so it cannot depend on the package's scale-factor API.
    private func nearest2x(_ x: MLXArray) -> MLXArray {
        let (n, h, w, c) = (x.shape[0], x.shape[1], x.shape[2], x.shape[3])
        var y = x.expandedDimensions(axis: 3)                  // [n,h,w,1,c]
        y = concatenated([y, y], axis: 3)                      // [n,h,w,2,c]
        y = y.reshaped([n, h, w * 2, c])
        y = y.expandedDimensions(axis: 2)                      // [n,h,1,w2,c]
        y = concatenated([y, y], axis: 2)                      // [n,h,2,w2,c]
        return y.reshaped([n, h * 2, w * 2, c])
    }

    private func upsampleBlock(_ x: MLXArray, _ p: String) throws -> MLXArray {
        try conv(nearest2x(x), p + "resample.1", padding: 1)
    }

    // MARK: - Decode

    /// - Parameter latents: `[N, C, 1, H, W]` or `[N, C, H, W]` diffusion latents.
    /// - Returns: `[N, H*8, W*8, 3]` (NHWC) in the range `[-1, 1]`.
    func decode(latents: MLXArray) throws -> MLXArray {
        var z = latents
        if z.ndim == 5 {
            guard z.shape[2] == 1 else {
                throw VaeError.unsupported("only single-frame decode is implemented (got T=\(z.shape[2]))")
            }
            z = z.squeezed(axis: 2)
        }
        guard z.ndim == 4 else { throw VaeError.unsupported("latents must be 4D or 5D") }
        guard z.shape[1] == zDim else {
            throw VaeError.unsupported("expected \(zDim) latent channels, got \(z.shape[1])")
        }
        guard zDim == 16 else {
            throw VaeError.unsupported("latent statistics are only known for 16-channel VAEs")
        }

        // [N, C, H, W] -> [N, H, W, C]
        var x = z.transposed(0, 2, 3, 1)
        let mean = MLXArray(Self.latentsMean).reshaped([1, 1, 1, zDim])
        let std = MLXArray(Self.latentsStd).reshaped([1, 1, 1, zDim])
        x = x * std + mean

        // post_quant_conv is the engine's `conv2` block.
        x = try conv(x, "conv2", padding: 0)
        eval(x)
        x = try conv(x, "decoder.conv1", padding: 1)
        eval(x)

        x = try residualBlock(x, "decoder.middle.0.")
        x = try attention(x, "decoder.middle.1.")
        x = try residualBlock(x, "decoder.middle.2.")
        eval(x)

        var index = 0
        let stages = dimMult.count
        for i in 0..<stages {
            for _ in 0...numResBlocks {
                x = try residualBlock(x, "decoder.upsamples.\(index).")
                index += 1
            }
            if i != stages - 1 {
                x = try upsampleBlock(x, "decoder.upsamples.\(index).")
                index += 1
            }
            // Evaluate per stage so each Metal submission stays short; lazy MLX
            // would otherwise fuse the entire decoder into one long command
            // buffer, which is the very thing this path exists to avoid.
            eval(x)
        }

        x = rmsNorm(x, try? tensor("decoder.head.0.gamma"))
        x = silu(x)
        x = try conv(x, "decoder.head.2", padding: 1)
        x = clip(x, min: -1.0, max: 1.0)
        eval(x)
        return x
    }

    // MARK: - Latent blob

    /// Reads the blob written by the engine's `SD_LATENTS_DUMP` hook.
    ///
    /// Layout: `"PLLV"`, u32 version, i32 ndim, ndim i32 dims in the engine's
    /// shape order (fastest axis first), then contiguous f32. That memory order
    /// is already `[batch, channel, H, W]` row-major, so a reshape is enough.
    static func latents(from url: URL) throws -> MLXArray {
        let data = try Data(contentsOf: url)
        guard data.count >= 12 else { throw VaeError.unreadable(url.path) }
        let magic: UInt32 = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt32.self) }
        guard magic == 0x564C4C50 else { throw VaeError.unreadable("bad magic in \(url.path)") }
        let version: Int32 = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 4, as: Int32.self) }
        guard version == 1 else { throw VaeError.unsupported("latent blob version \(version)") }
        let ndim: Int32 = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 8, as: Int32.self) }
        guard ndim >= 3, ndim <= 5 else { throw VaeError.unsupported("latent ndim \(ndim)") }

        var offset = 12
        var dimsFastestFirst: [Int] = []
        for _ in 0..<ndim {
            let d: Int32 = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: Int32.self) }
            dimsFastestFirst.append(Int(d))
            offset += 4
        }
        let count = dimsFastestFirst.reduce(1, *)
        guard data.count >= offset + count * MemoryLayout<Float>.size else {
            throw VaeError.unreadable("truncated latent blob")
        }
        let floats: [Float] = data.withUnsafeBytes { raw in
            let src = raw.baseAddress!.advanced(by: offset)
            return Array(UnsafeBufferPointer(start: src.assumingMemoryBound(to: Float.self), count: count))
        }
        let rowMajor = Array(dimsFastestFirst.reversed())
        return MLXArray(floats).reshaped(rowMajor)
    }
}

import Foundation
import MLX
import MLXNN

/// Decoder-only Swift port of the two VAE families the engine can hand over:
/// the 3D causal VAE used by Qwen-Image and Wan 2.1 (`qwen_image_vae.safetensors`)
/// and the 2D `AutoencoderKL` used by SD1.5, SDXL and FLUX (`ae.safetensors`).
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
/// Weight names follow the engine's block paths (`conv2`, `decoder.conv1`,
/// `decoder.middle.0`, `decoder.upsamples.N`, ...). Files exported by diffusers
/// use the same blocks under different names (`post_quant_conv`, `conv_in`,
/// `norm_out`, `conv_out`), so the key space is rewritten into the engine's
/// vocabulary once at load. A leading wrapper prefix (`first_stage_model.`,
/// `vae.`) is detected from the file rather than assumed.
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

    /// Read from the safetensors header without touching the tensor data, so it
    /// is cheap enough to run before a generation rather than after it fails.
    struct Inspection {
        enum Layout: Equatable {
            /// The 3D causal Wan/Qwen decoder this class implements.
            case wan3D
            /// A 2D SD/SDXL/FLUX decoder: same idea, unrelated block layout.
            case diffusers2D
            case unknown
        }

        let layout: Layout
        let prefix: String
        let latentChannels: Int

        /// Why this file cannot be decoded here, or nil when it can.
        var problem: String? {
            switch layout {
            case .wan3D:
                guard latentChannels == 0 || latentChannels == 16 else {
                    return "unsupported \(latentChannels)-channel VAE (Wan 2.2 TI2V?); only the 16-channel Wan/Qwen VAE is implemented"
                }
                return nil
            case .diffusers2D:
                guard latentChannels > 0 else {
                    return "2D diffusers VAE without a readable latent channel count"
                }
                return nil
            case .unknown:
                return "no decoder.conv1/conv_in tensors — not a VAE file (a full checkpoint has no decoder)"
            }
        }
    }

    /// `nil` when the file can be decoded here, otherwise the reason.
    static func decodeProblem(_ path: String) -> String? {
        do { return try inspect(path).problem } catch { return error.localizedDescription }
    }

    static func inspect(_ path: String) throws -> Inspection {
        var shapes: [String: [Int]]
        do {
            shapes = try headerShapes(path)
        } catch {
            throw VaeError.unreadable("\(path): \(error.localizedDescription)")
        }

        func lookup(_ anchors: [String]) -> (String, [Int])? {
            for anchor in anchors {
                if let shape = shapes[anchor] { return ("", shape) }
                if let hit = shapes.first(where: { $0.key.hasSuffix("." + anchor) }) {
                    return (String(hit.key.dropLast(anchor.count)), hit.value)
                }
            }
            return nil
        }

        if let (prefix, shape) = lookup(["decoder.conv1.weight"]) {
            return Inspection(layout: .wan3D, prefix: prefix,
                              latentChannels: shape.count > 1 ? shape[1] : 0)
        }
        if let (prefix, shape) = lookup(["decoder.conv_in.weight"]) {
            // diffusers names (`up_blocks`, `mid_block`) or the original LDM ones
            // (`up.N.block.M`, `mid.block_1`); both decode the same way.
            let is2D = shapes.keys.contains {
                $0.hasPrefix(prefix + "decoder.up_blocks.") || $0.hasPrefix(prefix + "decoder.mid_block.")
                    || $0.hasPrefix(prefix + "decoder.up.") || $0.hasPrefix(prefix + "decoder.mid.")
            }
            return Inspection(layout: is2D ? .diffusers2D : .wan3D, prefix: prefix,
                              latentChannels: shape.count > 1 ? shape[1] : 0)
        }
        return Inspection(layout: .unknown, prefix: "", latentChannels: 0)
    }

    /// The safetensors header: u64 length, then a JSON object of
    /// `name -> {dtype, shape, data_offsets}`. Reading 8 bytes and one JSON blob
    /// is what makes inspection cheap; the tensors themselves are mmapped later.
    private static func headerShapes(_ path: String) throws -> [String: [Int]] {
        guard let handle = FileHandle(forReadingAtPath: path) else {
            throw VaeError.unreadable(path)
        }
        defer { try? handle.close() }
        guard let lengthBytes = try handle.read(upToCount: 8), lengthBytes.count == 8 else {
            throw VaeError.unreadable("truncated header")
        }
        let length = lengthBytes.withUnsafeBytes { $0.loadUnaligned(as: UInt64.self) }
        guard length > 2, length < 64 * 1024 * 1024 else {
            throw VaeError.unreadable("bad header length \(length)")
        }
        guard let json = try handle.read(upToCount: Int(length)),
              let object = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw VaeError.unreadable("header is not valid JSON")
        }
        var out: [String: [Int]] = [:]
        for (key, value) in object where key != "__metadata__" {
            guard let entry = value as? [String: Any] else { continue }
            if let shape = entry["shape"] as? [Int] { out[key] = shape }
        }
        guard !out.isEmpty else { throw VaeError.unreadable("no tensors in header") }
        return out
    }

    private(set) var weights: [String: MLXArray]

    /// Compute dtype. The engine decodes in f32 (bf16 files are converted on
    /// load) and the widest stage is only ~230 MB, so match it instead of
    /// introducing a bf16 path the Metal kernels may or may not cover.
    static let compute: DType = .float32

    /// Alternate spellings of the same modules, as `(diffusers, engine)`. Only
    /// the decoder is aliased; the encoder is dropped at load.
    private static let aliases: [(String, String)] = [
        ("decoder.conv_in.",   "decoder.conv1."),
        ("decoder.norm_out.",  "decoder.head.0."),
        ("decoder.conv_out.",  "decoder.head.2."),
        ("post_quant_conv.",   "conv2."),
        ("post_quant.",        "conv2."),
    ]

    /// Original-LDM decoder spelling -> diffusers spelling, so the 2D decoder
    /// below only ever sees one vocabulary. The SD1.5 VAE shipped in LDM form
    /// (`decoder.up.3.block.0`, `mid.attn_1`); SDXL/FLUX files are already
    /// diffusers-shaped.
    private static let ldm2dAliases: [(String, String)] = {
        var out: [(String, String)] = [
            ("decoder.mid.block_1.", "decoder.mid_block.resnets.0."),
            ("decoder.mid.block_2.", "decoder.mid_block.resnets.1."),
            ("decoder.mid.attn_1.",  "decoder.mid_block.attentions.0."),
            ("decoder.norm_out.",    "decoder.conv_norm_out."),
        ]
        for i in 0..<4 {
            for j in 0..<3 {
                out.append(("decoder.up.\(3 - i).block.\(j).",
                            "decoder.up_blocks.\(i).resnets.\(j)."))
            }
            if i < 3 {
                out.append(("decoder.up.\(3 - i).upsample.",
                            "decoder.up_blocks.\(i).upsamplers.0.conv."))
            }
        }
        return out
    }()

    /// LDM attention module names, applied only inside `attentions.0.`.
    private static let ldm2dAttention: [(String, String)] = [
        ("norm.",     "group_norm."),
        ("q.",        "to_q."),
        ("k.",        "to_k."),
        ("v.",        "to_v."),
        ("proj_out.", "to_out.0."),
    ]

    let layout: Inspection.Layout
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
        // Classify from the header first: a 2D SD/FLUX VAE or a full checkpoint
        // used to surface much later as a missing tensor, after minutes of work.
        let info = try Self.inspect(vaePath)
        if let problem = info.problem { throw VaeError.unsupported(problem) }

        let arrays: [String: MLXArray]
        do {
            arrays = try loadArrays(url: URL(fileURLWithPath: vaePath))
        } catch {
            throw VaeError.unreadable("\(vaePath): \(error.localizedDescription)")
        }
        guard !arrays.isEmpty else { throw VaeError.unreadable(vaePath) }

        // The encoder half is never used; dropping it keeps the lookup table
        // (and the mmapped pages it pins) to the decoder.
        var normalized: [String: MLXArray] = [:]
        normalized.reserveCapacity(arrays.count / 2)
        for (key, value) in arrays where key.hasPrefix(info.prefix) {
            let bare = String(key.dropFirst(info.prefix.count))
            normalized[info.layout == .diffusers2D ? Self.diffusersName(bare) : Self.engineName(bare)] = value
        }
        if info.layout == .diffusers2D {
            // post_quant_conv is optional: DiT VAEs (SD3, FLUX.1, Z-Image) are
            // trained without one, which is why sd.cpp only builds it when
            // `use_quant` holds (`auto_encoder_kl.hpp:568`).
            guard normalized["decoder.conv_in.weight"] != nil else {
                throw VaeError.missing("decoder.conv_in.weight")
            }
        } else {
            guard normalized["decoder.conv1.weight"] != nil else {
                throw VaeError.missing("decoder.conv1.weight")
            }
        }
        self.weights = normalized
        self.layout = info.layout
        self.zDim = info.layout == .diffusers2D ? info.latentChannels : zDim
        self.dim = dim
        self.dimMult = dimMult
        self.numResBlocks = numResBlocks
    }

    // MARK: - Tensor lookup

    private func has(_ name: String) -> Bool {
        weights[name] != nil
    }

    private func tensor(_ name: String) throws -> MLXArray {
        guard let a = weights[name] else { throw VaeError.missing(name) }
        return a
    }

    private static func engineName(_ key: String) -> String {
        for (from, to) in Self.aliases where key.hasPrefix(from) {
            return to + key.dropFirst(from.count)
        }
        return key
    }

    /// Rewrites an original-LDM key into the diffusers vocabulary the 2D decoder
    /// below reads. Keys already in diffusers form pass through untouched.
    private static func diffusersName(_ key: String) -> String {
        var k = key
        for (from, to) in Self.ldm2dAliases {
            if let r = k.range(of: from) {
                k.replaceSubrange(r, with: to)
                break
            }
        }
        if let r = k.range(of: "attentions.0.") {
            let head = String(k[..<r.upperBound])
            let tail = String(k[r.upperBound...])
            for (from, to) in Self.ldm2dAttention where tail.hasPrefix(from) {
                return head + to + tail.dropFirst(from.count)
            }
        }
        if let r = k.range(of: "nin_shortcut.") {
            k.replaceSubrange(r, with: "conv_shortcut.")
        }
        return k
    }

    private func bias(_ name: String) -> MLXArray? {
        weights[name + ".bias"]
    }

    /// PyTorch keeps convolution weights as `[out, in, kt, kh, kw]`; mlx-swift
    /// wants `[out, kh, kw, in]`. For a single frame, the temporal dimension
    /// collapses to the last tap (two zero frames are padded on the left).
    private func convWeight(_ name: String) throws -> MLXArray {
        var w = try tensor(name)
        // MLX convolutions want one dtype throughout.
        w = w.asType(Self.compute)
        if w.ndim == 5 {
            w = w[0..., 0..., w.shape[2] - 1, 0..., 0...]
        }
        return w.transposed(0, 2, 3, 1)
    }

    // MARK: - Ops (NHWC throughout)

    private func conv(_ x: MLXArray, _ name: String, padding: Int) throws -> MLXArray {
        let w = try convWeight(name + ".weight")
        var y = conv2d(x, w, stride: 1, padding: .init(padding))
        if let b = bias(name) {
            y = y + b.asType(Self.compute).reshaped([1, 1, 1, b.size])
        }
        // Evaluate per convolution. MLX is lazy, so without this the whole
        // stage would fuse into one graph and hold every intermediate alive at
        // once - both the memory spike and the long Metal submission this path
        // is meant to avoid.
        eval(y)
        return y
    }

    /// Matches the engine's RMS norm: divide by sqrt(mean(x^2) + eps) over the
    /// channel axis, then scale by `gamma`. (Over channels this is equivalent to
    /// diffusers' `L2-normalize * sqrt(C)`.)
    private func rmsNorm(_ x: MLXArray, _ gamma: MLXArray?) -> MLXArray {
        let meanSq = (x * x).mean(axis: -1, keepDims: true)
        var y = x / sqrt(meanSq + 1e-12)
        if let gamma {
            y = y * gamma.asType(Self.compute).reshaped([1, 1, 1, gamma.size])
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
        // MLX reports errors through a process-wide handler whose default is
        // `fatalError`. Scoping it converts them into a thrown Swift error, so
        // a bad tensor name or an allocation failure lands in the app log
        // instead of killing the process.
        try withError { _ in
            switch layout {
            case .diffusers2D:
                return try decode2D(latents)
            default:
                return try decodeChecked(latents: latents)
            }
        }
    }

    private func decodeChecked(latents: MLXArray) throws -> MLXArray {
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
        x = (x * std + mean).asType(Self.compute)

        // post_quant_conv is the engine's `conv2` block.
        x = try conv(x, "conv2", padding: 0)
        x = try conv(x, "decoder.conv1", padding: 1)

        x = try residualBlock(x, "decoder.middle.0.")
        x = try attention(x, "decoder.middle.1.")
        x = try residualBlock(x, "decoder.middle.2.")
        eval(x)

        // Mirror Decoder3d::Decoder3d in wan_vae.hpp, which is what wrote the
        // tensor names. Its `dims` list starts with `dim_mult.last * dim`
        // repeated, so for dim=96, dim_mult={1,2,4,4} it is
        // [384, 384, 384, 192, 96, 96] and there are five groups, not four:
        //
        //   upsamples.0-2   Residual(384)         upsamples.3  Resample(384)
        //   upsamples.4-6   Residual(192->384)    upsamples.7  Resample(384)
        //   upsamples.8-10  Residual(192)         upsamples.11 Resample(192)
        //   upsamples.12-14 Residual(96)
        //   upsamples.15-17 Residual(96)
        //
        // The channel change sits on the *first* residual of a group; the
        // engine's `in_dim / 2` quirk for i in 1...3 is already baked into
        // those weights, so this loop only walks indices and resample slots.
        let dims = [dimMult[dimMult.count - 1] * dim] + dimMult.reversed().map { dim * $0 }

        var index = 0
        let t0 = Date()
        for i in 0..<(dims.count - 1) {
            for _ in 0...numResBlocks {
                x = try residualBlock(x, "decoder.upsamples.\(index).")
                index += 1
            }
            if i != dimMult.count - 1 {
                x = try upsampleBlock(x, "decoder.upsamples.\(index).")
                index += 1
            }
            // Group markers: if MLX throws or the process dies, the last line
            // in the log names where.
            eval(x)
            appLogMemory(String(format: "sd: mlx vae group %d/%d %dx%d %dch in %.2fs",
                                i + 1, dims.count - 1, x.shape[2], x.shape[1], x.shape[3],
                                Date().timeIntervalSince(t0)), source: "sd")
        }

        x = rmsNorm(x, try? tensor("decoder.head.0.gamma"))
        x = silu(x)
        x = try conv(x, "decoder.head.2", padding: 1)
        x = clip(x, min: -1.0, max: 1.0).asType(.float32)
        eval(x)
        return x
    }

    // MARK: - 2D diffusers decoder (SD1.5 / SDXL / FLUX)

    /// GroupNorm over channels, `groups` fixed at 32 like every 2D VAE, with the
    /// engine's `eps` (GroupNorm32 uses 1e-6).
    private func groupNorm2D(_ x: MLXArray, _ name: String, groups: Int = 32, eps: Float = 1e-6) throws -> MLXArray {
        let (n, h, w, c) = (x.shape[0], x.shape[1], x.shape[2], x.shape[3])
        let g = c % groups == 0 ? groups : 1
        // [n, hw, g, c/g]: the group axis is kept separate so the mean can be
        // taken with the single-axis form the rest of this file already uses.
        var y = x.reshaped([n, h * w, g, c / g])
        let mean = y.mean(axis: 3, keepDims: true).mean(axis: 1, keepDims: true)
        y = y - mean
        let variance = (y * y).mean(axis: 3, keepDims: true).mean(axis: 1, keepDims: true)
        y = y / sqrt(variance + eps)
        y = y.reshaped([n, h, w, c])
        if let gamma = weights[name + ".weight"] {
            y = y * gamma.asType(Self.compute).reshaped([1, 1, 1, c])
        }
        if let beta = weights[name + ".bias"] {
            y = y + beta.asType(Self.compute).reshaped([1, 1, 1, c])
        }
        return y
    }

    private func conv2D(_ x: MLXArray, _ name: String, padding: Int) throws -> MLXArray {
        let w = try tensor(name + ".weight").asType(Self.compute).transposed(0, 2, 3, 1)
        var y = conv2d(x, w, stride: 1, padding: .init(padding))
        if let b = weights[name + ".bias"] {
            y = y + b.asType(Self.compute).reshaped([1, 1, 1, b.size])
        }
        eval(y)
        return y
    }

    private func linear2D(_ x: MLXArray, _ name: String) throws -> MLXArray {
        var w = try tensor(name + ".weight").asType(Self.compute)
        // The original LDM attention projects with 1x1 convolutions, diffusers
        // with Linear layers; both reduce to the same [out, in] matrix.
        if w.ndim == 4 { w = w.reshaped([w.shape[0], w.shape[1]]) }
        var y = matmul(x, w.transposed(1, 0))
        if let b = weights[name + ".bias"] {
            y = y + b.asType(Self.compute)
        }
        return y
    }

    private func resnet2D(_ x: MLXArray, _ p: String) throws -> MLXArray {
        var h = try groupNorm2D(x, p + "norm1")
        h = silu(h)
        h = try conv2D(h, p + "conv1", padding: 1)
        h = try groupNorm2D(h, p + "norm2")
        h = silu(h)
        h = try conv2D(h, p + "conv2", padding: 1)
        var skip = x
        if has(p + "conv_shortcut.weight") {
            skip = try conv2D(x, p + "conv_shortcut", padding: 0)
        }
        return skip + h
    }

    /// Single-head self-attention over the spatial positions, as diffusers'
    /// `Attention` block does for a VAE: GroupNorm, three projections, softmax
    /// with 1/sqrt(C), then the output projection and a residual.
    private func attention2D(_ x: MLXArray, _ p: String) throws -> MLXArray {
        let (n, h, w, c) = (x.shape[0], x.shape[1], x.shape[2], x.shape[3])
        var y = try groupNorm2D(x, p + "group_norm")
        y = y.reshaped([n, h * w, c])
        let q = try linear2D(y, p + "to_q")
        let k = try linear2D(y, p + "to_k")
        let v = try linear2D(y, p + "to_v")
        let scale = 1.0 / sqrt(Float(c))
        let probs = softmax(matmul(q * scale, k.transposed(0, 2, 1)), axis: 2)
        var out = matmul(probs, v)
        out = try linear2D(out, p + "to_out.0")
        out = out.reshaped([n, h, w, c])
        eval(out)
        return out + x
    }

    /// `post_quant_conv` -> `conv_in` -> mid -> every `up_blocks` entry, with an
    /// upsample after each block that has one. Block and resnet counts come from
    /// the weights rather than a hard-coded config, because SD1.5, SDXL and FLUX
    /// all use this shape and only differ in channel counts.
    private func decode2D(_ z: MLXArray) throws -> MLXArray {
        var latents = z
        if latents.ndim == 5 {
            guard latents.shape[2] == 1 else {
                throw VaeError.unsupported("only single-frame decode is implemented (got T=\(latents.shape[2]))")
            }
            latents = latents.squeezed(axis: 2)
        }
        guard latents.ndim == 4 else { throw VaeError.unsupported("latents must be 4D or 5D") }
        guard latents.shape[1] == zDim else {
            throw VaeError.unsupported("expected \(zDim) latent channels, got \(latents.shape[1])")
        }

        // The caller passes VAE-space latents (the engine applied its scale
        // factor and shift before writing the blob), so there is no scaling here.
        var x = latents.transposed(0, 2, 3, 1).asType(Self.compute)
        if has("post_quant_conv.weight") {
            x = try conv2D(x, "post_quant_conv", padding: 0)
        }
        x = try conv2D(x, "decoder.conv_in", padding: 1)
        eval(x)

        x = try resnet2D(x, "decoder.mid_block.resnets.0.")
        x = try attention2D(x, "decoder.mid_block.attentions.0.")
        x = try resnet2D(x, "decoder.mid_block.resnets.1.")
        eval(x)

        var block = 0
        let t0 = Date()
        while has("decoder.up_blocks.\(block).resnets.0.conv1.weight") {
            var index = 0
            while has("decoder.up_blocks.\(block).resnets.\(index).conv1.weight") {
                x = try resnet2D(x, "decoder.up_blocks.\(block).resnets.\(index).")
                index += 1
            }
            if has("decoder.up_blocks.\(block).upsamplers.0.conv.weight") {
                x = try conv2D(nearest2x(x), "decoder.up_blocks.\(block).upsamplers.0.conv", padding: 1)
            }
            eval(x)
            appLogMemory(String(format: "sd: mlx vae 2d block %d %dx%d %dch in %.2fs",
                                block, x.shape[2], x.shape[1], x.shape[3],
                                Date().timeIntervalSince(t0)), source: "sd")
            block += 1
        }
        guard block > 0 else {
            throw VaeError.missing("decoder.up_blocks.0.resnets.0.conv1.weight")
        }

        x = try groupNorm2D(x, "decoder.conv_norm_out")
        x = silu(x)
        x = try conv2D(x, "decoder.conv_out", padding: 1)
        x = clip(x, min: -1.0, max: 1.0).asType(.float32)
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

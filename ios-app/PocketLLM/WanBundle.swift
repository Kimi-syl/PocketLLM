import Foundation

/// The Wan 2.2 TI2V 5B Turbo set, as one click.
///
/// These are the files `jc-builds/Wan2.2-TI2V-5B-Turbo-iOS` publishes for
/// on-device use: a 4-step self-forcing distill of Wan 2.2 TI2V 5B (Q4_K_S), its
/// multilingual umt5-xxl encoder (Q3_K_S), and `taew2_2.safetensors` - a 23 MB
/// tiny decoder that replaces the 1.4 GB VAE, which is what makes a dozen-frame
/// decode seconds instead of minutes.
///
/// Every field the app needs is derived from the file headers rather than
/// guessed, and the two that matter here are:
///
/// - the DiT's tensors are `blocks.*`, which is how `ModelAudit` recognises a
///   diffusion model regardless of the architecture tag;
/// - the encoder's tensors are `enc.blk.*` (the spelling stable-diffusion.cpp's
///   own T5/umt5 GGUF conversion writes), which is why `ModelAudit` had to learn
///   it before this bundle could be recognised as complete rather than "missing a
///   text encoder";
/// - the tiny decoder's are `encoder.<N>.*` / `decoder.<N>.*` (numeric blocks),
///   its own role rather than a VAE, because it loads through `taesd_path`.
enum WanBundle {
    static let repo = "jc-builds/Wan2.2-TI2V-5B-Turbo-iOS"

    static let diffusion = RemoteModelFile(
        id: "wan-dit", file: "Wan2_2-TI2V-5B-Turbo-Q4_K_S.gguf", megabytes: 3121,
        url: RemoteModelFile.hf(repo, "Wan2_2-TI2V-5B-Turbo-Q4_K_S.gguf"))
    static let encoder = RemoteModelFile(
        id: "wan-te", file: "umt5-xxl-encoder-Q3_K_S.gguf", megabytes: 2858,
        url: RemoteModelFile.hf(repo, "umt5-xxl-encoder-Q3_K_S.gguf"))
    static let decoder = RemoteModelFile(
        id: "wan-tae", file: "taew2_2.safetensors", megabytes: 23,
        url: RemoteModelFile.hf(repo, "taew2_2.safetensors"))

    static let files: [RemoteModelFile] = [diffusion, encoder, decoder]
    static let totalMegabytes = files.reduce(0) { $0 + $1.megabytes }

    /// Where they are saved. One folder per repository, so the library and the
    /// video tab agree about what a complete set looks like.
    static var directory: URL { ModelDownload.directory("Video/\(repo)") }
}

import Foundation

/// A named set of generation settings.
///
/// Two kinds share one type: the built-ins below (the values a model family is
/// usually run with) and the user's own saved snapshots of the tab. A preset
/// carries the model slots as well as the sampling parameters because the two
/// belong together — a Turbo checkpoint at 20 steps and CFG 7 is a blank image —
/// and the engine keeps its slots in memory only, so a saved preset is also the
/// one way to get a model set back after a relaunch.
struct GenPreset: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var mainModel = ""
    var textEncoder = ""
    var vae = ""
    var loras = ""
    var steps = 20
    var cfg: Float = 7
    var width = 512
    var height = 512
    var seed: Int64 = -1
    var sampleMethod = 0
    var scheduler = 0
    var clipSkip = 1
    var negative = ""

    /// Listed before the saved ones, and not deletable. A built-in also leaves
    /// the model slots alone when applied: it only carries sampling values.
    var isBuiltin = false
}

extension GenPreset {
    /// Values per model family, in the order the menu shows them. The sampler
    /// and scheduler stay at the library defaults (index 0) on purpose: the
    /// indices are the engine's own tables, and hard-coding a name's position
    /// would break silently if the library ever reorders them.
    static let builtins: [GenPreset] = [
        GenPreset(name: "Turbo / LCM · 8 步 CFG 1",
                  steps: 8, cfg: 1, width: 1024, height: 1024, isBuiltin: true),
        GenPreset(name: "FLUX · 16 步 CFG 1",
                  steps: 16, cfg: 1, width: 1024, height: 1024, isBuiltin: true),
        GenPreset(name: "Z-Image Turbo · 9 步 CFG 1",
                  steps: 9, cfg: 1, width: 1024, height: 1024, isBuiltin: true),
        GenPreset(name: "SDXL · 25 步 CFG 7",
                  steps: 25, cfg: 7, width: 1024, height: 1024, isBuiltin: true),
        GenPreset(name: "SD1.5 · 20 步 CFG 7.5",
                  steps: 20, cfg: 7.5, width: 512, height: 512, isBuiltin: true),
    ]

    /// The built-in that matches the loaded diffusion file.
    ///
    /// Distilled checkpoints are found by name first: turbo/lcm/lightning/hyper
    /// are a training recipe on top of an architecture, not an architecture, so
    /// only the file name says it. Everything else goes by the GGUF header's
    /// architecture, and anything unrecognised returns nil rather than a guess.
    static func recommended(for modelPath: String) -> GenPreset? {
        guard !modelPath.isEmpty else { return nil }
        let url = URL(fileURLWithPath: modelPath)
        let name = url.lastPathComponent.lowercased()
        for marker in ["turbo", "lcm", "lightning", "hyper", "schnell"] where name.contains(marker) {
            return builtins[0]
        }
        switch ModelAudit.facts(of: url).architecture {
        case "flux", "flux2", "flux3", "chroma", "hidream":
            return builtins[1]
        case "z_image":
            return builtins[2]
        case "sdxl":
            return builtins[3]
        case "sd1", "sd2", "sd3":
            return builtins[4]
        default:
            return nil
        }
    }

    private static let storeKey = "sd.presets"

    /// Saved presets only; the built-ins are code, not data.
    static func loadStored() -> [GenPreset] {
        guard let data = UserDefaults.standard.data(forKey: storeKey),
              let list = try? JSONDecoder().decode([GenPreset].self, from: data) else { return [] }
        return list.filter { !$0.isBuiltin }
    }

    static func persist(_ presets: [GenPreset]) {
        let saved = presets.filter { !$0.isBuiltin }
        guard let data = try? JSONEncoder().encode(saved) else { return }
        UserDefaults.standard.set(data, forKey: storeKey)
    }
}

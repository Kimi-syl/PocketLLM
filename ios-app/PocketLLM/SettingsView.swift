import SwiftUI

/// Inference, prompt and account settings.
///
/// Three of these — context size, thread count and Metal offload — are read when
/// a model is *loaded*, not while it runs, because llama.cpp fixes the KV cache
/// and the device layer split at load time. Saying so in the UI is cheaper than
/// a user wondering why a change did nothing.
struct SettingsView: View {
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var speech: PhononTTSEngine

    var body: some View {
        NavigationStack {
            Form {
                inferenceSection
                promptSection
                accountSection
                speechSection
                serverSection
            }
            .navigationTitle("Settings")
        }
    }

    private var inferenceSection: some View {
        Section {
            Picker("Context length", selection: $settings.contextSize) {
                ForEach(AppSettings.contextChoices, id: \.self) { size in
                    Text("\(size)").tag(size)
                }
            }
            Stepper("Max tokens: \(settings.maxTokens)",
                    value: $settings.maxTokens, in: 128...8192, step: 128)
            VStack(alignment: .leading) {
                Text("Temperature: \(settings.temperature, specifier: "%.2f")")
                Slider(value: $settings.temperature, in: 0...2, step: 0.05)
            }
            VStack(alignment: .leading) {
                Text("Top-p: \(settings.topP, specifier: "%.2f")")
                Slider(value: $settings.topP, in: 0...1, step: 0.01)
            }
            Stepper("Top-k: \(settings.topK)", value: $settings.topK, in: 1...200)
            Stepper("Threads: \(settings.threads)", value: $settings.threads, in: 1...8)
            Toggle("Metal GPU offload", isOn: $settings.gpuOffload)

            Text("Context length, threads and Metal offload apply when the model is loaded — reload from the Model menu to pick them up. Top-k is llama.cpp only; MLX samples without it.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Inference")
        }
    }

    private var promptSection: some View {
        Section {
            TextField("You are a helpful assistant…", text: $settings.systemPrompt, axis: .vertical)
                .lineLimit(3...8)
                .autocorrectionDisabled()
            Text("Prepended to every reply, on all three backends. Not shown in the conversation.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("System prompt")
        }
    }

    private var accountSection: some View {
        Section {
            SecureField("hf_…", text: $settings.hfToken)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Text("Only needed for gated Hugging Face repositories. Public models download without it.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Access token")
        }
    }


    /// Speech is on-device now: Phonon runs on the Neural Engine and streams the
    /// first 80 ms chunk before the sentence finishes. Everything here exists to
    /// answer the two questions this raises — "is it running on the device" and
    /// "how fast is it on this phone" — because a published benchmark is not a
    /// measurement of the hardware in hand.
    private var speechSection: some View {
        Section {
            switch speech.state {
            case .systemFallback:
                LabeledContent("Engine", value: "System voice")
                Text("Phonon needs iOS 18. This device runs the system voice instead.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            case .notInstalled:
                LabeledContent("Engine", value: "Phonon 31M")
                Text("The model folder is not installed. Add a folder named Models to the app, or download it on first run.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            case .downloading(let fraction):
                LabeledContent("Downloading", value: String(format: "%.0f%%", fraction * 100))
                ProgressView(value: fraction)
            case .loading:
                LabeledContent("Engine", value: "Loading…")
                Text("The first launch compiles the Core ML models for this device, which takes about ten seconds.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            case .ready:
                LabeledContent("Engine", value: "Phonon 31M · Neural Engine")
                if speech.voices.isEmpty == false {
                    Picker("Voice", selection: Binding(
                        get: { speech.selectedVoice ?? speech.voices[0] },
                        set: { speech.setVoice($0) }
                    )) {
                        ForEach(speech.voices, id: \.self) { Text($0).tag($0) }
                    }
                }
                if let ms = speech.lastFirstChunkMilliseconds {
                    LabeledContent("First audio", value: String(format: "%.0f ms", ms))
                }
                if let realtime = speech.lastRealtimeFactor {
                    LabeledContent("Speed", value: String(format: "%.1f× real time", realtime))
                }
            case .failed(let message):
                LabeledContent("Engine", value: "Failed")
                Text(message).font(.caption).foregroundStyle(.red)
            }

            HStack {
                Button("Speak a sample") {
                    speech.speak("PocketLLM is running entirely on this device.")
                }
                Spacer()
                Button("Stop") { speech.stop() }
                    .disabled(speech.state != .ready)
            }
        } header: {
            Text("Speech")
        } footer: {
            Text("Speech never leaves the phone: the model runs on the Neural Engine and streams audio as it generates.")
        }
    }

    private var serverSection: some View {
        Section {
            TextField("http://192.168.1.100:8080/", text: $settings.serverURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            Text("An OpenAI-compatible endpoint, e.g. the PocketLLM desktop CLI's `serve` or the Android app on the same network.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Remote server")
        }
    }
}

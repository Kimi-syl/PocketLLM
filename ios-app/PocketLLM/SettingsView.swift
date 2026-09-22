import SwiftUI

/// Inference, prompt and account settings.
///
/// Three of these — context size, thread count and Metal offload — are read when
/// a model is *loaded*, not while it runs, because llama.cpp fixes the KV cache
/// and the device layer split at load time. Saying so in the UI is cheaper than
/// a user wondering why a change did nothing.
struct SettingsView: View {
    @EnvironmentObject var settings: AppSettings

    var body: some View {
        NavigationStack {
            Form {
                inferenceSection
                promptSection
                accountSection
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

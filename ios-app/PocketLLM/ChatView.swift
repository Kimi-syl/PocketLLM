import SwiftUI

struct ChatView: View {
    @State private var serverURL = "http://192.168.1.100:8080/"
    @State private var input = ""
    @State private var messages: [OpenAIClient.Message] = []
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 0) {
            TextField("Server URL", text: $serverURL)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            ScrollView {
                ForEach(messages) { m in
                    HStack {
                        if m.role == "user" { Spacer() }
                        Text(m.content)
                            .padding(10)
                            .background(m.role == "user" ? Color.accentColor.opacity(0.25) : Color(.systemGray5))
                            .cornerRadius(12)
                        if m.role != "user" { Spacer() }
                    }
                    .padding(.horizontal)
                }
            }
            if let error { Text(error).foregroundStyle(.red).font(.footnote) }
            HStack {
                TextField("Message", text: $input)
                    .textFieldStyle(.roundedBorder)
                Button(busy ? "..." : "Send") { send() }
                    .disabled(input.isEmpty || busy)
            }
            .padding()
        }
        .navigationTitle("PocketLLM")
    }

    private func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy, let url = URL(string: serverURL) else { return }
        input = ""
        messages.append(.init(role: "user", content: text))
        busy = true
        error = nil
        let client = OpenAIClient(baseURL: url)
        let history = messages
        Task {
            do {
                let reply = try await client.complete(messages: history)
                messages.append(.init(role: "assistant", content: reply))
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}

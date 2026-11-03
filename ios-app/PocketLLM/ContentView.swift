import SwiftUI

struct ContentView: View {
    @EnvironmentObject var speech: PhononTTSEngine

    var body: some View {
        TabView {
            ChatView()
                .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right") }
            ModelsView()
                .tabItem { Label("Models", systemImage: "square.stack.3d.down.right") }
            AgentKitView()
                .tabItem { Label("AgentKit", systemImage: "brain") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
            ImageGenView()
                .tabItem { Label("Images", systemImage: "photo") }
            VideoGenView()
                .tabItem { Label("Video", systemImage: "film") }
            GalleryView()
                .tabItem { Label("Gallery", systemImage: "photo.on.rectangle.angled") }
            LogView()
                .tabItem { Label("Logs", systemImage: "doc.text.magnifyingglass") }
        }
        // Load the speech model once, at launch: the first load compiles the
        // Core ML bundle (~10 s) and every later load is ~0.6 s, so doing it here
        // means the first reply that speaks is not the one that pays for it. It
        // also logs why speech is unavailable when it is, instead of leaving the
        // Settings row on "system voice" with no explanation.
        .task { await speech.prepare() }
    }
}

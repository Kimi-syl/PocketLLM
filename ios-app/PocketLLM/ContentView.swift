import SwiftUI

struct ContentView: View {
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
            PhotoAnalysisView()
                .tabItem { Label("照片分析", systemImage: "photo.on.rectangle") }
                .tabItem { Label("Images", systemImage: "photo") }
            LogView()
                .tabItem { Label("Logs", systemImage: "doc.text.magnifyingglass") }
        }
    }
}

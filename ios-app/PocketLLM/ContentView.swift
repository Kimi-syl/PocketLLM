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
        }
    }
}

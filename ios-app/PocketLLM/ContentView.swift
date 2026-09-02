import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            ChatView()
                .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right") }
            AgentKitView()
                .tabItem { Label("AgentKit", systemImage: "brain") }
        }
    }
}

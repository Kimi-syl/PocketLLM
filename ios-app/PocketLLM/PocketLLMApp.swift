import SwiftUI

@main
struct PocketLLMApp: App {
    /// One library instance for the whole app, so a download started in the
    /// Models tab is immediately visible to the Chat tab's model menu.
    @StateObject private var store = ModelStore.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
    }
}

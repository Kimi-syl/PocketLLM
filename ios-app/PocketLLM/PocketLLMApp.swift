import SwiftUI

@main
struct PocketLLMApp: App {
    /// One library instance for the whole app, so a download started in the
    /// Models tab is immediately visible to the Chat tab's model menu.
    @StateObject private var store = ModelStore.shared
    @StateObject private var settings = AppSettings.shared
    /// Also a singleton: the image tab writes into it from `SdEngine`, which has
    /// no environment of its own.
    @StateObject private var gallery = GalleryStore.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(settings)
                .environmentObject(gallery)
        }
    }
}

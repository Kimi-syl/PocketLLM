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
    /// One TTS engine for the whole app: the Phonon model takes ~0.6 s to load
    /// and ~10 s to compile the first time, so it must not be re-created per view.
    /// It falls back to the system voice when the Phonon package is absent.
    @StateObject private var speech = PhononTTSEngine()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(settings)
                .environmentObject(gallery)
                .environmentObject(speech)
        }
    }
}

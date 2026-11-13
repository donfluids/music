import SwiftUI

@main
struct KeyCatchApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(model.darkTheme.map { $0 ? .dark : .light })
                .modifier(PaletteProvider())
                // The microphone is released whenever the app leaves the foreground, as on Android.
                .onChange(of: scenePhase) { _, phase in if phase == .background { model.stop() } }
        }
    }
}

/// Picks the screen: the main one, the song history or What to expect.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            AnalyzerScreen()
                .navigationDestination(isPresented: $model.showHistory) { HistoryScreen() }
                .navigationDestination(isPresented: $model.showLimits) { LimitsScreen() }
        }
        .tint(p.primary)
        .sheet(isPresented: $model.showSettings) { SettingsScreen() }
    }
}

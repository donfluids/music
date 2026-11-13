import KeyCatchCore
import SwiftUI

/// The tuning reference, note names, chord names or numbers, how notes and the beat are heard,
/// and the theme.
/// Each is saved as soon as it changes.
struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Heading(S.settingsReference)
                    HStack(spacing: 8) {
                        StepButton(systemImage: "minus", label: S.settingsReferenceDown, enabled: model.referenceHz > minReferenceHz) {
                            model.setReferenceHz(model.referenceHz - 1)
                        }
                        Text(String(format: S.settingsReferenceValue, model.referenceHz))
                            .font(AppFont.titleMedium.monospacedDigit())
                            .frame(width: 96)
                        StepButton(systemImage: "plus", label: S.settingsReferenceUp, enabled: model.referenceHz < maxReferenceHz) {
                            model.setReferenceHz(model.referenceHz + 1)
                        }
                        if model.referenceHz != 440 {
                            Button(S.settingsReferenceReset) { model.setReferenceHz(440) }.font(AppFont.labelLarge)
                        }
                    }
                    Help(S.settingsReferenceHelp)

                    Heading(S.settingsNaming)
                    Segmented(labels: [S.namingLetters, S.namingDoReMi], selected: model.naming == .fixedDo ? 1 : 0) {
                        model.setNaming($0 == 1 ? .fixedDo : .letters)
                    }
                    Help(S.settingsNamingHelp)

                    Heading(S.settingsChords)
                    Segmented(labels: [S.chordsAsNames, S.chordsAsNumbers], selected: model.nashville ? 1 : 0) { model.setNashville($0 == 1) }
                    Help(S.settingsChordsHelp)

                    Heading(S.settingsNotes)
                    Segmented(labels: [S.notesStandard, S.notesBasicPitch], selected: model.basicPitch ? 1 : 0) { model.setBasicPitch($0 == 1) }
                    Help(S.settingsNotesHelp)

                    Heading(S.settingsBeat)
                    Segmented(labels: [S.beatStandard, S.beatBeatThis], selected: model.beatThis ? 1 : 0) { model.setBeatThis($0 == 1) }
                    Help(S.settingsBeatHelp)

                    Heading(S.settingsPitch)
                    Segmented(labels: [S.pitchStandard, S.pitchCrepe], selected: model.crepe ? 1 : 0) { model.setCrepe($0 == 1) }
                    Help(S.settingsPitchHelp)

                    Heading(S.settingsTheme)
                    Segmented(labels: [S.themeSystem, S.themeLight, S.themeDark],
                              selected: model.darkTheme == nil ? 0 : (model.darkTheme == false ? 1 : 2)) {
                        model.setTheme($0 == 0 ? nil : $0 == 2)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
            .background(p.background)
            .navigationTitle(S.settings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(S.done) { model.showSettings = false } }
            }
        }
        .tint(p.primary)
        .presentationDetents([.large])
    }
}

private struct Heading: View {
    let text: String
    init(_ text: String) { self.text = text }
    @Environment(\.palette) private var p
    var body: some View {
        Text(text).font(AppFont.labelLarge).foregroundStyle(p.onBackground).padding(.top, 10).accessibilityAddTraits(.isHeader)
    }
}

private struct Help: View {
    let text: String
    init(_ text: String) { self.text = text }
    @Environment(\.palette) private var p
    var body: some View {
        Text(text).font(AppFont.bodySmall).foregroundStyle(p.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
    }
}

import SwiftUI

/// What the app can and can't do, in plain words. Back returns to listening.
struct LimitsScreen: View {
    @Environment(\.palette) private var p

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(S.limitsIntro).font(AppFont.bodyLarge).foregroundStyle(p.onBackground)

                Heading(S.limitsWrongHeading)
                Item(lead: S.limitsOffPitchLead, body: S.limitsOffPitch)
                Item(lead: S.limitsDriftLead, body: S.limitsDrift)
                Item(lead: S.limitsKeyChangeLead, body: S.limitsKeyChange)
                Item(lead: S.limitsMajorMinorLead, body: S.limitsMajorMinor)
                Item(lead: S.limitsRoomLead, body: S.limitsRoom)
                Item(lead: S.limitsOneKeyLead, body: S.limitsOneKey)
                Item(lead: S.limitsBeatLead, body: S.limitsBeat)
                Item(lead: S.limitsVoicesLead, body: S.limitsVoices)

                Heading(S.limitsNotesHeading)
                Note(S.limitsNoteLate)
                Note(S.limitsNoteSimple)
                Note(S.limitsNoteTuning)
                Note(S.limitsNoteNew)

                Rectangle().fill(p.outlineVariant).frame(height: 1).padding(.top, 4)
                Text(S.limitsClosing).font(AppFont.bodyLarge).foregroundStyle(p.onBackground)
                Text(S.limitsPrivacy).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
                Heading(S.limitsThanksHeading)
                Text(S.limitsThanks).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
                Text(S.limitsPublisher).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
            .fixedSize(horizontal: false, vertical: true)
        }
        .background(p.background)
        .navigationTitle(S.limitsTitle)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct Heading: View {
    let text: String
    init(_ text: String) { self.text = text }
    @Environment(\.palette) private var p
    var body: some View {
        Text(text).font(AppFont.titleMedium).foregroundStyle(p.primary).padding(.top, 10).accessibilityAddTraits(.isHeader)
    }
}

/// A bold lead ("The room is busy.") followed by the explanation, as one paragraph.
private struct Item: View {
    let lead: String
    let text: String
    init(lead: String, body: String) { self.lead = lead; text = body }
    @Environment(\.palette) private var p
    var body: some View {
        (Text(lead).font(AppFont.manrope(16, .semibold, relativeTo: .body)) + Text(" " + text).font(AppFont.bodyLarge))
            .foregroundStyle(p.onBackground)
    }
}

private struct Note: View {
    let text: String
    init(_ text: String) { self.text = text }
    @Environment(\.palette) private var p
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("•").font(AppFont.bodyLarge).foregroundStyle(p.onSurfaceVariant)
            Text(text).font(AppFont.bodyLarge).foregroundStyle(p.onBackground)
        }
    }
}

import KeyCatchCore
import SwiftUI

/// The saved songs, grouped by day, newest first. Back returns to listening.
struct HistoryScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    @State private var confirmClear = false
    /// The last song deleted, offered back for a few seconds.
    @State private var undoSong: SongRecord?
    @State private var undoTask: Task<Void, Never>?

    var body: some View {
        let songs = model.history
        let naming = model.naming
        Group {
            if songs.isEmpty {
                Text(S.historyEmpty)
                    .font(AppFont.bodyLarge).foregroundStyle(p.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(HistoryText.days(songs), id: \.day) { group in
                        Section {
                            ForEach(group.songs) { song in
                                SongRow(song: song, naming: naming) { delete(song) }
                                    .listRowBackground(p.background)
                                    .listRowSeparatorTint(p.outlineVariant)
                                    .swipeActions { Button(S.delete, role: .destructive) { delete(song) } }
                            }
                        } header: {
                            Text(HistoryText.dayLabel(group.day))
                                .font(AppFont.labelLarge).foregroundStyle(p.primary).textCase(nil)
                                .accessibilityAddTraits(.isHeader)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .background(p.background)
        .navigationTitle(S.historyTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                ShareLink(item: HistoryText.text(songs, naming), subject: Text(S.historyShareTitle)) { Text(S.historyShare) }
                    .disabled(songs.isEmpty)
                Button(S.historyClear) { confirmClear = true }.disabled(songs.isEmpty)
            }
        }
        .overlay(alignment: .bottom) {
            if let song = undoSong {
                UndoBar(message: String(format: S.historyDeleted, naming.label(song.key))) {
                    model.restoreSong(song)
                    undoSong = nil
                }
                .padding(16)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: undoSong)
        .alert(S.historyClearTitle, isPresented: $confirmClear) {
            Button(S.delete, role: .destructive) { model.clearHistory() }
            Button(S.cancel, role: .cancel) {}
        } message: { Text(S.historyClearText) }
    }

    /// One tap deletes, so each delete can be taken back for a few seconds.
    private func delete(_ song: SongRecord) {
        model.deleteSong(song.atMillis)
        undoSong = song
        undoTask?.cancel()
        undoTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { undoSong = nil }
        }
        UIAccessibility.post(notification: .announcement, argument: String(format: S.historyDeleted, model.naming.label(song.key)))
    }
}

private struct SongRow: View {
    let song: SongRecord
    let naming: NoteNaming
    let onDelete: () -> Void
    @Environment(\.palette) private var p
    var body: some View {
        let time = HistoryText.time(song)
        let key = naming.label(song.key)
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(key).font(AppFont.titleLarge).foregroundStyle(p.onBackground)
                Text(HistoryText.details(song, time)).font(AppFont.bodyMedium.monospacedDigit()).foregroundStyle(p.onSurfaceVariant)
                if let played = HistoryText.playedIn(song, naming) {
                    Text(played).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
                }
            }
            Spacer()
            Button(action: onDelete) {
                Image(systemName: "xmark").font(.system(size: 15, weight: .medium)).foregroundStyle(p.onSurfaceVariant).frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(format: S.historyDeleteOne, key, time))
        }
        .padding(.vertical, 4)
    }
}

private struct UndoBar: View {
    let message: String
    let onUndo: () -> Void
    @Environment(\.palette) private var p
    var body: some View {
        HStack {
            Text(message).font(AppFont.bodyMedium).foregroundStyle(p.background)
            Spacer()
            Button(S.undo, action: onUndo).font(AppFont.labelLarge).foregroundStyle(p.primaryContainer)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 48)
        .background(RoundedRectangle(cornerRadius: 8).fill(p.onBackground))
    }
}

/// Day groups and the wording of each row, shared by the list and the shared text.
enum HistoryText {
    struct Day { let day: Date; let songs: [SongRecord] }

    static func date(_ song: SongRecord) -> Date { Date(timeIntervalSince1970: Double(song.atMillis) / 1000) }

    /// Songs grouped by calendar day, in the order given (newest first).
    static func days(_ songs: [SongRecord]) -> [Day] {
        let cal = Calendar.current
        var out: [Day] = []
        for song in songs {
            let day = cal.startOfDay(for: date(song))
            if let last = out.last, last.day == day { out[out.count - 1] = Day(day: day, songs: last.songs + [song]) }
            else { out.append(Day(day: day, songs: [song])) }
        }
        return out
    }

    static func dayLabel(_ day: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(day) { return S.historyToday }
        if cal.isDateInYesterday(day) { return S.historyYesterday }
        return day.formatted(date: .complete, time: .omitted)
    }

    static func time(_ song: SongRecord) -> String { date(song).formatted(date: .omitted, time: .shortened) }

    /// "10:42 AM, 96 BPM, 4/4, 3 min"
    static func details(_ song: SongRecord, _ time: String) -> String {
        var parts = [time]
        if let bpm = song.bpm { parts.append(String(format: song.bpmTapped ? S.historyBpmTapped : S.historyBpm, bpm)) }
        if let meter = song.meter { parts.append(meter) }
        parts.append(song.seconds >= 60 ? String(format: S.historyMinutes, (song.seconds + 30) / 60) : String(format: S.historySeconds, song.seconds))
        return parts.joined(separator: ", ")
    }

    /// "Played in A major (+2)" when the player had transposed, else nil.
    static func playedIn(_ song: SongRecord, _ naming: NoteNaming) -> String? {
        song.transpose == 0 ? nil : String(format: S.historyPlayedIn, naming.label(song.key.transposed(song.transpose)), song.transpose)
    }

    /// The whole list as plain text, for a message or a note.
    static func text(_ songs: [SongRecord], _ naming: NoteNaming) -> String {
        var s = S.historyShareTitle
        for group in days(songs) {
            s += "\n\n" + dayLabel(group.day)
            for song in group.songs {
                s += "\n" + naming.label(song.key) + ", " + details(song, time(song))
                if let played = playedIn(song, naming) { s += ". " + played }
            }
        }
        return s
    }
}

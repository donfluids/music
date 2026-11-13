import KeyCatchCore
import SwiftUI
import UIKit

/// Everything the screen shows, after Hold and transposition have been applied.
struct Shown {
    /// The key to play in (transposed), or nil.
    let key: Key?
    let keyHeld: Bool
    /// nil once the singing has shown which is home; otherwise the relative key, transposed.
    let orRelative: Key?
    /// The singing does not yet point clearly to one key: what is shown is a best guess.
    let bestGuess: Bool
    /// Where wandering singers are now, transposed; nil when they are in the shown key.
    let now: Key?
    /// The chord sounding now, transposed.
    let chord: ChordLabel?
    let transpose: Int
    let nashville: Bool
    let naming: NoteNaming
    /// Cents sharp (+) or flat (-) of the band's reference A, not of 440.
    let tuningCents: Float
    let referenceHz: Int

    func chordName(_ c: ChordLabel) -> String {
        if nashville, let key { return c.nashville(in: key) }
        return naming.chord(c, key: key)
    }
    func name(_ k: Key) -> String { naming.name(k) }
    func label(_ k: Key) -> String { naming.label(k) }

    @MainActor init(_ m: AppModel) {
        let a = m.analysis
        let base = m.heldKey ?? a.displayKey
        key = base?.transposed(m.transpose)
        keyHeld = m.heldKey != nil
        orRelative = m.heldKey == nil && a.pairDecided && !a.modeDecided ? base?.relative.transposed(m.transpose) : nil
        bestGuess = m.heldKey == nil && base != nil && !a.pairDecided
        now = a.keyNow.flatMap { m.heldKey == nil && $0 != a.displayKey ? $0.transposed(m.transpose) : nil }
        chord = a.currentChord?.transposed(m.transpose)
        transpose = m.transpose
        nashville = m.nashville
        naming = m.naming
        tuningCents = a.tuningCents - Float(1200 * log2(Double(m.referenceHz) / 440))
        referenceHz = m.referenceHz
    }
}

/// Within this many cents of the key the singers count as in tune.
let inTuneCents = 10

struct AnalyzerScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    @State private var confirmDelete = false

    private var idle: Bool { !model.listening && model.analysis.key == nil && model.analysis.displayKey == nil && model.analysis.bpm == nil }

    var body: some View {
        @Bindable var model = model
        let sh = Shown(model)
        let a = model.analysis
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if idle {
                    if model.error != nil { StatusLine() }
                    IdleIntro()
                } else {
                    StatusLine()
                    VStack(alignment: .leading, spacing: 16) {
                        KeyAndChordRow(sh: sh)
                        // Drums and strums give a pulse to read; singing alone rarely does, so for voices
                        // the row shows how far off pitch the singers are and a tempo the player taps in.
                        if model.preset.readsBeat { TempoAndTimeRow() } else { TuningAndTapRow(sh: sh) }
                    }
                    HintLines(sh: sh, showTuning: model.preset.readsBeat)
                    // Playing along: the key's chords (and transposing them), then the chords heard.
                    VStack(alignment: .leading, spacing: 16) {
                        if let key = sh.key { PlayAlong(key: key, sh: sh) }
                        if a.chordSegments.contains(where: { $0.chord != nil }) { ChordTimeline(sh: sh) }
                    }
                    DetailsFold(sh: sh)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(p.background)
        .safeAreaInset(edge: .bottom) { BottomBar(sh: sh) }
        .navigationTitle("KeyCatch")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Text(verbatim: "KeyCatch").font(AppFont.titleLarge).foregroundStyle(p.onBackground)
            }
            ToolbarItem(placement: .principal) { EmptyView() }
            ToolbarItemGroup(placement: .topBarTrailing) {
                // Before the first Listen the start screen offers the modes itself.
                if !idle { PresetMenu() }
                OverflowMenu(confirmDelete: $confirmDelete)
            }
        }
        .toolbarBackground(p.background, for: .navigationBar)
        .alert(S.deleteConfirmTitle, isPresented: $confirmDelete) {
            Button(S.delete, role: .destructive) { model.deleteClips() }
            Button(S.cancel, role: .cancel) {}
        } message: { Text(S.deleteConfirmText) }
        .alert(S.clipNoticeTitle, isPresented: $model.clipNoticePending) {
            Button(S.clipNoticeAccept) { model.acceptClipNotice() }
            Button(S.cancel, role: .cancel) { model.dismissClipNotice() }
        } message: { Text(S.clipNoticeText) }
        .sheet(isPresented: Binding(get: { model.clipAwaitingLabels != nil }, set: { if !$0 { model.skipLabels() } })) {
            LabelSheet().modifier(PaletteProvider())
        }
        .sheet(isPresented: Binding(get: { model.shareFiles != nil }, set: { if !$0 { model.shareConsumed() } })) {
            ShareSheet(items: model.shareFiles ?? [])
        }
    }
}

// MARK: - Top bar

private struct PresetMenu: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    var body: some View {
        Menu {
            ForEach(AnalysisPreset.all) { preset in
                Button { model.setPreset(preset) } label: {
                    if preset == model.preset { Label(preset.title, systemImage: "checkmark") } else { Text(preset.title) }
                    Text(preset.description)
                }
            }
        } label: {
            HStack(spacing: 2) {
                Text(model.preset.title).font(AppFont.bodyLarge)
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(p.onSurfaceVariant)
        }
        .accessibilityLabel("\(S.presetMenu): \(model.preset.title)")
    }
}

private struct OverflowMenu: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    @Binding var confirmDelete: Bool
    var body: some View {
        Menu {
            Button { model.showHistory = true } label: { Label(S.historyTitle, systemImage: "clock.arrow.circlepath") }
            Button { model.showSettings = true } label: { Label(S.settings, systemImage: "gearshape") }
            Button { model.showLimits = true } label: { Label(S.limitsTitle, systemImage: "info.circle") }
            Divider()
            if model.recording {
                Button { model.stopClip() } label: { Label(S.stopClip, systemImage: "stop.circle") }
            } else {
                Button { model.startClip() } label: { Label(S.recordClip, systemImage: "record.circle") }.disabled(!model.listening)
            }
            Button { model.shareClips() } label: {
                Label(S.shareClips + (model.clipCount > 0 ? " (\(model.clipCount))" : ""), systemImage: "square.and.arrow.up")
            }.disabled(model.clipCount == 0 || model.recording)
            Button(role: .destructive) { confirmDelete = true } label: { Label(S.deleteClips, systemImage: "trash") }
                .disabled(model.clipCount == 0 || model.recording)
        } label: {
            Image(systemName: "ellipsis").foregroundStyle(p.onSurfaceVariant).frame(width: 32, height: 32)
        }
        .accessibilityLabel(S.more)
    }
}

// MARK: - Bottom bar

private struct BottomBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    let sh: Shown
    @State private var askedPermission = false

    var body: some View {
        HStack(spacing: 12) {
            if model.listening {
                Button { model.reset() } label: { BarLabel(S.newSong) }
                    .buttonStyle(BarButtonStyle(kind: .outlined))
                Button { model.toggleHold() } label: { BarLabel(sh.keyHeld ? S.held : S.hold) }
                    .buttonStyle(BarButtonStyle(kind: sh.keyHeld ? .filled : .tonal))
                    .disabled(sh.key == nil)
                StopButton(level: model.analysis.level) { model.stop() }
            } else {
                Button {
                    MicrophonePermission.request { granted in if granted { model.start() } else { model.reportPermissionDenied() } }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "mic").font(.system(size: 20, weight: .medium))
                        Text(S.listen).font(AppFont.titleMedium)
                    }.frame(maxWidth: .infinity)
                }
                .buttonStyle(BarButtonStyle(kind: .filled))
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .background(p.background)
    }
}

private struct BarLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(AppFont.titleMedium).lineLimit(1).minimumScaleFactor(0.75).frame(maxWidth: .infinity)
    }
}

/// The large bottom buttons: rounded rectangles, not pills.
struct BarButtonStyle: ButtonStyle {
    enum Kind { case filled, tonal, outlined }
    let kind: Kind
    @Environment(\.palette) private var p
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        let (fill, text): (Color, Color) = switch kind {
        case .filled: (p.primary, p.onPrimary)
        case .tonal: (p.secondaryContainer, p.onSecondaryContainer)
        case .outlined: (.clear, p.primary)
        }
        return configuration.label
            .foregroundStyle(enabled ? text : p.onSurfaceVariant.opacity(0.5))
            .padding(.horizontal, 12)
            .frame(height: 56)
            .background(shape.fill(enabled ? fill : (kind == .outlined ? .clear : p.surfaceVariant.opacity(0.5))))
            .overlay { if kind == .outlined { shape.strokeBorder(p.outline.opacity(0.6), lineWidth: 1) } }
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(shape)
    }
}

/// Round stop button with a ring that swells with the sound coming in, so it is plain the
/// microphone is hearing something (and plain when it hears nothing).
private struct StopButton: View {
    let level: Float
    let action: () -> Void
    @Environment(\.palette) private var p
    var body: some View {
        let l = CGFloat(level.coerceIn(0, 1))
        ZStack {
            Circle()
                .stroke(p.primary.opacity(0.3 + 0.6 * l), lineWidth: 3)
                .frame(width: 50 + l * 8, height: 50 + l * 8)
            Button(action: action) {
                Image(systemName: "stop.fill").font(.system(size: 16)).foregroundStyle(p.onSecondaryContainer)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(p.secondaryContainer))
            }
            .accessibilityLabel(S.stopListening)
        }
        .frame(width: 60, height: 60)
    }
}

// MARK: - Status and start screen

private struct StatusLine: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    var body: some View {
        HStack(spacing: 8) {
            if let error = model.error {
                Text(error).font(AppFont.bodyMedium).foregroundStyle(p.tertiary).frame(maxWidth: .infinity, alignment: .leading)
            } else if model.listening {
                Circle().fill(p.primary).frame(width: 8, height: 8).scaleEffect(0.7 + 0.6 * CGFloat(model.analysis.level))
                Text(S.listening).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
                let s = Int(model.analysis.secondsHeard)
                Text(String(format: S.listeningTime, s / 60, s % 60)).font(AppFont.bodyMedium.monospacedDigit()).foregroundStyle(p.outline)
                Spacer()
            } else if model.analysis.key != nil || model.analysis.bpm != nil {
                Text(S.stoppedLastResult).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
                Spacer()
            } else {
                Spacer()
            }
            if model.recording {
                Button { model.stopClip() } label: {
                    HStack(spacing: 6) {
                        Circle().fill(p.tertiary).frame(width: 7, height: 7)
                        Text("\(S.rec) \(String(format: S.secondsShort, model.recordingSeconds))").font(AppFont.labelMedium.monospacedDigit())
                    }
                    .foregroundStyle(p.onTertiaryContainer)
                    .padding(.horizontal, 12).frame(minHeight: 40)
                    .background(RoundedRectangle(cornerRadius: 12).fill(p.tertiaryContainer))
                }
            }
        }
    }
}

/// Before the first Listen: what to do, what will show up, and which kind of music it is set for.
private struct IdleIntro: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    var body: some View {
        let beat = model.preset.readsBeat
        VStack(alignment: .leading, spacing: 12) {
            Text(beat ? S.idleTitleBeat : S.idleTitleVoices).font(AppFont.headlineSmall).foregroundStyle(p.onBackground)
                .accessibilityAddTraits(.isHeader)
            Text(beat ? S.idleStep2Beat : S.idleStep2Voices).font(AppFont.bodyLarge).foregroundStyle(p.onSurfaceVariant)
        }
        .padding(.top, 24)
        VStack(alignment: .leading, spacing: 8) {
            Text(S.idleMode).font(AppFont.labelLarge).foregroundStyle(p.onSurfaceVariant)
            Segmented(labels: AnalysisPreset.all.map(\.title), selected: AnalysisPreset.all.firstIndex(of: model.preset) ?? 0, height: 44) {
                model.setPreset(AnalysisPreset.all[$0])
            }
            Text(model.preset.description).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
        }
    }
}

/// Equal segments, the chosen one in light teal: the Android app's segmented buttons.
struct Segmented: View {
    let labels: [String]
    let selected: Int
    var height: CGFloat = 40
    let onSelect: (Int) -> Void
    @Environment(\.palette) private var p
    var body: some View {
        HStack(spacing: 0) {
            ForEach(labels.indices, id: \.self) { i in
                Button { onSelect(i) } label: {
                    Text(labels[i]).font(AppFont.bodyLarge).lineLimit(1).minimumScaleFactor(0.8)
                        .foregroundStyle(i == selected ? p.onPrimaryContainer : p.onSurface)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(i == selected ? p.primaryContainer : Color.clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(i == selected ? .isSelected : [])
                if i < labels.count - 1 { Rectangle().fill(p.outline).frame(width: 1) }
            }
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(p.outline, lineWidth: 1))
    }
}

// MARK: - The big readouts

/// A label, a large value, and a line under it only when there is something to say ("major",
/// "a guess", "hold to clear"). How sure the app is shows in words, not in a bar.
private struct HeroValue: View {
    let value: String
    let sub: String?
    let label: String
    var accent = false
    @Environment(\.palette) private var p
    var body: some View {
        let known = value != "–"
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(AppFont.labelLarge).foregroundStyle(p.onSurfaceVariant).lineLimit(1)
            // Shrinks for long names such as "Bbmaj7" so nothing runs off the column.
            Text(value)
                .font(AppFont.manrope(64, .semibold, relativeTo: .largeTitle).monospacedDigit())
                .tracking(-1)
                .foregroundStyle(!known ? p.outline : (accent ? p.primary : p.onBackground))
                .lineLimit(1).minimumScaleFactor(0.45)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            if let sub { Text(sub).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant).lineLimit(1) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(known ? value + (sub.map { ", \($0)" } ?? "") : S.unknown)")
    }
}

/// Row 1: the key to play in, and the chord sounding now.
private struct KeyAndChordRow: View {
    let sh: Shown
    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            HeroValue(value: sh.key.map { sh.name($0) } ?? "–", sub: keySub, label: S.labelKey)
            HeroValue(value: sh.chord.map { sh.chordName($0) } ?? "–", sub: nil, label: S.labelChord, accent: true)
        }
    }
    private var keySub: String? {
        guard let key = sh.key else { return nil }
        if sh.keyHeld { return String(format: S.subHeld, key.mode.label) }
        // Singers who have wandered: the big key is the centre, and this says where they are now.
        if let now = sh.now { return String(format: S.subNow, key.mode.label, sh.name(now)) }
        if sh.bestGuess { return String(format: S.subBestGuess, key.mode.label) }
        if let rel = sh.orRelative { return String(format: S.subOrRelative, key.mode.label, sh.label(rel)) }
        return key.mode.label
    }
}

/// Row 2 for a beat: tempo with beat dots (tap the number to feel it double or half), and the time signature.
private struct TempoAndTimeRow: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        let a = model.analysis
        let m = model.tempoMultiplier
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                let next: Float = m == 1 ? 2 : (m == 2 ? 0.5 : 1)
                Button { if a.bpm != nil { model.setTempoMultiplier(next) } } label: {
                    HeroValue(
                        value: a.bpm.map { String(roundToInt($0 * m)) } ?? "–",
                        sub: m == 2 ? S.subDouble : (m == 0.5 ? S.subHalf : (a.bpm != nil && !a.tempoLocked ? S.subSettling : nil)),
                        label: S.labelBpm)
                }
                .buttonStyle(.plain)
                .accessibilityHint(S.tapTempoFeel)
                if a.bpm != nil { BeatRow(multiplier: m) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HeroValue(value: a.meterLabel ?? "–", sub: a.meterCompound ? S.subInThrees : nil, label: S.labelTime)
        }
    }
}

/// One dot per beat in the bar; the current one is filled and pulses, the downbeat in coral.
private struct BeatRow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    let multiplier: Float
    var body: some View {
        let a = model.analysis
        let beatsPerBar = a.beatsPerBar > 0 && multiplier == 1 ? a.beatsPerBar : 1
        TimelineView(.animation(paused: !model.listening)) { _ in
            let period = Double(a.beatPeriodMillis) * 1e6 / Double(multiplier)
            let elapsed = period > 0 ? Double(Int(DispatchTime.now().uptimeNanoseconds) - a.lastBeatAtNanos) / period : 0
            let phase = elapsed - elapsed.rounded(.down)
            let current = beatsPerBar > 1 ? floorMod(a.lastBeatIndexInBar + Int(elapsed.rounded(.down)), beatsPerBar) : 0
            HStack(spacing: 12) {
                ForEach(0..<beatsPerBar, id: \.self) { i in
                    let active = i == current
                    let size: CGFloat = beatsPerBar > 4 ? 22 : 26
                    ZStack {
                        Circle().fill(active ? (i == 0 && beatsPerBar > 1 ? p.tertiary : p.primary) : p.surfaceVariant)
                        if beatsPerBar > 1 {
                            Text("\(i + 1)").font(AppFont.labelSmall)
                                .foregroundStyle(active ? (i == 0 ? p.onTertiary : p.onPrimary) : p.onSurfaceVariant)
                        }
                    }
                    .frame(width: size, height: size)
                    .scaleEffect(active ? CGFloat(1 + 0.3 * exp(-phase * 6)) : 1)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// Row 2 for singing: how far off pitch the singers are, and a tempo the player taps in.
private struct TuningAndTapRow: View {
    @Environment(AppModel.self) private var model
    let sh: Shown
    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            TuningCell(sh: sh)
            TapCell()
        }
    }
}

/// Cents sharp or flat of the key the singers are in now: the number, and a needle on a dial.
private struct TuningCell: View {
    @Environment(AppModel.self) private var model
    let sh: Shown
    var body: some View {
        let a = model.analysis
        let known = a.sungNotes.count >= 4 || a.tuningConfidence >= 0.2
        let cents = roundToInt(sh.tuningCents)
        let sub: String? = !known ? nil : (abs(cents) < inTuneCents ? S.subInTune : (cents > 0 ? S.subCentsSharp : S.subCentsFlat))
        VStack(alignment: .leading, spacing: 4) {
            HeroValue(value: known ? signedNumber(cents) : "–", sub: sub,
                      // The reference is named whenever it is not the usual one, so a forgotten 442 is visible.
                      label: sh.referenceHz == 440 ? S.labelPitch : String(format: S.labelPitchRef, sh.referenceHz))
            TunerDial(cents: known ? cents : nil).frame(height: 44)
        }
        .padding(.top, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// "+11", "−7" (a true minus sign) or "0".
private func signedNumber(_ c: Int) -> String { c > 0 ? "+\(c)" : (c < 0 ? "\u{2212}\(-c)" : "0") }

/// A needle swinging over a dial from 50 cents flat to 50 sharp; teal inside the in-tune stretch, coral outside it.
private struct TunerDial: View {
    let cents: Int?
    @Environment(\.palette) private var p
    var body: some View {
        Canvas { ctx, size in
            let sweep: Double = 100
            let half: CGFloat = CGFloat(sweep / 2 * Double.pi / 180)
            let r: CGFloat = (size.width / 2 - 6) / sin(half)
            // The pivot sits below the box, so only the top of the dial shows.
            let pivot = CGPoint(x: size.width / 2, y: 6 + r)
            func at(_ c: Double, _ radius: CGFloat) -> CGPoint {
                let ang = CGFloat((270 + sweep / 2 * c / 50) * Double.pi / 180)
                return CGPoint(x: pivot.x + radius * cos(ang), y: pivot.y + radius * sin(ang))
            }
            func arc(_ from: Double, _ span: Double) -> Path {
                var path = Path()
                path.addArc(center: pivot, radius: r, startAngle: .degrees(270 - span / 2 + from), endAngle: .degrees(270 + span / 2 + from), clockwise: false)
                return path
            }
            ctx.stroke(arc(0, sweep), with: .color(p.surfaceVariant), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            ctx.stroke(arc(0, sweep * Double(inTuneCents) / 50), with: .color(p.primary.opacity(0.45)), lineWidth: 5)
            for c in [-25.0, 0, 25] {
                var tick = Path()
                tick.move(to: at(c, r + 4)); tick.addLine(to: at(c, r - (c == 0 ? 9 : 6)))
                ctx.stroke(tick, with: .color(p.outline), lineWidth: 1.5)
            }
            if let cents {
                let c = Double(cents.coerceIn(-50, 50))
                var needle = Path()
                needle.move(to: at(c, r - 30)); needle.addLine(to: at(c, r + 2))
                ctx.stroke(needle, with: .color(abs(cents) < inTuneCents ? p.primary : p.tertiary), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Tap on the beat: the tempo shows from the second tap and pulses; a long press clears it.
private struct TapCell: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    var body: some View {
        let bpm = model.tapBpm
        let sub = bpm == nil && model.tapCount == 0 ? S.subTapTheBeat : (model.tapCount < 4 ? S.subKeepTapping : S.subHoldToClear)
        // Not boxed, like the tuning beside it: the big teal "Tap" says it is a button.
        VStack(alignment: .leading, spacing: 4) {
            HeroValue(value: bpm.map { String(roundToInt($0)) } ?? S.tapValue, sub: sub, label: S.labelBpm, accent: bpm == nil)
            ZStack(alignment: .leading) {
                if let bpm { PulseDot(bpm: bpm, anchorNanos: model.tapAnchorNanos) }
            }
            .frame(height: 38)
        }
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { UIImpactFeedbackGenerator(style: .light).impactOccurred(); model.tapBeat() }
        .onLongPressGesture { UINotificationFeedbackGenerator().notificationOccurred(.warning); model.clearTapTempo() }
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: Text(S.tapAction)) { model.tapBeat() }
        .accessibilityAction(named: Text(S.tapClear)) { model.clearTapTempo() }
    }
}

/// One dot that swells on each tapped beat, kept in step with the last tap.
private struct PulseDot: View {
    let bpm: Float
    let anchorNanos: Int
    @Environment(\.palette) private var p
    var body: some View {
        TimelineView(.animation) { _ in
            let period = 60e9 / Double(bpm)
            let elapsed = Double(Int(DispatchTime.now().uptimeNanoseconds) - anchorNanos) / period
            let phase = elapsed - elapsed.rounded(.down)
            Circle().fill(p.primary)
                .frame(width: 22, height: 22)
                .scaleEffect(CGFloat(1 + 0.35 * exp(-phase * 6)))
                .opacity(0.5 + 0.5 * exp(-phase * 4))
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Hints

private struct HintLines: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    let sh: Shown
    let showTuning: Bool
    var body: some View {
        let lines = hints()
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(lines, id: \.self) { Text($0).font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant) }
            }
        }
    }

    private func hints() -> [String] {
        let a = model.analysis
        var lines: [String] = []
        let other = a.displayKeyFromChords ? a.key.flatMap { $0 != a.displayKey ? $0 : nil } : a.songKey.flatMap { $0 != a.displayKey ? $0 : nil }
        if !sh.keyHeld, let other {
            let name = sh.label(other.transposed(sh.transpose))
            lines.append(String(format: a.displayKeyFromChords ? S.hintNotesSuggest : S.hintChordsSuggest, name))
        }
        if let key = sh.key, let rel = sh.orRelative, a.secondsHeard >= 4 {
            lines.append(String(format: S.hintMajorOrMinor, sh.label(key), sh.label(rel)))
        }
        if let key = sh.key, !sh.keyHeld {
            let now = (a.keyNow ?? a.displayKey)?.transposed(sh.transpose)
            let low = a.keyRangeLow?.transposed(sh.transpose)
            let high = a.keyRangeHigh?.transposed(sh.transpose)
            if a.keyMove == .settled, let n = sh.now { lines.append(String(format: S.hintSettled, sh.label(n))) }
            else if a.keyMove == .rising, let now { lines.append(String(format: S.hintRising, sh.label(now))) }
            else if a.keyMove == .falling, let now { lines.append(String(format: S.hintFalling, sh.label(now))) }
            else if let low, let high, low != high { lines.append(String(format: S.hintWandering, sh.name(low), sh.name(high), sh.label(key))) }
        }
        if sh.key != nil, sh.bestGuess, a.secondsHeard >= 4 {
            let spread = roundToInt(a.singerSpreadCents)
            lines.append(spread >= 25 ? String(format: S.hintBestGuessScattered, spread) : S.hintBestGuess)
        }
        // How far the music sits from the key it is in now. A whole semitone or more of
        // wandering shows as "now" instead.
        let cents = roundToInt(sh.tuningCents)
        if showTuning && a.tuningConfidence >= 0.2 && abs(cents) >= 25 {
            lines.append(cents > 0 ? String(format: S.hintTuningSharp, cents) : String(format: S.hintTuningFlat, -cents))
        }
        return lines
    }
}

// MARK: - Playing along

/// The chords of the key, with transposing beside their heading. Transpose stays a small text
/// button until it is used; once the key is moved the stepper and what to play stay in view.
private struct PlayAlong: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    let key: Key
    let sh: Shown
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(S.chordsInKey).font(AppFont.labelLarge).foregroundStyle(p.onSurfaceVariant)
                Spacer()
                if open || sh.transpose != 0 {
                    StepButton(systemImage: "minus", label: S.transposeDown, enabled: sh.transpose > -6) { model.setTranspose(sh.transpose - 1) }
                    Text(String(format: "%+d", sh.transpose)).font(AppFont.titleMedium.monospacedDigit()).frame(width: 32)
                    StepButton(systemImage: "plus", label: S.transposeUp, enabled: sh.transpose < 6) { model.setTranspose(sh.transpose + 1) }
                } else {
                    Button(S.transpose) { open = true }.font(AppFont.labelLarge).foregroundStyle(p.primary)
                }
            }
            .frame(minHeight: 40)
            if sh.transpose != 0 {
                Text(String(format: S.transposePlay, sh.label(key.transposed(-sh.transpose)), sh.label(key), sh.transpose))
                    .font(AppFont.bodyMedium).foregroundStyle(p.onSurfaceVariant)
            }
            ChordsInKey(key: key, sh: sh)
        }
    }
}

struct StepButton: View {
    let systemImage: String
    let label: String
    let enabled: Bool
    let action: () -> Void
    @Environment(\.palette) private var p
    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 15, weight: .medium))
                .frame(width: 40, height: 40)
                .overlay(Circle().strokeBorder(p.outline.opacity(0.6), lineWidth: 1))
        }
        .foregroundStyle(enabled ? p.primary : p.onSurfaceVariant.opacity(0.4))
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

private struct ChordsInKey: View {
    @Environment(\.palette) private var p
    let key: Key
    let sh: Shown
    var body: some View {
        let chords = key.chords
        let names = chords.enumerated().map { i, c in sh.nashville ? "\(i + 1)\(c.quality.suffix)" : sh.naming.of(c.name) }
        // One size for the whole row, set by its longest name, so the cells never differ.
        let longest = names.map(\.count).max() ?? 1
        let size: CGFloat = longest <= 2 ? 22 : (longest == 3 ? 19 : (longest == 4 ? 16 : 14))
        HStack(spacing: 6) {
            ForEach(chords.indices, id: \.self) { i in
                let chord = chords[i]
                let playing = sh.chord.map { $0.root == key.scale[i] && $0.minor == (chord.quality == .minor) } ?? false
                VStack(spacing: 2) {
                    Text(names[i]).font(AppFont.manrope(size, .semibold, relativeTo: .title3))
                        .foregroundStyle(playing ? p.onPrimaryContainer : p.onBackground)
                        .lineLimit(1).minimumScaleFactor(0.5).frame(height: 30)
                    Text(chord.numeral).font(AppFont.labelSmall).foregroundStyle(p.onSurfaceVariant)
                }
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: 10).fill(playing ? p.primaryContainer : (i == 0 ? p.surfaceVariant : .clear)))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(playing ? String(format: S.chordPlaying, names[i]) : names[i])
                .accessibilityAddTraits(playing ? .isSelected : [])
            }
        }
    }
}

private struct ChordTimeline: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    let sh: Shown
    var body: some View {
        let chords = model.analysis.chordSegments.suffix(30).compactMap { $0.chord?.transposed(sh.transpose) }
        VStack(alignment: .leading, spacing: 6) {
            Text(S.recentChords).font(AppFont.labelLarge).foregroundStyle(p.onSurfaceVariant)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 18) {
                        ForEach(Array(chords.enumerated()), id: \.offset) { i, chord in
                            Text(sh.chordName(chord)).font(AppFont.titleMedium)
                                .foregroundStyle(i == chords.count - 1 ? p.onBackground : p.onSurfaceVariant)
                                .id(i)
                        }
                    }
                }
                // Newest at the right; older chords fade out at the left edge.
                .defaultScrollAnchor(.trailing)
                .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.1)], startPoint: .leading, endPoint: .trailing))
                .onChange(of: chords.count) { _, n in if n > 0 { proxy.scrollTo(n - 1, anchor: .trailing) } }
            }
        }
    }
}

// MARK: - Details

private struct DetailsFold: View {
    @Environment(AppModel.self) private var model
    @Environment(\.palette) private var p
    let sh: Shown
    @State private var open = Screenshots.detailsOpen
    /// A long press on the heading adds the raw timings, for testing on a new phone.
    @State private var timings = false

    var body: some View {
        let a = model.analysis
        VStack(alignment: .leading, spacing: 0) {
            Rectangle().fill(p.outlineVariant).frame(height: 1)
            HStack {
                Text(S.details).font(AppFont.labelLarge).foregroundStyle(p.onSurfaceVariant)
                Spacer()
                Image(systemName: "chevron.down").foregroundStyle(p.onSurfaceVariant).rotationEffect(.degrees(open ? 180 : 0))
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { open.toggle() } }
            .onLongPressGesture { open = true; timings.toggle() }
            .accessibilityAddTraits(.isButton)
            .accessibilityValue(open ? S.detailsExpanded : S.detailsCollapsed)
            if open {
                VStack(alignment: .leading, spacing: 18) {
                    DetailRow(label: S.tuning) {
                        let cents = roundToInt(sh.tuningCents)
                        Text(a.tuningConfidence < 0.2 ? S.tuningUnknown
                             : abs(cents) < 10 ? String(format: S.tuningInTune, sh.referenceHz)
                             : cents > 0 ? String(format: S.tuningSharp, cents, sh.referenceHz)
                             : String(format: S.tuningFlat, -cents, sh.referenceHz))
                    }
                    DetailRow(label: S.whyKey) {
                        let lines = whyLines(a)
                        if lines.isEmpty { Text(S.whyNothing) }
                        ForEach(lines, id: \.self) { Text($0) }
                    }
                    DetailRow(label: S.notesHeard) { ChromaBars(chroma: a.chroma, key: sh.key?.transposed(-sh.transpose), naming: sh.naming) }
                    // The learned models' cost as a share of real time: CREPE runs once per chroma
                    // frame (about 46 ms of audio), Basic Pitch reports milliseconds per second.
                    let loads = modelLoads(a)
                    if !loads.isEmpty {
                        DetailRow(label: S.phoneLoad) {
                            let worst = loads.map(\.1).max() ?? 0
                            Text(worst < 0.35 ? S.phoneLoadLight : (worst < 0.75 ? S.phoneLoadBusy : S.phoneLoadBehind))
                            if timings {
                                ForEach(loads.indices, id: \.self) { i in
                                    Text(String(format: S.phoneLoadDev, loads[i].0, loads[i].1 * 100)).font(AppFont.bodySmall.monospacedDigit()).foregroundStyle(p.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                .padding(.bottom, 8)
                .transition(.opacity)
            }
        }
    }

    private func whyLines(_ a: AnalysisResult) -> [String] {
        var lines: [String] = []
        if let k = a.key { lines.append(String(format: S.whyNotes, sh.label(k.transposed(sh.transpose)))) }
        if let k = a.songKey { lines.append(String(format: S.whyChords, sh.label(k.transposed(sh.transpose)))) }
        var others: [Key] = []
        for k in a.keyAlternatives.map({ $0.0 }) + [a.songKeyAlternative].compactMap({ $0 }) where k != a.key && k != a.songKey && !others.contains(k) { others.append(k) }
        if !others.isEmpty { lines.append(String(format: S.whyAlso, others.prefix(2).map { sh.label($0.transposed(sh.transpose)) }.joined(separator: " or "))) }
        return lines
    }

    private func modelLoads(_ a: AnalysisResult) -> [(String, Float)] {
        var loads: [(String, Float)] = []
        if let m = a.pitchModel, m != "autocorrelation" { loads.append((m, a.pitchMillis / 46)) }
        if let m = a.notesModel { loads.append((m, a.notesMillisPerSecond / 1000)) }
        return loads
    }
}

private struct DetailRow<Content: View>: View {
    let label: String
    @ViewBuilder let content: Content
    @Environment(\.palette) private var p
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(AppFont.labelLarge).foregroundStyle(p.onSurfaceVariant)
            content.font(AppFont.bodyLarge).foregroundStyle(p.onBackground)
        }
    }
}

private struct ChromaBars: View {
    let chroma: [Float]
    let key: Key?
    let naming: NoteNaming
    @Environment(\.palette) private var p
    var body: some View {
        let inScale = Set(key?.scale ?? [])
        VStack(spacing: 4) {
            Canvas { ctx, size in
                let gap: CGFloat = 4
                let w = (size.width - gap * 11) / 12
                for (i, v) in chroma.enumerated() {
                    let h = size.height * CGFloat(v.coerceIn(0, 1))
                    let rect = CGRect(x: CGFloat(i) * (w + gap), y: size.height - h, width: w, height: h)
                    ctx.fill(Path(roundedRect: rect, cornerRadius: 3), with: .color(inScale.contains(i) ? p.primary : p.outline))
                }
            }
            .frame(height: 48)
            HStack(spacing: 0) {
                ForEach(0..<12, id: \.self) { i in
                    Text(naming.of(Notes.neutral(i))).font(AppFont.labelSmall).foregroundStyle(p.onSurfaceVariant).frame(maxWidth: .infinity)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Dialogs

private struct LabelSheet: View {
    @Environment(AppModel.self) private var model
    @State private var key = ""
    @State private var tempo = ""
    @State private var meter = ""
    @State private var notes = ""
    var body: some View {
        NavigationStack {
            Form {
                TextField(S.labelKeyField, text: $key)
                TextField(S.labelTempo, text: $tempo).keyboardType(.numbersAndPunctuation)
                TextField(S.labelMeter, text: $meter).keyboardType(.numbersAndPunctuation)
                TextField(S.labelNotes, text: $notes, axis: .vertical).onChange(of: notes) { _, v in if v.count > 500 { notes = String(v.prefix(500)) } }
            }
            .navigationTitle(S.labelTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(S.skip) { model.skipLabels() } }
                ToolbarItem(placement: .confirmationAction) { Button(S.save) { model.saveLabels(key: key, tempo: tempo, meter: meter, notes: notes) } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// The system share sheet, for the clip files.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

@preconcurrency import MekaKit
import SwiftUI

/// The voice note sheet on the Mac (voice notes into actions, slice 3), opened by holding the capture field's mic or by
/// "take a voice note" said to Talk; it starts recording as it opens. VOICE NOTE, a brass dot that breathes while
/// recording (still and dimmed when paused) beside "Recording · 0:42" (brass from 4:30, "· stops at 5:00"), a strip of
/// thin brass bars following his voice, and Pause/Resume · Done · Cancel (Space pauses, Return is Done, Esc cancels).
/// Then "Turning it into words…" over the thinking shimmer, then the words in a raised box; once the AI has read them
/// the box cross-fades to the tidy note under TIDIED BY NAVILIFE with "Mentions …" and "Your words ▾", and the line
/// says what came of it, with "See them in Needs you" when there are cards. Closing the sheet mid-note keeps it.
///
/// Motion: the sheet scale-fades (system); the steps cross-fade; "Your words" unfolds on the expand spring. Haptics:
/// light on Done and when the cards arrive, tick on Pause/Resume and Cancel. Reduced motion: the dot stays still, the
/// bars still follow the voice (they are what it hears) without easing, cross-fades.
struct VoiceNoteSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var showWords = false
    private let note = VoiceNoteController.shared

    var body: some View {
        let rules = VoiceNoteRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rules.MAC_TITLE)
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
            Group {
                switch note.step {
                case .idle, .recording: recording
                case .finishing:
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(rules.FINISHING).font(MekaType.body).foregroundStyle(palette.textSecondary)
                        SkeletonRows(count: 2, rowHeight: 18, palette: palette)
                    }
                case .needsMic:
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(rules.MAC_NEEDS_MIC).font(MekaType.body).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Button("Open Privacy & Security") {
                            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") { openURL(url) }
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    }
                case .busy: quiet(rules.MIC_BUSY)
                case .tooShort: quiet(rules.TOO_SHORT)
                case .cancelled: quiet("Cancelled · nothing was kept")
                case .done: done
                }
            }
            .transition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: note.step)
            .accessibilityElement(children: .contain)
            HStack {
                Spacer()
                if note.step == .done && note.cards > 0 {
                    Button(rules.SEE_CARDS) {
                        dismiss()
                        model.go(to: .needsYou, reduced: reduceMotion)
                    }
                }
                if note.step != .recording && note.step != .idle {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
        .onAppear { if !note.active { note.start(model) } }
        .onDisappear { note.sheetClosed() }
    }

    private var recording: some View {
        let rules = VoiceNoteRules.shared
        let warn = note.elapsedMs >= rules.WARN_MS
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                VoiceNoteDot(paused: note.paused, palette: palette)
                Text(rules.recordingLine(ms: note.elapsedMs, paused: note.paused))
                    .font(MekaType.body).monospacedDigit()
                    .foregroundStyle(warn ? palette.accent : palette.textPrimary)
                    .contentTransition(.opacity)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            VoiceNoteLevels(levels: note.levels, palette: palette)
            HStack(spacing: MekaSpace.xs) {
                Button(note.paused ? rules.RESUME : rules.PAUSE) {
                    MekaHaptics.tick()
                    if note.paused { note.resume() } else { note.pause() }
                }
                .keyboardShortcut(.space, modifiers: [])
                Button(rules.STOP) {
                    MekaHaptics.light()
                    note.stop()
                }
                .keyboardShortcut(.defaultAction)
                Button(rules.CANCEL) {
                    MekaHaptics.tick()
                    note.cancel()
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
                .accessibilityLabel("Cancel the voice note: nothing is kept")
            }
            .disabled(note.step != .recording)
        }
    }

    private var done: some View {
        let rules = VoiceNoteRules.shared
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let heard = note.heard {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if let tidy = note.tidy {
                        Text(rules.TIDY_LABEL).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                        Text(tidy).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                        if let mentions = note.mentions {
                            Text(mentions).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                        Button(showWords ? rules.HIDE_WORDS : rules.SHOW_WORDS) {
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showWords.toggle() }
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        if showWords {
                            Text(heard).font(MekaType.body).foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .textSelection(.enabled)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    } else {
                        Text(heard).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                    }
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: note.tidy)
            }
            if let why = note.noWords {
                Text(why).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if note.kept {
                Text(rules.KEPT_WHERE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if note.reading {
                Text(HandleThisRules.shared.READING_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                SkeletonRows(count: 1, rowHeight: 18, palette: palette)
            } else if let line = note.line {
                Text(line).font(MekaType.caption).foregroundStyle(note.cards > 0 ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
        }
    }

    private func quiet(_ text: String) -> some View {
        Text(text).font(MekaType.body).foregroundStyle(palette.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The brass dot: breathes (0.6 → 1, 0.9 s) while recording; still and dimmed when paused or under reduced motion.
struct VoiceNoteDot: View {
    let paused: Bool
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(paused: reduceMotion || paused)) { context in
            let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.8) / 1.8
            let pulse = (reduceMotion || paused) ? 1 : 0.6 + 0.4 * (0.5 - 0.5 * cos(t * 2 * .pi))
            Circle().fill(palette.accent)
                .frame(width: 12, height: 12)
                .scaleEffect(pulse)
                .opacity(paused ? 0.4 : 1)
        }
        .frame(width: 12, height: 12)
        .accessibilityLabel(paused ? "Paused" : "Recording")
    }
}

/// The last levels of his voice as thin brass bars, newest on the right.
struct VoiceNoteLevels: View {
    let levels: [Float]
    let palette: MekaPalette

    var body: some View {
        let n = VoiceNoteController.bars
        let shown = Array(repeating: Float(0), count: max(0, n - levels.count)) + levels.suffix(n)
        HStack(alignment: .center, spacing: 3) { // rhythm: ok (a glyph's own spacing)
            ForEach(Array(shown.enumerated()), id: \.offset) { _, l in
                RoundedRectangle(cornerRadius: 1)
                    .fill(palette.accent)
                    .frame(width: 2, height: 24 * CGFloat(0.08 + 0.92 * max(0, min(1, l))))
            }
        }
        .frame(height: 24)
        .accessibilityLabel("Your voice's level")
    }
}

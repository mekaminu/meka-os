@preconcurrency import MekaKit
import AVFoundation
import SwiftUI

/// Ask → Daily → Voice notes on the Mac (voice notes into actions, slice 3), like the Fold's VoiceNotesPane: the notes
/// this Mac keeps, newest first — when, how long, the tidy note or the words (three lines; click for all, with YOUR
/// WORDS under a tidied note), "Mentions …", ▶ Play / ■ Stop, ✦ Make cards (the words through the AI's tidy-up, when it
/// hasn't answered yet), Keep (past 30 days) or Let it go, and Delete with Undo. Recordings are decrypted in memory
/// to play and never written out; only Make cards sends the words.
///
/// Motion: the sheet scale-fades (system); the summary and the line cross-fade; notes stagger in and leave on the
/// expand spring; a note's words unfold on the expand spring (tick); Play (light haptic) cross-fades to ■ Stop; Keep
/// blends to the accent; Make cards (light) says "Reading it…" until the AI answers. Reduced motion: cross-fades.
struct VoiceNotesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var open: String?
    @State private var playing: String?
    @State private var player: AVAudioPlayer?
    @State private var watcher: Task<Void, Never>?
    @State private var making: String?
    @State private var line: String?
    @State private var undo: VoiceNote?
    private let store = MacVoiceNoteStore.shared

    var body: some View {
        let rules = VoiceNoteRules.shared
        let notes = store.notes ?? []
        let rows = model.voiceNoteRows(notes)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rules.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(store.notes == nil ? "" : LoneWordGlue.shared.kept(line: rules.summary(notes: notes, onMac: true)))
                .font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: notes.count)
                .staggeredAppear(1)
            if let line {
                HStack(spacing: MekaSpace.xs) {
                    Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    if let u = undo {
                        Button("Undo") {
                            MekaHaptics.tick()
                            store.restore(u)
                            undo = nil
                            self.line = "Put back"
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        VoiceNoteRowView(
                            row: row, palette: palette,
                            expanded: open == row.id, playing: playing == row.id,
                            canPlay: store.hasRecording(row.id), making: making == row.id,
                            onExpand: {
                                MekaHaptics.tick()
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = open == row.id ? nil : row.id }
                            },
                            onPlay: { play(row.id) },
                            onMakeCards: { makeCards(row.id) },
                            onKeep: { keep(row.id) },
                            onDelete: { delete(row.id, title: row.title) }
                        )
                        .staggeredAppear(2 + i)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
            }
            .frame(maxHeight: 460)
            Text(LoneWordGlue.shared.kept(line: rules.MAC_NOTE)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
            HStack {
                Button("New voice note") {
                    dismiss()
                    // After this sheet has gone, so the recording sheet isn't asked for while one is still up.
                    Task {
                        try? await Task.sleep(for: .milliseconds(400))
                        model.startVoiceNote()
                    }
                }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 520)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
        .onAppear { store.sweep(recording: VoiceNoteController.shared.recordingId) }
        .onDisappear { stopPlaying() }
    }

    private func stopPlaying() {
        watcher?.cancel()
        watcher = nil
        player?.stop()
        player = nil
        playing = nil
    }

    private func play(_ id: String) {
        if playing == id { MekaHaptics.tick(); stopPlaying(); return }
        stopPlaying()
        MekaHaptics.light()
        guard let data = store.playable(id), let p = try? AVAudioPlayer(data: data), p.play() else {
            line = VoiceNoteRules.shared.PLAY_FAILED
            return
        }
        player = p
        playing = id
        // Back to ▶ Play when it ends (a quarter-second look; nothing else to listen for).
        watcher = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                if Task.isCancelled { return }
                if player?.isPlaying != true { stopPlaying(); return }
            }
        }
    }

    private func makeCards(_ id: String) {
        guard making == nil, let note = store.notes?.first(where: { $0.id == id }) else { return }
        MekaHaptics.light()
        making = id
        let words = note.transcript
        let atMs = note.atMs
        Task {
            let read = await model.tidyVoiceNote(words, subject: model.voiceNoteSubject(atMs))
            making = nil
            guard let read else { line = AskRules.shared.OFFLINE_LINE; return }
            line = read.paneLine
            if read.cardCount > 0 { MekaHaptics.light() }
            if let mine = store.notes?.first(where: { $0.id == id }),
               let updated = VoiceNoteRules.shared.tidied(note: mine, cards: read.cardCount, tidy: read.tidy, people: read.people) {
                store.replace(updated)
            }
        }
    }

    private func keep(_ id: String) {
        guard let note = store.notes?.first(where: { $0.id == id }) else { return }
        MekaHaptics.tick()
        store.replace(VoiceNoteRules.shared.toggledKeep(note: note))
    }

    private func delete(_ id: String, title: String) {
        MekaHaptics.tick()
        if playing == id { stopPlaying() }
        guard let gone = store.remove(id) else { return }
        undo = gone
        line = "Deleted the note from " + (title.components(separatedBy: " · ").first ?? title)
    }
}

private struct VoiceNoteRowView: View {
    let row: VoiceNoteRow
    let palette: MekaPalette
    let expanded: Bool
    let playing: Bool
    let canPlay: Bool
    let making: Bool
    let onExpand: () -> Void
    let onPlay: () -> Void
    let onMakeCards: () -> Void
    let onKeep: () -> Void
    let onDelete: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        let rules = VoiceNoteRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onExpand) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.title).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(LoneWordGlue.shared.kept(line: row.text)).font(MekaType.body)
                        .foregroundStyle(row.heard ? palette.textPrimary : palette.textSecondary)
                        .lineLimit(expanded ? nil : 3)
                        .fixedSize(horizontal: false, vertical: true)
                    if let people = row.peopleLine {
                        Text(LoneWordGlue.shared.kept(line: people)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    if let cards = row.cardsLine {
                        Text(LoneWordGlue.shared.kept(line: cards)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("\(row.title). \(row.text)")
            .accessibilityHint(expanded ? "Folds the note" : "Shows the whole note")
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if let words = row.words {
                        Text(rules.WORDS_LABEL).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        Text(words).font(MekaType.body).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                    }
                    Text(LoneWordGlue.shared.kept(line: row.keepLine)).font(MekaType.caption).foregroundStyle(row.kept ? palette.accent : palette.textTertiary)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: MekaSpace.m) {
                if canPlay {
                    action(playing ? rules.STOP_PLAYING : rules.PLAY, accent: true,
                           spoken: (playing ? "Stop " : "Play ") + row.title, perform: onPlay)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: playing)
                }
                if row.canMakeCards {
                    action(making ? HandleThisRules.shared.READING_LINE : "✦ " + rules.MAKE_CARDS, accent: true,
                           spoken: "Make cards from the note of " + row.title, perform: onMakeCards)
                        .disabled(making)
                }
                action(row.kept ? "Kept" : rules.KEEP, accent: row.kept,
                       spoken: row.kept ? rules.UNKEEP : "Keep this note past 30 days", perform: onKeep)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.kept)
                action(rules.DELETE, accent: false, spoken: "Delete the note of " + row.title, perform: onDelete)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }

    private func action(_ label: String, accent: Bool, spoken: String, perform: @escaping () -> Void) -> some View {
        Button(label, action: perform)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.caption)
            .foregroundStyle(accent ? palette.accent : palette.textTertiary)
            .accessibilityLabel(spoken)
    }
}

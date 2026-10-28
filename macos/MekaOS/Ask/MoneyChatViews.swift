@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Money redesign, **Ask Obi** on the Mac (slice 1), like the Fold's MoneyChatUi: the bar under the tabs on every Money
/// tab, and its popover **Obi · money**: the turns (his question on the right, Obi's answer on the left with its
/// buttons), the Try questions from his own numbers, a field (Return asks) and the one foot line. Every word is the
/// core's `MoneyChat`, answered on this Mac; what its rules can't answer goes to Obi's AI through the core (slice 2), and
/// "Obi's answer · …" sits under his words. Only the question's string crosses into the core.
///
/// Motion: the popover scale-fades (system); each new turn fades and rises on the appear spring (fades only with Reduce
/// Motion); buttons press like every control.
struct MoneyAskBarView: View {
    let palette: MekaPalette
    var onMic: () -> Void = {}
    let onOpen: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button { MekaHaptics.tick(); onOpen() } label: {
                HStack(spacing: MekaSpace.xs) {
                    Circle()
                        .fill(RadialGradient(colors: [palette.accent, palette.accent.opacity(0.45)], center: .topLeading, startRadius: 0, endRadius: 22))
                        .frame(width: 20, height: 20)
                    Text(MoneyChat.shared.BAR).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                    Spacer(minLength: MekaSpace.xs)
                }
                .padding(.leading, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(MoneyChat.shared.BAR_SPOKEN)
            // Slice 3: the mockup's mic opens Obi · money with Dictation already listening.
            Button { MekaHaptics.tick(); onMic() } label: {
                Image(systemName: "mic").font(MekaType.body).foregroundStyle(palette.accent)
                    .frame(width: 32, height: 32).contentShape(Circle())
            }
            .buttonStyle(MekaPressStyle())
            .padding(.trailing, MekaSpace.xs)
            .help(MoneyChatSpoken.shared.MIC_SPOKEN)
            .accessibilityLabel(MoneyChatSpoken.shared.MIC_SPOKEN)
        }
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.background))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.l).stroke(palette.hairline, lineWidth: 1))
    }
}

struct MoneyChatPanel: View {
    let palette: MekaPalette
    let turns: [CoreModel.MoneyChatLine]
    let suggestions: [String]
    let working: Bool
    let said: CoreModel.MoneySaid?
    let onAsk: (String) -> Void
    let onAction: (CoreModel.MoneyChatAct) -> Void
    let onUndo: (String) -> Void
    let onDone: () -> Void
    /// Slice 3: opened from the bar's mic, so Dictation starts in the field once the popover is up.
    var listenOnOpen = false
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var typed = ""
    @FocusState private var fieldFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyChat.shared.HEAD).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        if turns.isEmpty { bubble(MoneyChat.shared.HELLO, mine: false) }
                        ForEach(turns) { t in
                            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                                HStack { Spacer(minLength: MekaSpace.xl); bubble(t.question, mine: true) }
                                bubble(t.text, mine: false)
                                if t.byObi {
                                    Text(MoneyChatObi.shared.BY).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                        .padding(.leading, MekaSpace.xs)
                                }
                                if !t.actions.isEmpty {
                                    HStack(spacing: MekaSpace.xs) {
                                        ForEach(t.actions) { a in
                                            Button(a.label) { MekaHaptics.tick(); onAction(a) }
                                                .buttonStyle(MekaPressStyle()).font(MekaType.metaStrong).foregroundStyle(palette.accent)
                                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                                .background(palette.surfaceRaised, in: Capsule())
                                        }
                                    }
                                }
                            }
                            .id(t.id)
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
                        }
                        if working {
                            Text(MoneyChat.shared.WORKING).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        }
                    }
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: turns)
                }
                .frame(maxHeight: 320)
                .scrollIndicators(.never)
                .onChange(of: turns) { _, now in
                    if let last = now.last { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            if !suggestions.isEmpty {
                Text(MoneyChat.shared.TRY).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xxs)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    ForEach(suggestions, id: \.self) { q in
                        Button(q) { onAsk(q) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
                            .background(palette.surfaceRaised, in: Capsule())
                    }
                }
            }
            HStack(spacing: MekaSpace.xs) {
                TextField(MoneyChat.shared.FIELD, text: $typed)
                    .textFieldStyle(.roundedBorder)
                    .focused($fieldFocused)
                    .onSubmit { send() }
                Button { dictate() } label: { Image(systemName: "mic") }
                    .help(MoneyChatSpoken.shared.MIC_SPOKEN)
                    .accessibilityLabel(MoneyChatSpoken.shared.MIC_SPOKEN)
                    .disabled(working)
                Button(MoneyChat.shared.SEND) { send() }.disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty || working)
            }
            if let s = said {
                HStack {
                    Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    if let u = s.undo {
                        Button("Undo") { onUndo(u) }.buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
            Text(MoneyChat.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(MoneyChat.shared.DONE) { onDone() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 420)
        .onAppear { if listenOnOpen { dictate() } }
    }

    /// The mic: the field takes focus and macOS Dictation listens into it (Return asks, as typed; the core tidies the
    /// spoken amounts). Dictation is the Mac's own; nothing is recorded or kept here.
    private func dictate() {
        MekaHaptics.tick()
        fieldFocused = true
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(200))
            NSApp.sendAction(Selector(("startDictation:")), to: nil, from: nil)
        }
    }

    private func send() {
        let raw = typed.trimmingCharacters(in: .whitespaces)
        guard !raw.isEmpty, !working else { return }
        let q = MoneyChatSpoken.shared.question(words: raw) ?? raw
        typed = ""
        onAsk(q)
    }

    private func bubble(_ text: String, mine: Bool) -> some View {
        Text(text).font(MekaType.body).foregroundStyle(palette.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(mine ? palette.accent.opacity(0.16) : palette.surfaceRaised))
    }
}

@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask MEKA on the Mac (build plan V1, AI layer slice 3b): the field asks MEKA in your own words (Return asks), with
/// Search Everything one click beside it (⌘F), and the field itself opening Search while asking can't work (AI off, the
/// month's budget used up, not connected). Under it, MEKA's AI and the month's spend, in the accent colour when it needs
/// a look. Asking shows the question, a thinking shimmer, then the answer's lines fading in one after another and up
/// to three cards rising under them; a card does nothing until clicked (light haptic), then leaves and the shell's
/// undo bar rises with what it did and Undo (which brings the card back). Reduced motion: cross-fades.
///
/// Talk to MEKA (V1 voice slice 3): the mic beside the field (or ⌥Space) starts a spoken conversation (`TalkController`;
/// macOS asks for the microphone and speech recognition the first time). The orb's panel unfolds under the field with
/// the expand spring: what it's doing, the live transcript and what MEKA said; answers show here as typed ones, and a
/// spoken yes folds the cards away with one undo bar. Clicking the orb while MEKA speaks interrupts it; otherwise it
/// ends. It stops when Ask leaves the screen or MEKA isn't the app in front.
struct AskMekaSection: View {
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var text = ""
    @State private var thinking: String?
    @State private var question: String?
    @State private var reply: AskReplyView?
    @State private var replyN = 0
    @State private var done: Set<Int> = []
    @FocusState private var focused: Bool
    @State private var talk = TalkController()

    private var canAsk: Bool { model.aiStatus?.canAsk ?? true }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                if canAsk {
                    HStack {
                        Image(systemName: "sparkle").foregroundStyle(palette.textTertiary)
                        TextField("Ask MEKA…", text: $text)
                            .textFieldStyle(.plain)
                            .font(MekaType.body)
                            .foregroundStyle(palette.textPrimary)
                            .focused($focused)
                            .onSubmit(ask)
                            .accessibilityLabel("Ask MEKA")
                    }
                    .padding(.horizontal, MekaSpace.l)
                    .frame(minHeight: 44)
                    .background(palette.surfaceRaised, in: Capsule())
                    // The mic: starts talking (or ends it), the orb's resting look.
                    Button(action: toggleTalk) {
                        VoiceOrbView(phase: .ended, level: 0, palette: palette, size: 44)
                    }
                    .buttonStyle(MekaPressStyle())
                    .help("Talk to MEKA (⌥Space)")
                    .accessibilityLabel(talk.active ? "Stop talking to MEKA" : "Talk to MEKA")
                    Button("Ask", action: ask)
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(sendable ? palette.onAccent : palette.textTertiary)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.s)
                        .background(sendable ? palette.accent : palette.surfaceRaised, in: Capsule())
                        .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: sendable)
                        .disabled(!sendable)
                    Button { model.showSearch = true } label: {
                        Label("Search", systemImage: "magnifyingglass").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.s)
                            .background(palette.surfaceRaised, in: Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .help("Search everything (⌘F)")
                } else {
                    Button { model.showSearch = true } label: {
                        HStack {
                            Image(systemName: "magnifyingglass").foregroundStyle(palette.textTertiary)
                            Text("Search everything").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            Spacer()
                            Text("⌘F").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        }
                        .padding(.horizontal, MekaSpace.l)
                        .frame(minHeight: 44)
                        .background(palette.surfaceRaised, in: Capsule())
                        .contentShape(Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel("Search everything")
                }
            }
            Text("MEKA's AI · " + (model.aiStatus?.line ?? "Checking…"))
                .font(MekaType.caption)
                .foregroundStyle(model.aiStatus?.lit == true ? palette.accent : palette.textTertiary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: model.aiStatus?.line)
                .padding(.leading, MekaSpace.xxs)
            if talk.active || talk.problem != nil {
                TalkPanel(talk: talk, palette: palette) { MekaHaptics.tick(); talk.tapOrb() }
                    .padding(.top, MekaSpace.s)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            conversation
                .padding(.top, MekaSpace.s)
                .padding(.bottom, MekaSpace.l)
                .accessibilityElement(children: .contain)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: talk.active || talk.problem != nil)
        .task { await model.refreshAiStatus() }
        .onChange(of: model.askUndone) { _, u in
            if let u { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { done.subtract(u.cards) } }
        }
        .onAppear {
            wireTalk()
            takeTalkRequest()
            takeTalkOnOpen()
        }
        .onChange(of: model.talkRequested) { _, asked in if asked { takeTalkRequest() } }
        .onChange(of: model.talkOnOpenRequested) { _, asked in if asked { takeTalkOnOpen() } }
        .onDisappear { talk.stop() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in talk.stop() }
    }

    private var sendable: Bool { AskRules.shared.question(text: text) != nil && thinking == nil }

    @ViewBuilder private var conversation: some View {
        if let q = thinking {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                questionLine(q)
                SkeletonRows(count: 2, rowHeight: 16, palette: palette)
            }
        } else if let reply, let q = question {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                questionLine(q)
                if let line = reply.unavailable {
                    Text(line).font(MekaType.body).foregroundStyle(palette.textSecondary).staggeredAppear(0)
                } else {
                    ForEach(Array(reply.lines.enumerated()), id: \.offset) { i, line in
                        Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .textSelection(.enabled)
                            .staggeredAppear(i)
                    }
                    ForEach(Array(reply.cards.enumerated()), id: \.offset) { j, card in
                        if !done.contains(j) {
                            AskCardRow(card: card, palette: palette) { tap(j, card) }
                                .staggeredAppear(reply.lines.count + j)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    }
                }
            }
            .id(replyN)
        } else {
            Text(canAsk
                 ? "Ask about your day, or ask MEKA to add, move or tick off a task, start a fast or set a timer. Nothing happens until you click."
                 : "Tasks, events, lists, goals and habits.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .padding(.leading, MekaSpace.xxs)
        }
    }

    private func questionLine(_ q: String) -> some View {
        Text("“\(q)”").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(3)
    }

    private func ask() {
        guard let q = AskRules.shared.question(text: text), thinking == nil else { return }
        MekaHaptics.light()
        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { thinking = q }
        Task {
            let r = await model.askMeka(q)
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) {
                question = q
                reply = r
                replyN += 1
                done = []
                thinking = nil
            }
            if r.unavailable == nil { text = "" }
        }
    }

    private func wireTalk() {
        talk.attach(model)
        talk.onAnswer = { q, out in
            let r: AskReplyView = switch onEnum(of: out) {
            case .answered(let a): AskReplyView(lines: AskRules.shared.answerLines(text: a.answer.text), cards: a.answer.cards, unavailable: nil)
            case .unavailable(let u): AskReplyView(lines: [], cards: [], unavailable: u.line)
            }
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) {
                question = q
                reply = r
                replyN += 1
                done = []
                thinking = nil
            }
        }
        talk.indicesOf = { cards in
            let shown = reply?.cards ?? []
            return cards.compactMap { c in shown.firstIndex(of: c) }
        }
        talk.onDid = { indices in
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { done.formUnion(indices) }
        }
    }

    /// ⌥Space: start talking, or end the conversation that's running.
    private func takeTalkRequest() {
        guard model.talkRequested else { return }
        model.talkRequested = false
        toggleTalk()
    }

    /// "Listen when I open MEKA": MEKA was just opened with the setting on: check the room, chime, listen for 6 s; if
    /// nothing is said, Today slides back.
    private func takeTalkOnOpen() {
        guard model.talkOnOpenRequested else { return }
        model.talkOnOpenRequested = false
        guard canAsk, !talk.active else { return }
        focused = false
        let model = model
        talk.startOnOpen { model.go(to: .today, reduced: MotionSetting.reduced) }
    }

    private func toggleTalk() {
        if talk.active {
            MekaHaptics.tick()
            talk.stop()
        } else {
            guard canAsk else { return }
            focused = false
            MekaHaptics.light()
            talk.start()
        }
    }

    private func tap(_ index: Int, _ card: AskCard) {
        talk.cardTapped(card)
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = done.insert(index) }
        Task {
            if !(await model.doAsk(card, index: index)) {
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = done.remove(index) }
            }
        }
    }
}

/// The orb (click: interrupt while MEKA speaks, else end), its line, the live transcript and MEKA's words; or why it
/// can't listen.
private struct TalkPanel: View {
    let talk: TalkController
    let palette: MekaPalette
    let onOrb: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    private var line: String { talk.problem?.macLine ?? TalkOrb.shared.label(phase: talk.phase, mac: true) }

    var body: some View {
        VStack(spacing: MekaSpace.xs) {
            Button(action: onOrb) {
                VoiceOrbView(phase: talk.phase, level: talk.level, palette: palette)
            }
            .buttonStyle(MekaPressStyle())
            // Resting after "Too noisy — click to talk": a click starts listening as the mic does.
            .disabled(!talk.active && talk.problem != .tooNoisy)
            .accessibilityLabel("MEKA, \(TalkOrb.shared.label(phase: talk.phase, mac: true))")
            Text(line)
                .font(MekaType.caption)
                .foregroundStyle(talk.problem != nil ? palette.accent : palette.textTertiary)
                .multilineTextAlignment(.center)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            if !talk.heard.isEmpty && talk.problem == nil {
                Text(talk.heard)
                    .font(MekaType.body)
                    .foregroundStyle(talk.phase == .listening ? palette.textSecondary : palette.textPrimary)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
            }
            if !talk.said.isEmpty && talk.phase == .speaking {
                Text(talk.said)
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
    }
}

/// A proposal: its line and one button; nothing happens until it is clicked.
private struct AskCardRow: View {
    let card: AskCard
    let palette: MekaPalette
    let action: () -> Void

    var body: some View {
        HStack {
            Text(card.line).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(3)
            Spacer()
            Button(card.button, action: action)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.onAccent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.accent, in: Capsule())
                .accessibilityLabel("\(card.button): \(card.line)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.s)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

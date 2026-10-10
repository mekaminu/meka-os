@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask MEKA on the Mac (build plan V1, AI layer slice 3b): one field (Fold review 2026-10-09 07:26, item 8) asks MEKA in
/// your own words (Return) and searches as you type: the best matches show under it (`AskFieldRules`; a task opens its
/// detail in Search, a list item, goal or habit opens where it lives, "See all 12 matches" opens Search, ⌘F as before);
/// the mic sits inside the field. While asking can't work (AI off, the month's budget used up, not connected) the field
/// only searches and Return opens Search. Under it, MEKA's AI and the month's spend, in the accent colour when it needs
/// a look. Asking shows the question, a thinking shimmer, then the answer's lines fading in one after another and up
/// to three cards rising under them; a card does nothing until clicked (light haptic), then leaves and the shell's
/// undo bar rises with what it did and Undo (which brings the card back). Reduced motion: cross-fades.
///
/// Talk to MEKA (V1 voice slice 3): the mic in the field (or ⌥Space) starts a spoken conversation (`TalkController`;
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
    /// The question MEKA is answering (or last answered); the matches step aside for it until the field changes.
    @State private var asked: String?
    @State private var matches: AskMatches?
    @FocusState private var focused: Bool
    @State private var talk = TalkController()

    private var canAsk: Bool { model.aiStatus?.canAsk ?? true }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // One field (Fold review 2026-10-09 07:26, item 8): Return asks, typing searches; the mic sits inside it.
            HStack(spacing: MekaSpace.xs) {
                Image(systemName: canAsk ? "sparkle" : "magnifyingglass").foregroundStyle(palette.textTertiary)
                TextField(AskFieldRules.shared.placeholder(canAsk: canAsk), text: $text)
                    .textFieldStyle(.plain)
                    .font(MekaType.body)
                    .foregroundStyle(palette.textPrimary)
                    .focused($focused)
                    .onSubmit(onReturn)
                    .accessibilityLabel(AskFieldRules.shared.fieldLabel(canAsk: canAsk))
                if canAsk {
                    // The mic: starts talking (or ends it), the orb's resting look, inside the field.
                    Button(action: toggleTalk) {
                        VoiceOrbView(phase: .ended, level: 0, palette: palette, size: 30)
                    }
                    .buttonStyle(MekaPressStyle())
                    .help("Talk to MEKA (⌥Space)")
                    .accessibilityLabel(talk.active ? "Stop talking to MEKA" : "Talk to MEKA")
                }
            }
            .padding(.leading, MekaSpace.l)
            .padding(.trailing, MekaSpace.xs)
            .frame(minHeight: 44)
            .background(palette.surfaceRaised, in: Capsule())
            // The best matches unfold under the field and glide as they narrow; "See all" opens Search (⌘F).
            if searching, let m = matches {
                AskMatchList(matches: m, palette: palette, onOpen: openMatch, onSeeAll: { openSearch(chosen: nil) })
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            Text("MEKA's AI · " + (model.aiStatus?.line ?? "Checking…"))
                .font(MekaType.caption)
                .foregroundStyle(model.aiStatus?.lit == true ? palette.accent : palette.textTertiary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: model.aiStatus?.line)
                .padding(.leading, MekaSpace.xxs)
            if talk.active || talk.problem != nil {
                TalkPanel(talk: talk, palette: palette) { MekaHaptics.tick(); talk.tapOrb() }
                    .padding(.top, MekaSpace.xs)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            conversation
                .padding(.top, MekaSpace.xs)
                .padding(.bottom, MekaSpace.l)
                .accessibilityElement(children: .contain)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: talk.active || talk.problem != nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: searching && matches != nil)
        .task(id: "\(searching)|\(text)") {
            guard searching else {
                if matches != nil { matches = nil; model.search("") }
                return
            }
            try? await Task.sleep(for: .milliseconds(Int(AskFieldRules.shared.SETTLE_MS)))
            guard !Task.isCancelled else { return }
            model.search(text)
        }
        .onChange(of: model.searchResults) { _, _ in refreshMatches() }
        // Search everything closing clears its query; the field searches its own text again.
        .onChange(of: model.showSearch) { _, open in if !open && searching { model.search(text) } }
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

    /// True while what's typed shows its matches (not the question MEKA is answering).
    private var searching: Bool { AskFieldRules.shared.showMatches(typed: text, asked: asked) }

    private func refreshMatches() {
        guard searching, let v = model.searchResults,
              let m = AskFieldRules.shared.matches(view: v, typed: text, canAsk: canAsk) else { return }
        withAnimation(MekaMotion.replan(reduced: reduceMotion)) { matches = m }
    }

    /// Return: ask MEKA when it can, else open Search everything with what was typed.
    private func onReturn() {
        switch AskFieldRules.shared.onReturn(typed: text, canAsk: canAsk) {
        case .ask: ask()
        case .search: openSearch(chosen: nil)
        default: break
        }
    }

    /// A task opens its detail beside the results in Search; a list item, goal or habit opens where it lives.
    private func openMatch(_ m: AskMatch) {
        guard m.opens else { return }
        MekaHaptics.light()
        if m.hit.target == .task { openSearch(chosen: m.hit.id) } else { model.open(m.hit, reduced: reduceMotion) }
    }

    private func openSearch(chosen: String?) {
        if chosen == nil { MekaHaptics.tick() }
        model.searchSeed = text
        model.searchChosenSeed = chosen
        model.showSearch = true
    }

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
            if !searching {
                Text(AskFieldRules.shared.idleLine(canAsk: canAsk, mac: true))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.leading, MekaSpace.xxs)
            }
        }
    }

    private func questionLine(_ q: String) -> some View {
        Text("“\(q)”").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(3)
    }

    private func ask() {
        guard let q = AskRules.shared.question(text: text), thinking == nil else { return }
        MekaHaptics.light()
        asked = q
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
            asked = q
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

/// The best matches for what's typed: the title, then "Task · Planned today 14:00"; a task, list item, goal or habit
/// opens with a click (rows lift on hover); an event is only shown; "See all 12 matches" opens Search.
private struct AskMatchList: View {
    let matches: AskMatches
    let palette: MekaPalette
    let onOpen: (AskMatch) -> Void
    let onSeeAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            ForEach(Array(matches.rows.enumerated()), id: \.element.hit.id) { i, row in
                Button { onOpen(row) } label: {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(row.hit.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                        Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
                }
                .buttonStyle(MekaPressStyle())
                .disabled(!row.opens)
                .mekaHoverLift()
                .accessibilityLabel(row.spoken)
                .staggeredAppear(i)
            }
            if let line = matches.seeAll {
                Button(line, action: onSeeAll)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    .padding(.vertical, MekaSpace.xs).padding(.leading, MekaSpace.xxs)
                    .help("Search everything (⌘F)")
            }
            if let line = matches.empty {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.leading, MekaSpace.xxs)
            }
        }
        .padding(.top, MekaSpace.xs)
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
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

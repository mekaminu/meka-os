@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask MEKA on the Mac (build plan V1, AI layer slice 3b): one field (Fold review 2026-10-09 07:26, item 8) asks MEKA in
/// your own words (Return) and searches as you type: the best matches show under it (`AskFieldRules`; a task opens its
/// detail in Search, a list item, goal or habit opens where it lives, "See all 12 matches" opens Search, ⌘F as before);
/// the mic sits inside the field. While asking can't work (AI off, the month's budget used up, not connected) the field
/// only searches and Return opens Search. Under it, MEKA's AI in the accent colour only when it needs a look (the month's
/// spend is in Ask → Settings → MEKA's AI). Asking shows the question, a thinking shimmer, then the answer's lines fading in one after another and up
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
    /// Take me there under a help answer (Ask knows how Navilife itself works): opens that sheet or place.
    var openHelp: (HelpPlace) -> Void = { _ in }
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var text = ""
    @State private var thinking: String?
    @State private var question: String?
    @State private var reply: AskReplyView?
    @State private var replyN = 0
    @State private var done: Set<Int> = []
    /// Earned trust: MEKA's own actions in this answer that were taken back (their Activity ids).
    @State private var autoUndone: Set<String> = []
    /// The question MEKA is answering (or last answered); the matches step aside for it until the field changes.
    @State private var asked: String?
    @State private var matches: AskMatches?
    @FocusState private var focused: Bool
    @State private var talk = TalkController()
    /// After the first ask on this Mac the paragraph under the field becomes a one-line hint (Fold review 17:56, item 2).
    @AppStorage("meka.ask.used") private var usedAsk = false

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
                    .help("Talk to Navilife (⌥Space)")
                    .accessibilityLabel(talk.active ? "Stop talking to Navilife" : "Talk to Navilife")
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
            // The spend lives in Settings → MEKA's AI (Fold review 2026-10-12, item 7); here only when it needs a look,
            // since it's why Return searches instead of asking.
            if let ai = model.aiStatus, ai.lit {
                Text("Navilife's AI · " + ai.line)
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
                    .padding(.leading, MekaSpace.xxs)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            if talk.active || talk.problem != nil {
                TalkPanel(talk: talk, needsYou: model.needsYouCount, palette: palette) { MekaHaptics.tick(); talk.tapOrb() }
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
            takeHelloRequest()
            takeTalkOnOpen()
            takeTalkAfterBrief()
        }
        .onChange(of: model.talkAfterBriefBack) { _, back in if back != nil { takeTalkAfterBrief() } }
        .onChange(of: model.talkRequested) { _, asked in if asked { takeTalkRequest() } }
        .onChange(of: model.helloRequested) { _, asked in if asked { takeHelloRequest() } }
        // Slice 3: "Hello Navilife" isn't listened for over a conversation.
        .onChange(of: talk.active) { _, active in MacHelloWake.shared.setTalking(active) }
        .onDisappear { MacHelloWake.shared.setTalking(false) }
        .onChange(of: model.talkOnOpenRequested) { _, asked in if asked { takeTalkOnOpen() } }
        .onDisappear { talk.stop() }
        // Money manager (16): Touch ID said yes to "Unlock Money to answer": the same question again, answered here.
        .onChange(of: model.moneyAskUnlocks) { _, _ in
            if let q = model.moneyAskAgain { ask(again: q, beyondHelp: false) }
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.screensDidSleepNotification)) { _ in model.lockMoneyAsk() }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidResignActiveNotification)) { _ in model.lockMoneyAsk() }
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
                    // Earned trust: what MEKA already did on its own, each with Undo; an undone one folds away.
                    ForEach(Array(reply.did.enumerated()), id: \.offset) { k, auto in
                        if !autoUndone.contains(auto.activityId) {
                            AskAutoRow(done: auto, palette: palette) { undoAuto(auto) }
                                .staggeredAppear(reply.lines.count + k)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    ForEach(Array(reply.cards.enumerated()), id: \.offset) { j, card in
                        if !done.contains(j) {
                            AskCardRow(card: card, palette: palette) { tap(j, card) }
                                .staggeredAppear(reply.lines.count + reply.did.count + j)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    // Ask knows how Navilife itself works: Take me there under an answer from the help.
                    if let link = reply.help {
                        Button {
                            MekaHaptics.light()
                            openHelp(link.place)
                        } label: {
                            Text(link.button + " ›").font(MekaType.itemMeta)
                                .foregroundStyle(palette.onAccent)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(palette.accent, in: Capsule())
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel(link.button)
                        .staggeredAppear(reply.lines.count + reply.did.count + reply.cards.count)
                    }
                    // Slice 2: "Still stuck? Ask Navilife" asks the same question of the AI, past the help.
                    if let label = reply.stillStuck {
                        Button {
                            MekaHaptics.tick()
                            ask(again: q)
                        } label: {
                            Text(label + " ›").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                                .padding(.vertical, MekaSpace.xs)
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel(label)
                        .staggeredAppear(reply.lines.count + reply.did.count + reply.cards.count + 1)
                    }
                    // Money manager (16): a money question while Money is locked; Touch ID, then the answer.
                    if let label = reply.unlockMoney {
                        Button {
                            model.unlockMoneyAsk(question: q)
                        } label: {
                            Text(label).font(MekaType.itemMeta)
                                .foregroundStyle(palette.onAccent)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(palette.accent, in: Capsule())
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel(label)
                        .staggeredAppear(reply.lines.count)
                    }
                    // Earned trust: "want me to just do these?" rises under the answer after the fifth yes in a row.
                    if let offer = model.askTrustOffer {
                        TrustOfferRow(offer: offer, palette: palette,
                                      onYes: { answerOffer(offer, yes: true) }, onNo: { answerOffer(offer, yes: false) })
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
            }
            .id(replyN)
        } else {
            if !searching {
                // "Hello, Navilife": the orb at rest, glowing warm while Needs you has cards; a click greets him.
                if canAsk && !talk.active && talk.problem == nil {
                    let line = HelloOrb.shared.restingLine(needsYou: Int32(model.needsYouCount), mac: true)
                    VStack(spacing: MekaSpace.xs) {
                        Button(action: sayHello) {
                            HelloOrbView(state: HelloOrb.shared.state(phase: .ended, needsYou: Int32(model.needsYouCount)),
                                         level: 0, palette: palette)
                        }
                        .buttonStyle(MekaPressStyle())
                        .help("Say hello to Navilife")
                        .accessibilityLabel("Navilife · \(line)")
                        Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, MekaSpace.m)
                    .transition(.opacity)
                }
                Text(AskFieldRules.shared.idleLine(canAsk: canAsk, mac: true, used: usedAsk))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.leading, MekaSpace.xxs)
                // Ask polish, item 5: two things to ask beside the one-line hint; a click asks it.
                let examples = AskFieldRules.shared.examples(canAsk: canAsk, used: usedAsk)
                if !examples.isEmpty {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(examples, id: \.self) { q in
                            Button { text = q; ask() } label: {
                                Text(q).font(MekaType.caption).lineLimit(1)
                                    .foregroundStyle(palette.textPrimary)
                                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                    .background(Capsule().fill(palette.surfaceRaised))
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityHint("Asks Navilife")
                        }
                    }
                }
            }
        }
    }

    private func questionLine(_ q: String) -> some View {
        Text("“\(q)”").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(3)
    }

    /// Asks what was typed; `again` asks that question of the AI past the help ("Still stuck? Ask Navilife").
    /// `beyondHelp` false asks `again` as a plain question (after "Unlock Money to answer").
    private func ask(again: String? = nil, beyondHelp: Bool? = nil) {
        guard let q = again ?? AskRules.shared.question(text: text), thinking == nil else { return }
        MekaHaptics.light()
        asked = q
        if !usedAsk { usedAsk = true }
        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { thinking = q }
        Task {
            let r = await model.askMeka(q, beyondHelp: beyondHelp ?? (again != nil))
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) {
                question = q
                reply = r
                replyN += 1
                done = []
                autoUndone = []
                model.askTrustOffer = nil
                thinking = nil
            }
            if r.unavailable == nil { text = "" }
        }
    }

    private func wireTalk() {
        talk.attach(model)
        talk.onAnswer = { q, out in
            let r: AskReplyView = switch onEnum(of: out) {
            case .answered(let a): AskReplyView(lines: AskRules.shared.answerLines(text: a.answer.text), cards: a.answer.cards, unavailable: nil, did: a.answer.did, help: a.answer.help, stillStuck: a.answer.stillStuck, unlockMoney: a.answer.unlockMoney)
            case .unavailable(let u): AskReplyView(lines: [], cards: [], unavailable: u.line)
            }
            asked = q
            if !usedAsk { usedAsk = true }
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) {
                question = q
                reply = r
                replyN += 1
                done = []
                autoUndone = []
                model.askTrustOffer = nil
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

    /// "Hello, Navilife" slice 2 (the sidebar's orb, the menu bar, ⌥Space after a while): the greeting, then
    /// listening; a conversation already running ends, as ⌥Space always did.
    private func takeHelloRequest() {
        guard model.helloRequested else { return }
        model.helloRequested = false
        if talk.active { toggleTalk() } else { sayHello() }
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

    /// The brief's Listen ran to its end (morning brief, slice 7): chime, listen for 8 s; if nothing is said, the
    /// place the brief was read from slides back.
    private func takeTalkAfterBrief() {
        guard let back = model.talkAfterBriefBack else { return }
        model.talkAfterBriefBack = nil
        guard canAsk, !talk.active else { return }
        focused = false
        let model = model
        talk.startAfterBrief { if back != .ask { model.go(to: back, reduced: MotionSetting.reduced) } }
    }

    /// "Hello, Navilife": the resting orb clicked; the greeting, then listening.
    private func sayHello() {
        guard canAsk, !talk.active else { return }
        focused = false
        MekaHaptics.light()
        talk.hello()
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

    private func undoAuto(_ auto: AskAutoDone) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = autoUndone.insert(auto.activityId) }
        Task { await model.undoAutoAsk(auto) }
    }

    private func answerOffer(_ offer: TrustOffer, yes: Bool) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { model.askTrustOffer = nil }
        Task { await model.answerTrustOffer(offer, yes: yes) }
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
    let needsYou: Int
    let palette: MekaPalette
    let onOrb: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    private var line: String { talk.problem?.macLine ?? TalkOrb.shared.label(phase: talk.phase, mac: true, talkOver: talk.talkOver) }

    var body: some View {
        VStack(spacing: MekaSpace.xs) {
            Button(action: onOrb) {
                HelloOrbView(state: HelloOrb.shared.state(phase: talk.phase, needsYou: Int32(needsYou)), level: talk.level, palette: palette)
            }
            .buttonStyle(MekaPressStyle())
            // Resting after "Too noisy — click to talk": a click starts listening as the mic does.
            .disabled(!talk.active && talk.problem != .tooNoisy)
            .accessibilityLabel("Navilife, \(TalkOrb.shared.label(phase: talk.phase, mac: true, talkOver: talk.talkOver))")
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
/// Something MEKA did on its own (earned trust): "Added “Milk” · done for you" and Undo.
private struct AskAutoRow: View {
    let done: AskAutoDone
    let palette: MekaPalette
    let undo: () -> Void

    var body: some View {
        HStack {
            Text(done.line).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(3)
            Spacer()
            Button("Undo", action: undo)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .accessibilityLabel("Undo: \(done.line)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }
}

/// Earned trust's offer: its line, then "Just do it" (accent) and "Keep asking".
private struct TrustOfferRow: View {
    let offer: TrustOffer
    let palette: MekaPalette
    let onYes: () -> Void
    let onNo: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(offer.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: MekaSpace.xs) {
                Button(offer.yes, action: onYes)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                Button(offer.no, action: onNo)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surface, in: Capsule())
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }
}

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

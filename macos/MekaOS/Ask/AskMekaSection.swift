@preconcurrency import MekaKit
import SwiftUI

/// Ask MEKA on the Mac (build plan V1, AI layer slice 3b): the field asks MEKA in your own words (Return asks), with
/// Search Everything one click beside it (⌘F), and the field itself opening Search while asking can't work (AI off, the
/// month's budget used up, not connected). Under it, MEKA's AI and the month's spend, in the accent colour when it needs
/// a look. Asking shows the question, a thinking shimmer, then the answer's lines fading in one after another and up
/// to three cards rising under them; a card does nothing until clicked (light haptic), then leaves and the shell's
/// undo bar rises with what it did and Undo (which brings the card back). Reduced motion: cross-fades.
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
            conversation
                .padding(.top, MekaSpace.s)
                .padding(.bottom, MekaSpace.l)
                .accessibilityElement(children: .contain)
        }
        .task { await model.refreshAiStatus() }
        .onChange(of: model.askUndone) { _, u in
            if let u { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = done.remove(u.card) } }
        }
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

    private func tap(_ index: Int, _ card: AskCard) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = done.insert(index) }
        Task {
            if !(await model.doAsk(card, index: index)) {
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = done.remove(index) }
            }
        }
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

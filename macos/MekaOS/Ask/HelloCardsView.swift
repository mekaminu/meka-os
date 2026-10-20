@preconcurrency import MekaKit
import SwiftUI

/// "Hello, Navilife" slice 8: one of the greeting's cards as the Mac draws it. Plain Swift values only (built by
/// `CoreModel.hello` on the main actor), so no Kotlin object crosses isolation.
struct HelloCardLine: Identifiable, Sendable, Equatable {
    let id: Int
    let eyebrow: String
    let title: String
    let detail: String?
    let warm: Bool
    /// Where a click takes him (the train board is on Today).
    let open: ShellDestination
    /// When it starts to rise, in ms after the greeting's voice began (`HelloCards.revealAt`).
    let revealMs: Int
    let label: String

    static func destination(_ open: HelloOpen) -> ShellDestination {
        switch open {
        case .needsYou: .needsYou
        case .calendar: .calendar
        default: .today // the commute's train board and due today are on Today
        }
    }
}

/// What `CoreModel.hello` hands back: the words to say and the cards that rise with them.
struct HelloSaid: Sendable {
    let words: String
    let cards: [HelloCardLine]
}

/// The greeting's cards under the Ask orb (the Mac's simpler layout: a row under the words, wrapping to a column when
/// narrow), each rising as its sentence starts (`HelloCards.rise`: up from a little below, fading and growing in);
/// reduced motion fades them in place. A click opens its screen and ends the conversation.
struct HelloCardsRow: View {
    let cards: [HelloCardLine]
    let spokeAt: Date?
    let palette: MekaPalette
    let onOpen: (HelloCardLine) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: spokeAt == nil || settled)) { context in
            let shown = cards.prefix(Int(HelloCards.shared.MAX))
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: MekaSpace.xs) { ForEach(shown) { card(for: $0, at: context.date) } }
                VStack(spacing: MekaSpace.xs) { ForEach(shown) { card(for: $0, at: context.date) } }
            }
        }
    }

    /// Every card has risen: the timeline stops drawing.
    private var settled: Bool {
        guard let spokeAt, let last = cards.map(\.revealMs).max() else { return false }
        return Date().timeIntervalSince(spokeAt) * 1000 > Double(last) + Double(HelloCards.shared.RISE_MS) + 100
    }

    private func card(for c: HelloCardLine, at now: Date) -> some View {
        let ms: Int64 = spokeAt.map { Int64(now.timeIntervalSince($0) * 1000) - Int64(c.revealMs) } ?? -1
        let rise = HelloCards.shared.rise(ms: ms, reduced: reduceMotion)
        let tint = c.warm ? palette.calendar3 : palette.accent
        return Button { onOpen(c) } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(c.eyebrow).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(tint).lineLimit(1)
                Text(c.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2)
                if let d = c.detail { Text(d).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1) }
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .frame(width: CGFloat(HelloCards.shared.CARD_DP), alignment: .leading)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).stroke(tint.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(MekaPressStyle())
        .disabled(rise.alpha < 0.5)
        .scaleEffect(CGFloat(rise.scale))
        .offset(y: CGFloat(rise.liftDp))
        .opacity(Double(rise.alpha))
        .accessibilityLabel(c.label)
    }
}

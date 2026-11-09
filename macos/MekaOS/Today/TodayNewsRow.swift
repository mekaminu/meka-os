@preconcurrency import MekaKit
import SwiftUI

/// The Today rebuild, slice 2d-2e (Meka approved 2026-11-08 22:58, mockup `docs/design/day-list/TodayNew.dc.html`; as the
/// Fold's slices 2b-2b-2b-3 and the drift of 2026-11-09 19:20): the Mac's NEWS on Today is the mockup's row of picture
/// cards below Also today (core TodayNewsRules), in place of the floating ticker at Today's foot and the News list under
/// Coming up. A small-caps NEWS label with "swipe ›", then today's match first in Barça's colour and the stories, each a
/// picture, a two-line headline and "BBC News · 49 min ago". It starts at the first card, whole; every few seconds it
/// glides on to the next card on its own (round twice when Calm, on and on when Always moving, never with Reduce
/// Motion or Off), settling on a card's start; hovering or scrolling it yourself pauses the glide a few seconds.
/// A click presses the card in with a tick and opens the story in the News sheet (the match opens its detail).
/// Headlines are untrusted (ADR-006): text only.
struct MacTodayNewsRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @AppStorage(NewsTickerChoice.key) private var modeId = "calm"
    let palette: MekaPalette

    var body: some View {
        if let place = model.newsPlace {
            let ticker = TickerRules.shared.ticker(place: place)
            let mode = NewsTickerChoice.mode(modeId)
            if TodayNewsRules.shared.shown(mode: mode, ticker: ticker) {
                let cards = TodayNewsRules.shared.cards(ticker: ticker)
                if !cards.isEmpty {
                    MacNewsCards(cards: cards, stories: Dictionary(ticker.items.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }),
                                 mode: mode, reduced: reduceMotion, palette: palette, open: open)
                        .padding(.bottom, MekaSpace.l)
                        .transition(.opacity)
                }
            }
        }
    }

    private func open(_ c: TodayNewsCard) {
        MekaHaptics.tick()
        if let m = c.match {
            model.openEvent = m.event
        } else if let id = c.storyId {
            model.newsStoryId = id
            model.showNews = true
        }
    }
}

/// The row itself: the label, then the cards in a sideways scroll that snaps to a card's start.
private struct MacNewsCards: View {
    let cards: [TodayNewsCard]
    let stories: [String: NewsItem]
    let mode: TickerMode
    let reduced: Bool
    let palette: MekaPalette
    let open: (TodayNewsCard) -> Void
    @State private var current: String?
    /// When he last hovered or scrolled the row himself (ms since 1970), so the glide waits.
    @State private var touchedAt: Int64?

    /// The mockup's 250 px card, so the next one peeks in from the edge.
    static var cardWidth: CGFloat { CGFloat(TodayNewsRules.shared.CARD_WIDTH_DP) }
    static var thumb: CGFloat { CGFloat(TodayNewsRules.shared.THUMB_DP) }

    var body: some View {
        let keys = cards.map(\.key)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                Text(TodayNewsRules.shared.LABEL).font(MekaType.cardLabel).tracking(MekaType.cardLabelTracking)
                    .foregroundStyle(palette.textSecondary).lineLimit(1)
                    .accessibilityLabel(TodayNewsRules.shared.spokenLabel(count: Int32(cards.count)))
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                let hint = TodayNewsRules.shared.hint(count: Int32(cards.count))
                if !hint.isEmpty {
                    Text(hint).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                        .accessibilityHidden(true)
                }
            }
            ScrollView(.horizontal) {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(cards, id: \.key) { c in
                        MacNewsCard(card: c, story: c.storyId.flatMap { stories[$0] }, palette: palette, open: open)
                            .id(c.key)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollIndicators(.never)
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $current, anchor: .leading)
            .onScrollPhaseChange { _, phase in
                if phase == .interacting { touchedAt = Self.nowMs() }
            }
            .onHover { if $0 { touchedAt = Self.nowMs() } }
        }
        .task(id: DriftKey(keys: keys, mode: mode, reduced: reduced)) {
            var left = Int(TodayNewsRules.shared.driftSteps(mode: mode, count: Int32(cards.count), reduced: reduced))
            while left > 0, !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(TodayNewsRules.shared.DRIFT_STEP_MS))
                if Task.isCancelled { return }
                let touched = touchedAt.map { KotlinLong(value: $0) }
                guard TodayNewsRules.shared.mayDrift(touchedAtMs: touched, nowMs: Self.nowMs()) else { continue }
                let index = current.flatMap { keys.firstIndex(of: $0) } ?? Int(TodayNewsRules.shared.START_INDEX)
                let next = Int(TodayNewsRules.shared.nextIndex(index: Int32(index), count: Int32(keys.count)))
                // The glide (the motion catalogue's replan curve), back to the first after the last.
                withAnimation(MekaMotion.replan(reduced: false)) { current = keys[next] }
                if left != Int(Int32.max) { left -= 1 }
            }
        }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private struct DriftKey: Equatable {
        let keys: [String]
        let mode: TickerMode
        let reduced: Bool
    }
}

/// One card: a 56 pt picture (the match: a tile in Barça's colour with a dot), a two-line headline and the source line.
private struct MacNewsCard: View {
    let card: TodayNewsCard
    let story: NewsItem?
    let palette: MekaPalette
    let open: (TodayNewsCard) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var hovering = false

    var body: some View {
        Button { open(card) } label: {
            HStack(spacing: MekaSpace.xs) {
                if let story {
                    NewsThumb(item: story, palette: palette)
                        .frame(width: MacNewsCards.thumb, height: MacNewsCards.thumb)
                } else {
                    ZStack {
                        RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.barca.opacity(0.16))
                        Circle().fill(palette.barca).frame(width: 10, height: 10)
                    }
                    .frame(width: MacNewsCards.thumb, height: MacNewsCards.thumb)
                }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(card.title).font(MekaType.caption)
                        .foregroundStyle(hovering ? palette.accent : palette.textPrimary)
                        .lineLimit(2).multilineTextAlignment(.leading)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: card.title)
                    HStack(spacing: MekaSpace.xxs) {
                        if card.barca { Circle().fill(palette.barca).frame(width: 5, height: 5) }
                        Text(card.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(MekaSpace.xs)
            .frame(width: MacNewsCards.cardWidth, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .onHover { hovering = $0 }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(card.spoken)
        .accessibilityHint(card.match != nil ? "Opens the match" : "Opens the story")
        .accessibilityAddTraits(.isButton)
    }
}

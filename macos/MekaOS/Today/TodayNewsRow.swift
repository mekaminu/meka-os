@preconcurrency import MekaKit
import SwiftUI

/// The Today rebuild, slice 2d-2e (Meka approved 2026-11-08 22:58, mockup `docs/design/day-list/TodayNew.dc.html`; as the
/// Fold's slices 2b-2b-2b-3 and the drift of 2026-11-09 19:20): the Mac's NEWS on Today is the mockup's row of picture
/// cards below Also today (core TodayNewsRules), in place of the floating ticker at Today's foot and the News list under
/// Coming up. A small-caps NEWS label with "swipe ›", then today's match first in Barça's colour and the stories, each a
/// picture, a two-line headline and "BBC News · 49 min ago". It starts at the first card, whole, and drifts across
/// continuously on its own (Meka 2026-11-10; Calm or Always moving, never with Reduce Motion or Off); hovering or
/// dragging it pauses it a few seconds.
/// A click presses the card in with a tick and opens the story in the News sheet (the match opens its detail).
/// Headlines are untrusted (ADR-006): text only.
struct MacTodayNewsRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @AppStorage(NewsTickerChoice.key) private var modeId = "moving"
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

/// The row itself: the label, then the cards. While it drifts (Meka 2026-11-10 07:38: "my news are not scrolling as
/// before") it is one slow constant glide, no hops, laid out twice so it loops back to the first card without a seam, for
/// as long as Today is on screen, Calm or Always moving (core TodayNewsRules.drifts). Hovering or dragging it pauses it; it
/// picks up again a few seconds after he moves away or lets go. With Reduce Motion (the app's Motion → Off) or one card
/// it is the still row you scroll yourself, snapping to a card's start.
private struct MacNewsCards: View {
    let cards: [TodayNewsCard]
    let stories: [String: NewsItem]
    let mode: TickerMode
    let reduced: Bool
    let palette: MekaPalette
    let open: (TodayNewsCard) -> Void
    @State private var offset: CGFloat = 0
    /// One set of cards' width plus the gap after it: the loop.
    @State private var loopWidth: CGFloat = 0
    @State private var hovering = false
    @State private var dragStart: CGFloat?
    /// Bumped each time he lets go (moves the pointer away or ends a drag): the drift waits a few seconds after it.
    @State private var letGo = 0

    /// The mockup's 250 px card, so the next one peeks in from the edge.
    static var cardWidth: CGFloat { CGFloat(TodayNewsRules.shared.CARD_WIDTH_DP) }
    static var thumb: CGFloat { CGFloat(TodayNewsRules.shared.THUMB_DP) }
    private static var gap: CGFloat { MekaSpace.xs }

    var body: some View {
        let drifts = TodayNewsRules.shared.drifts(mode: mode, count: Int32(cards.count), reduced: reduced)
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
            if drifts { drifting } else { still }
        }
    }

    /// The still row: scroll it yourself; it settles on a card's start.
    private var still: some View {
        ScrollView(.horizontal) {
            HStack(spacing: Self.gap) {
                ForEach(cards, id: \.key) { c in
                    MacNewsCard(card: c, story: c.storyId.flatMap { stories[$0] }, palette: palette, open: open)
                }
            }
            .scrollTargetLayout()
        }
        .scrollIndicators(.never)
        .scrollTargetBehavior(.viewAligned)
    }

    /// The drifting row: two sets side by side, slid left by `offset` (wrapped within one set by the core).
    private var drifting: some View {
        HStack(spacing: Self.gap) {
            cardSet.onGeometryChange(for: CGFloat.self) { $0.size.width } action: { loopWidth = $0 + Self.gap }
            cardSet.accessibilityHidden(true)
        }
        .fixedSize(horizontal: true, vertical: false)
        .offset(x: -offset)
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
        .contentShape(Rectangle())
        .onHover { inside in
            hovering = inside
            if !inside { letGo += 1 }
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 4)
                .onChanged { g in
                    let start = dragStart ?? offset
                    dragStart = start
                    offset = CGFloat(TickerRules.shared.scrubbed(drift: TickerDrift(offsetDp: Float(offset), loops: 0),
                                                                 offsetDp: Float(start - g.translation.width),
                                                                 loopWidthDp: Float(loopWidth)).offsetDp)
                }
                .onEnded { _ in
                    dragStart = nil
                    letGo += 1
                }
        )
        .task(id: DriftKey(held: hovering || dragStart != nil, letGo: letGo)) {
            guard !hovering, dragStart == nil else { return }
            // Just let go: a few seconds' rest first (none when Today first shows).
            if letGo > 0 {
                try? await Task.sleep(for: .milliseconds(TodayNewsRules.shared.RESUME_AFTER_MS))
            }
            let clock = ContinuousClock()
            var last = clock.now
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                if Task.isCancelled { return }
                let now = clock.now
                let d = (now - last).components
                let ms = d.seconds * 1000 + d.attoseconds / 1_000_000_000_000_000
                last = now
                offset = CGFloat(TodayNewsRules.shared.drift(offsetDp: Float(offset), dtMs: ms, loopWidthDp: Float(loopWidth)))
            }
        }
    }

    private var cardSet: some View {
        HStack(spacing: Self.gap) {
            ForEach(cards, id: \.key) { c in
                MacNewsCard(card: c, story: c.storyId.flatMap { stories[$0] }, palette: palette, open: open)
            }
        }
    }

    private struct DriftKey: Equatable {
        let held: Bool
        let letGo: Int
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

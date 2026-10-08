@preconcurrency import MekaKit
import SwiftUI

/// The Mac's ticker choice (Appearance → News ticker), kept on this Mac like the theme. Calm by default.
enum NewsTickerChoice {
    static let key = "meka.newsTicker"
    /// The three choices in their order (Calm · Always moving · Off).
    static var all: [TickerMode] { [.calm, .moving, .off] }
    static func mode(_ id: String) -> TickerMode { TickerRules.shared.mode(id: id) }
}

/// The news ticker at the foot of Today (build plan M1, news ticker slice 2): picture-and-headline cards drifting right
/// to left at a slow constant speed in a seamless loop, today's match first in Barça's colour. Hovering holds it;
/// clicking a story opens the News sheet on it, the match opens its detail. Calm (the default) drifts twice each time
/// Today appears and then rests, with ▸ to set it going again (tick haptic); "Always moving" never rests; "Off" hides it.
/// It only moves while it is on screen. Reduce Motion: no drift, one still card with ‹ › paging. Headlines are
/// untrusted (ADR-006): text only.
struct NewsTickerStrip: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @AppStorage(NewsTickerChoice.key) private var modeId = "calm"
    let palette: MekaPalette

    var body: some View {
        if let place = model.newsPlace {
            let ticker = TickerRules.shared.ticker(place: place)
            let mode = NewsTickerChoice.mode(modeId)
            if TickerRules.shared.shown(mode: mode, ticker: ticker) {
                let cards = TickerCardItem.cards(ticker)
                Group {
                    if reduceMotion {
                        StillTicker(cards: cards, palette: palette, open: open)
                    } else {
                        DriftingTicker(cards: cards, mode: mode, palette: palette, open: open)
                    }
                }
                .frame(height: 56)
                .transition(.opacity)
            }
        }
    }

    private func open(_ c: TickerCardItem) {
        MekaHaptics.tick()
        switch c {
        case .match(let m): model.openEvent = m.event
        case .story(let s):
            model.newsStoryId = s.id
            model.showNews = true
        }
    }
}

/// A card on the strip: today's match or a story.
enum TickerCardItem: Identifiable {
    case match(NewsMatchday)
    case story(NewsItem)

    var id: String {
        switch self {
        case .match(let m): "match:" + m.eventId
        case .story(let s): s.id
        }
    }

    var spoken: String {
        switch self {
        case .match(let m): m.line
        case .story(let s): TickerRules.shared.spoken(item: s)
        }
    }

    static func cards(_ t: NewsTicker) -> [TickerCardItem] {
        var out: [TickerCardItem] = []
        if let m = t.matchday { out.append(.match(m)) }
        out.append(contentsOf: t.items.map { .story($0) })
        return out
    }
}

struct DriftingTicker: View {
    let cards: [TickerCardItem]
    let mode: TickerMode
    let palette: MekaPalette
    let open: (TickerCardItem) -> Void
    @State private var drift = TickerDrift(offsetDp: 0, loops: 0)
    /// One set of cards' width plus the gap after it: the loop. Two sets are laid out so it wraps without a seam.
    @State private var loopWidth: CGFloat = 0
    @State private var viewport: CGFloat = 0
    @State private var hovering = false
    private static let gap: CGFloat = 12

    var body: some View {
        // A set narrower than the strip can't loop without a gap: it stands still.
        let fits = loopWidth > 0 && loopWidth >= viewport
        // Words fade over 48 pt at each end rather than being cut mid-word (TickerRules.EDGE_FADE_DP).
        let edge = CGFloat(TickerRules.shared.edgeFadeFraction(widthDp: Float(viewport)))
        let moving = TickerRules.shared.moving(mode: mode, reducedMotion: false, onScreen: true, held: hovering,
                                               loopsDone: drift.loops, hasItems: fits)
        ZStack(alignment: .trailing) {
            HStack(spacing: Self.gap) {
                HStack(spacing: Self.gap) {
                    ForEach(cards) { c in TickerCardView(card: c, palette: palette, open: open) }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { loopWidth = $0 + Self.gap }
                HStack(spacing: Self.gap) {
                    ForEach(cards) { c in TickerCardView(card: c, palette: palette, open: open) }
                }
                .accessibilityHidden(true)
            }
            .fixedSize(horizontal: true, vertical: false)
            .offset(x: fits ? -CGFloat(drift.offsetDp) : 0)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
            .mask(
                LinearGradient(stops: [
                    .init(color: .clear, location: 0), .init(color: .black, location: edge),
                    .init(color: .black, location: 1 - edge), .init(color: .clear, location: 1),
                ], startPoint: .leading, endPoint: .trailing)
            )
            if fits && TickerRules.shared.offersPlay(mode: mode, reducedMotion: false, loopsDone: drift.loops, hasItems: true) {
                Button {
                    MekaHaptics.tick()
                    drift = TickerDrift(offsetDp: drift.offsetDp, loops: 0)
                } label: {
                    Image(systemName: "play.fill").font(.caption).foregroundStyle(palette.accent)
                        .frame(width: 28, height: 28)
                        .background(palette.surfaceRaised, in: Circle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Play the news ticker")
                .transition(.opacity)
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { viewport = $0 }
        .onHover { hovering = $0 }
        .animation(MekaMotion.appear(reduced: false), value: drift.loops >= TickerRules.shared.CALM_LOOPS)
        .task(id: moving) {
            guard moving else { return }
            let clock = ContinuousClock()
            var last = clock.now
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                let now = clock.now
                let d = (now - last).components
                let ms = d.seconds * 1000 + d.attoseconds / 1_000_000_000_000_000
                last = now
                drift = TickerRules.shared.step(drift: drift, dtMs: ms, loopWidthDp: Float(loopWidth))
            }
        }
    }
}

/// Reduce Motion: one card at a time, ‹ 2 of 12 › to page (wrapping), cross-fading between cards.
struct StillTicker: View {
    let cards: [TickerCardItem]
    let palette: MekaPalette
    let open: (TickerCardItem) -> Void
    @State private var index = 0

    var body: some View {
        let i = min(max(index, 0), max(cards.count - 1, 0))
        HStack(spacing: MekaSpace.s) {
            if !cards.isEmpty {
                TickerCardView(card: cards[i], palette: palette, open: open)
                    .id(cards[i].id)
                    .transition(.opacity)
            }
            Spacer(minLength: 0)
            if cards.count > 1 {
                Button("‹") { step(-1) }.accessibilityLabel("Previous headline")
                Text(TickerRules.shared.pageLabel(index: Int32(i), count: Int32(cards.count)))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Button("›") { step(1) }.accessibilityLabel("Next headline")
            }
        }
        .buttonStyle(MekaPressStyle())
        .foregroundStyle(palette.accent)
        .animation(MekaMotion.appear(reduced: true), value: i)
    }

    private func step(_ delta: Int32) {
        MekaHaptics.tick()
        index = Int(TickerRules.shared.page(index: Int32(index), delta: delta, count: Int32(cards.count)))
    }
}

/// One card: a story's picture, title (regular weight: news is context) and "Sport · 2 h ago"; or the match line.
private struct TickerCardView: View {
    let card: TickerCardItem
    let palette: MekaPalette
    let open: (TickerCardItem) -> Void
    @State private var hovering = false

    var body: some View {
        HStack(spacing: MekaSpace.s) {
            switch card {
            case .match(let m):
                Circle().fill(palette.barca).frame(width: 8, height: 8)
                Text(m.line).font(MekaType.itemMeta).foregroundStyle(palette.barca).lineLimit(2)
                    .animation(MekaMotion.appear(reduced: false), value: m.line)
            case .story(let s):
                NewsThumb(item: s, palette: palette).frame(width: 56, height: 42)
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.title).font(MekaType.caption)
                        .foregroundStyle(hovering ? palette.accent : palette.textPrimary)
                        .lineLimit(2)
                    HStack(spacing: 4) {
                        if s.topic == "barca" { Circle().fill(palette.barca).frame(width: 5, height: 5) }
                        Text(s.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                .frame(width: 200, alignment: .leading)
            }
        }
        .padding(.horizontal, MekaSpace.s)
        .frame(height: 48)
        .frame(maxWidth: 300)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        .onHover { hovering = $0 }
        .onTapGesture { open(card) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(card.spoken)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { open(card) }
    }
}

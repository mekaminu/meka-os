@preconcurrency import MekaKit
import SwiftUI

/// The Today rebuild, slice 2d-2a (Meka approved 2026-11-08 22:58, mockup `docs/design/day-list/TodayNew.dc.html`; as
/// the Fold's slices 2b-1 and 2b-2b-2b-2): the Mac's compact header. The date over the compact greeting on the left;
/// the weather chip, ⋯ (Search, Plan my day) and a small clock on the right; then TASKS · NEXT · MAIL across the page
/// (core TodayHeaderRules). Quiet surfaces: gold stays for what you act on.

/// TASKS · NEXT · MAIL under the greeting. Each tile presses in (MekaPressStyle) with a tick and opens its place; a
/// figure that changes cross-fades. VoiceOver hears each tile whole ("1 of 3 of today's tasks done.").
struct MacHeaderTilesRow: View {
    let tiles: [HeaderTile]
    let palette: MekaPalette
    let onOpen: (HeaderTileKind) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            ForEach(tiles, id: \.key) { tile in
                Button {
                    MekaHaptics.tick()
                    onOpen(tile.kind)
                } label: {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(tile.key).font(MekaType.cardLabel).tracking(MekaType.cardLabelTracking)
                            .foregroundStyle(palette.textSecondary).lineLimit(1)
                        Text(tile.value).font(MekaType.metaStrong).monospacedDigit()
                            .foregroundStyle(palette.textPrimary).lineLimit(1)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: tile.value)
                        Text(tile.line).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(MekaSpace.xs)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
                    .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(tile.spoken)
                .accessibilityHint(Self.hint(tile.kind))
                .accessibilityAddTraits(.isButton)
            }
        }
        .frame(maxWidth: .infinity)
    }

    static func hint(_ kind: HeaderTileKind) -> String {
        switch kind {
        case .tasks: return "Opens today's list"
        case .next: return "Opens it"
        default: return "Opens mail"
        }
    }
}

/// "14° light rain": the weather as a small pill; cross-fades as it changes; VoiceOver hears the whole line.
struct MacWeatherChip: View {
    let text: String
    let spoken: String
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Text(text).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
            .padding(.horizontal, MekaSpace.xs)
            .padding(.vertical, MekaSpace.xxs)
            .background(Capsule().fill(palette.surface))
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: text)
            .accessibilityLabel(spoken)
    }
}

/// ⋯ in the header: Search and Plan my day (they left the row of links under the greeting). Their shortcuts are the
/// app's menus' (⌘K, ⌘F and the like), so nothing is lost with the menu shut.
struct MacHeaderMoreMenu: View {
    let palette: MekaPalette
    let openSearch: () -> Void
    let openPlan: () -> Void

    var body: some View {
        Menu {
            Button("Search", action: openSearch)
            Button("Plan my day", action: openPlan)
        } label: {
            Image(systemName: "ellipsis")
                .foregroundStyle(palette.textSecondary)
                .frame(width: 28, height: 28)
                .background(Circle().fill(palette.surface))
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .accessibilityLabel("More")
        .help("Search, Plan my day")
    }
}

/// The header's small clock (core WatchFaceRules.CLOCK_DP, 40 pt): the watch face drawn at its usual size and shown
/// scaled down, as the collapsing header's compact clock was, so its rim and hands keep their weight. It carries no habit
/// dots or goal arcs (WatchFaceRules.carriesDots): the habits are the chips under the tiles. A click opens the Day ring.
struct MacSmallClock: View {
    let face: WatchFace
    let play: DayRingPlayback
    let played: () -> Void
    let palette: MekaPalette
    let night: Bool
    let onOpen: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        let full = CGFloat(DayRingHeader.shared.WIDE_DP)
        let small = CGFloat(WatchFaceRules.shared.CLOCK_DP)
        WatchFaceView(face: face, play: play, played: played, palette: palette, size: full, onOpen: onOpen, night: night)
            .scaleEffect(small / full)
            .frame(width: small, height: small)
            .opacity(Double(WatchFaceRules.shared.nightAlpha(night: night)))
            .animation(reduceMotion ? MekaMotion.appear(reduced: true) : .easeInOut(duration: MekaChoreography.nightFall),
                       value: night)
            .help("Open the whole day")
    }
}

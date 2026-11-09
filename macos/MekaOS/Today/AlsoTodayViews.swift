@preconcurrency import MekaKit
import SwiftUI

/// The Today rebuild, slice 2d-2b (Meka approved 2026-11-08 22:58, mockup `docs/design/day-list/TodayNew.dc.html`; as the
/// Fold's slices 2b-2 and 2b-2b): the Mac's ALSO TODAY, one sideways-scrolling row of small tiles below the schedule, in
/// place of the header's one-line notes (the trains, the school, a clash with work, the week's goals, dinner, For the train,
/// the week ahead, bins, gifts, study, a trip, parcels, trading, replies, meeting prep, the study check-in, the week's one
/// thing, a day stacking up, date night, life admin) and a renewal named with its cancel-by day (core AlsoTodayRules).
/// The dot is gold only for something to do today; a tile with somewhere to go presses in and opens it
/// (AlsoTodayRules.place); prep, the check-in and a day stacking up unfold their detail under the row, a second click
/// folds it (expand, motion catalogue; a fade with Reduce Motion).
struct MacAlsoTodayRow<Detail: View>: View {
    let tiles: [AlsoTile]
    let palette: MekaPalette
    /// The kind unfolded under the row, or nil.
    let unfolded: AlsoKind?
    let onOpen: (AlsoTile) -> Void
    @ViewBuilder let detail: (AlsoKind) -> Detail
    @Environment(\.mekaReduceMotion) private var reduceMotion

    /// The mockup's 150 px tile, so the next one peeks in from the edge.
    static var tileWidth: CGFloat { 150 }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(AlsoTodayRules.shared.LABEL).font(MekaType.cardLabel).tracking(MekaType.cardLabelTracking)
                .foregroundStyle(palette.textSecondary).lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: MekaSpace.xs) {
                    ForEach(tiles, id: \.kind) { tile in tileView(tile) }
                }
            }
            .scrollIndicators(.never)
            if let kind = unfolded, tiles.contains(where: { $0.kind == kind }) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) { detail(kind) }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(MekaSpace.xs)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
                    .transition(reduceMotion ? AnyTransition.opacity
                                : AnyTransition.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: unfolded)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: tiles.map(\.kind))
    }

    @ViewBuilder
    private func tileView(_ tile: AlsoTile) -> some View {
        let place = AlsoTodayRules.shared.place(tile: tile)
        let tappable = place != AlsoPlace.info
        let open = unfolded == tile.kind
        let face = VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.xxs) {
                Circle().fill(Self.dot(tile, palette)).frame(width: 7, height: 7)
                Text(tile.key).font(MekaType.cardLabel).tracking(MekaType.cardLabelTracking)
                    .foregroundStyle(palette.textSecondary).lineLimit(1)
            }
            Text(tile.value).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).lineLimit(2)
                .multilineTextAlignment(.leading)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: tile.value)
            if !tile.line.isEmpty {
                Text(tile.line).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
        }
        .frame(width: Self.tileWidth, alignment: .topLeading)
        .padding(MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).stroke(open ? palette.hairline : .clear, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))

        if tappable {
            Button {
                MekaHaptics.tick()
                onOpen(tile)
            } label: { face }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(tile.spoken)
            .accessibilityHint(AlsoTodayRules.shared.hint(tile: tile))
            .accessibilityValue(place == AlsoPlace.unfold ? (open ? "Open" : "Closed") : "")
            .accessibilityAddTraits(.isButton)
        } else {
            face
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(tile.spoken)
        }
    }

    /// Gold only for something to do today; otherwise the kind's calendar colour, as on the Fold.
    static func dot(_ tile: AlsoTile, _ palette: MekaPalette) -> Color {
        if tile.lit { return palette.accent }
        switch tile.kind {
        case .study, .studyWeek, .reflection, .review, .goals, .prep: return palette.calendar1
        case .renewal, .parcels, .clash, .pressure: return palette.calendar3
        case .weekAhead, .trip, .lifeAdmin, .trains, .trainQueue: return palette.calendar2
        case .bins, .dinner, .school: return palette.calendar4
        default: return palette.calendar5
        }
    }
}

/// The prep tile unfolded: the headline, who's coming, then each guest's last email and what's open.
struct MacPrepDetail: View {
    let prep: MeetingPrep
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(prep.headline).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).lineLimit(2)
            Text(prep.whoLine).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(2)
            ForEach(Array(prep.lines.enumerated()), id: \.offset) { _, line in
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(2)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(prep.spoken)
    }
}

/// The stacking-up tile unfolded: the day's line, then its one-tap fixes in gold (a Board move, Undo on the bar).
struct MacPressureDetail: View {
    let pressure: DayPressure
    let palette: MekaPalette
    let onFix: (PressureFix) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(pressure.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(3)
                .accessibilityLabel(pressure.spoken)
            ForEach(pressure.fixes, id: \.label) { fix in
                Button { onFix(fix) } label: {
                    Text(fix.label).font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(1)
                }
                .buttonStyle(MekaPressStyle())
            }
        }
    }
}

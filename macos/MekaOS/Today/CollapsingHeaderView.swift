@preconcurrency import MekaKit
import SwiftUI

/// Today's collapsing header on the Mac (watch face item, part 2; core HeaderCollapseRules). The watch face sits pinned
/// over the header's right-hand slot (the header keeps an empty slot its size) and shrinks with the scroll, about its
/// top-right corner, into a small clock, rising from the header's top to the slim bar's; over the last part of the
/// glide the bar fades in behind it with the greeting and one line ("Tue 13 Oct · 14°"). Clicking the bar scrolls back
/// to the top; clicking the clock opens the whole day. One face throughout, so it keeps ticking and VoiceOver reads
/// the time once. Reduce Motion: `shown` is 0 or 1, no glide.
struct CollapsingHeaderView: View {
    let shown: Float
    let face: WatchFace
    let greeting: String
    let line: String
    let palette: MekaPalette
    let play: DayRingPlayback
    let played: () -> Void
    let onOpen: () -> Void
    var dots: [HabitDot] = []
    var onTick: ((HabitDot) -> Void)? = nil
    var night: Bool = false
    var week: WeekGoals? = nil
    let onTop: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    /// The bar's padding above and below the compact clock, and its height around it.
    static let barPad: CGFloat = MekaSpace.xs
    static var barHeight: CGFloat { MekaSpace.xs * 2 + CGFloat(HeaderCollapseRules.shared.COMPACT_DP) }

    var body: some View {
        let rules = HeaderCollapseRules.shared
        let full = DayRingHeader.shared.WIDE_DP
        let bar = Double(rules.barAlpha(shown: shown))
        let scale = CGFloat(rules.faceScale(shown: shown, fullDp: full))
        let top = CGFloat(rules.faceTopDp(shown: shown, restDp: Float(MekaSpace.xl), compactDp: Float(Self.barPad)))
        ZStack(alignment: .topTrailing) {
            if rules.barShown(shown: shown) {
                Button(action: onTop) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(greeting).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).lineLimit(1)
                        if !line.isEmpty {
                            Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: Self.barHeight - Self.barPad * 2, alignment: .leading)
                    .padding(.vertical, Self.barPad)
                    .padding(.leading, MekaSpace.gutterWide)
                    .padding(.trailing, MekaSpace.gutterWide + CGFloat(rules.COMPACT_DP) + MekaSpace.m)
                    .background(palette.background)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(greeting), \(line)")
                .accessibilityHint("Back to the top of Today")
                .opacity(bar)
            }
            WatchFaceView(face: face, play: play, played: played, palette: palette,
                          size: CGFloat(full), onOpen: onOpen, dots: dots,
                          onTick: rules.dotsTappable(shown: shown) ? onTick : nil,
                          night: night, week: week)
                .scaleEffect(scale, anchor: .topTrailing)
                .opacity(Double(WatchFaceRules.shared.nightAlpha(night: night)))
                .animation(reduceMotion ? MekaMotion.appear(reduced: true) : .easeInOut(duration: MekaChoreography.nightFall),
                           value: night)
                .padding(.top, top)
                .padding(.trailing, MekaSpace.gutterWide)
        }
        .frame(maxWidth: .infinity, alignment: .topTrailing)
    }
}

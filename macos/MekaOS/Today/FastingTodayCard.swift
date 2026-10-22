@preconcurrency import MekaKit
import SwiftUI

/// Today's live Fasting card on the Mac (Fasting rebuild slice 4, the approved design
/// docs/design/fasting/today-card.dc.html, like the Fold's FastingTodayCardView): a small gold ring with the time in,
/// "FASTING" over the stage the fast is in, "Ends in 2:30 · 12:00" in gold and an End pill. Shown only while a fast
/// runs. A click on the card opens the Fasting sheet; End ends the fast with "Fast ended · Undo" on the bar. Every word
/// comes from core FastingScreenRules (read on the main actor). The ring sweeps with the clock (a one-second linear
/// glide); Reduce Motion: it steps each tick.
struct FastingTodayCardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var hovering = false

    var body: some View {
        if model.fasting?.current != nil {
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                if let card = model.fastingScreen(atMs: Int64(ctx.date.timeIntervalSince1970 * 1000))?.todayCard {
                    content(card).padding(.top, MekaSpace.m)
                }
            }
        }
    }

    private func content(_ card: MekaKit.FastingTodayCard) -> some View {
        HStack(spacing: MekaSpace.m) {
            Button { model.showFasting = true } label: {
                HStack(spacing: MekaSpace.m) {
                    ZStack {
                        Circle().stroke(palette.surfaceRaised, lineWidth: 7)
                        Circle().trim(from: 0, to: CGFloat(card.fraction))
                            .stroke(palette.accent, style: StrokeStyle(lineWidth: 7, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .animation(reduceMotion ? nil : .linear(duration: 1), value: card.fraction)
                        Text(card.time).font(MekaType.metaStrong).monospacedDigit().foregroundStyle(palette.textPrimary)
                    }
                    .frame(width: 76, height: 76)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(card.label.uppercased()).font(MekaType.sectionLabel).foregroundStyle(palette.textSecondary).lineLimit(1)
                        Text(card.stage).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(2)
                        Text(card.line).font(MekaType.caption).monospacedDigit().foregroundStyle(palette.accent)
                    }
                    Spacer(minLength: MekaSpace.xs)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(card.spoken)
            .accessibilityIdentifier("fasting-today")
            Button { model.endFastFromToday(card.fastId) } label: {
                Text(card.endLabel).font(MekaType.captionStrong).foregroundStyle(palette.accent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .overlay(Capsule().stroke(palette.accent, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("End fast")
            .accessibilityIdentifier("fasting-today-end")
        }
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(hovering ? palette.surfaceRaised : palette.surface))
        .onHover { h in withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h } }
    }
}

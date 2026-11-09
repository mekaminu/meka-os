@preconcurrency import MekaKit
import SwiftUI

/// The Today rebuild, slice 2d-2c (Meka approved 2026-11-08 22:58, mockup `docs/design/day-list/TodayNew.dc.html`; as the
/// Fold's slice 2a): "THIS EVENING · 1 OF 3", one card right under Now from 17:00 with the evening's steps (Review your
/// week while it's due, Shut down the day, Plan tomorrow; core EveningCardRules), in place of the separate review,
/// shutdown and Tomorrow cards. Each step is a row that opens its own place; a done step's number turns into a gold tick
/// (a colour fade, the catalogue's state change; the same fade with Reduce Motion, never movement). Later puts the card
/// away until 21:00.
struct MacEveningCard: View {
    let card: EveningCard
    let palette: MekaPalette
    let onStep: (EveningStepKind) -> Void
    /// Nil once 21:00 is under a quarter of an hour away (EveningCardRules.laterOffered).
    let onLater: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(alignment: .firstTextBaseline) {
                SectionLabel(card.label, palette)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: MekaSpace.xs)
                if let later = onLater {
                    Button(EveningCardRules.shared.LATER) { later() }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textSecondary)
                        .help("Put the evening card away until 21:00")
                        .accessibilityLabel("Later, until 21:00")
                }
            }
            ForEach(Array(card.steps.enumerated()), id: \.element.kind) { i, step in
                MacEveningStepRow(index: i, step: step, palette: palette) { onStep(step.kind) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, MekaSpace.l)
        .padding(.vertical, MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("evening-card")
    }
}

private struct MacEveningStepRow: View {
    let index: Int
    let step: EveningStep
    let palette: MekaPalette
    let onOpen: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            MekaHaptics.tick()
            onOpen()
        } label: {
            HStack(spacing: MekaSpace.m) {
                ZStack {
                    Circle().fill(step.done ? palette.accent : palette.surfaceRaised)
                    if !step.done { Circle().stroke(palette.textTertiary, lineWidth: 1.5) }
                    Text(step.done ? "✓" : "\(index + 1)")
                        .font(MekaType.caption)
                        .foregroundStyle(step.done ? palette.onAccent : palette.textSecondary)
                        .contentTransition(.opacity)
                }
                .frame(width: 24, height: 24)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: step.done)
                VStack(alignment: .leading, spacing: 0) {
                    Text(step.title).font(MekaType.body)
                        .foregroundStyle(step.done ? palette.textSecondary : palette.textPrimary).lineLimit(1)
                    Text(step.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .padding(.vertical, MekaSpace.xxs)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(step.title + (step.done ? ", done. " : ". ") + step.line)
        .accessibilityAddTraits(.isButton)
    }
}

/// The thin gold done bar under Today's head (3 pt, the Day list's own), as the Fold's TodayDoneBar.
struct MacTodayDoneBar: View {
    let progress: Float
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        let p = CGFloat(min(max(progress, 0), 1))
        GeometryReader { g in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2).fill(palette.hairline)
                if p > 0 { RoundedRectangle(cornerRadius: 2).fill(palette.accent).frame(width: g.size.width * p) }
            }
        }
        .frame(height: 3)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: progress)
        .accessibilityHidden(true)
    }
}

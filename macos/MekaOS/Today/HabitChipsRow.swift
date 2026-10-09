import SwiftUI
@preconcurrency import MekaKit

/// Today's habits as compact chips under the header (Fold review 2026-10-09 07:26, item 3), the Mac twin of the
/// Fold's `HabitChipsRow`, in place of the "0 of 1 habits today" tile: each chip is a ring to tick (it sweeps, fills
/// and draws its check, light haptic) beside the habit's name, which dims once done (`HabitChipRules`). The chips
/// stagger in with Today's header and glide as habits come and go; the row scrolls sideways when they don't fit.
/// Nothing shows with no habits today. Reduced motion: cross-fades, the check shown at once.
struct HabitChipsRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let chips: [HabitChip]
    let palette: MekaPalette
    var play: Bool = true

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(chips.enumerated()), id: \.element.id) { i, chip in
                    chipView(chip).staggeredAppear(i + 1, play: play)
                }
            }
        }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: chips.map(\.id))
    }

    private func chipView(_ chip: HabitChip) -> some View {
        Button { model.setHabitDone(chip.id, !chip.done) } label: {
            HStack(spacing: MekaSpace.xs) {
                TickRingView(done: chip.done, palette: palette).frame(width: 18, height: 18)
                Text(chip.title).font(MekaType.body).lineLimit(1)
                    .foregroundStyle(chip.done ? palette.textSecondary : (chip.behind ? palette.accent : palette.textPrimary))
                // An N-a-week habit's goes this week ("1/2"); a daily one has none.
                if let count = chip.count {
                    Text(count).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
            }
            .padding(.leading, MekaSpace.xs)
            .padding(.trailing, MekaSpace.s)
            .padding(.vertical, MekaSpace.xxs)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(chip.tickLabel)
        .accessibilityAddTraits(chip.done ? [.isSelected] : [])
    }
}

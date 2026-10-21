import SwiftUI
@preconcurrency import MekaKit

/// Roll-over on the Mac (Week board and roll-over, slice 1; core `RollOverRules`): open tasks planned for an earlier
/// day lead Today under "From yesterday", oldest first, each saying how long it has carried ("Carried 2 days", in the
/// accent). From three carries a one-time prompt sits under the row — Break it down · Someday · Drop · Keep — and folds
/// away once answered (Reduce Motion: fades). Someday and Drop put Undo on the bar.
struct FromYesterdayView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let carried: [CarriedTask]
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionLabel(RollOverRules.shared.LABEL, palette)
            ForEach(carried, id: \.task.id) { c in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    TaskRow(task: c.task, reason: nil, palette: palette, carriedLine: c.line)
                    if c.asks {
                        prompt(c.task.id)
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: carried.map { "\($0.task.id):\($0.asks)" })
    }

    private func prompt(_ id: String) -> some View {
        let rules = RollOverRules.shared
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rules.ASK_LINE).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                MailNeedsPill(label: rules.BREAK_DOWN, palette: palette, spoken: "Break it down into steps") {
                    model.breakDownTaskID = id
                    model.select(id, reduced: reduceMotion)
                    model.answerCarried(id, CarryChoice.breakDown)
                }
                MailNeedsPill(label: rules.SOMEDAY, palette: palette, spoken: "Move it to Someday") {
                    model.answerCarried(id, CarryChoice.someday)
                }
                MailNeedsPill(label: rules.DROP, palette: palette, spoken: "Drop it") {
                    model.answerCarried(id, CarryChoice.drop)
                }
                MailNeedsPill(label: rules.KEEP, palette: palette, quiet: true, spoken: "Keep it and stop asking") {
                    model.answerCarried(id, CarryChoice.keep)
                }
            }
        }
        // Lines up with the task's title: the row's padding, the ring and the gap after it.
        .padding(.leading, MekaSpace.xs + 22 + MekaSpace.m)
        .padding(.bottom, MekaSpace.xs)
    }
}

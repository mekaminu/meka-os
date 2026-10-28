@preconcurrency import MekaKit
import SwiftUI

/// Money redesign (B3) on the Mac, like the Fold's MoneyBillsBlocks: the Bills tab's raised card (what's still to go out
/// before payday as the big number, how many bills, a thin bar of the month's bills already paid and its line) and each
/// bill of the month by date, the ones still to come first and the paid ones greyed. Everything it says is the core's
/// `MoneyBillsView`; these views only draw it.
///
/// Motion: the bar grows to its length (snaps with Reduce Motion); the big number cross-fades when it changes.
struct MoneyBillsCardView: View {
    let bills: MoneyBillsView
    let palette: MekaPalette
    let reduced: Bool
    @State private var shown: Double = 0

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(bills.head).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xs) {
                Text(bills.amount).font(MekaType.greeting).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                Text(bills.count).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if let line = bills.paidLine {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(palette.surface)
                        Capsule().fill(palette.accent).frame(width: g.size.width * min(max(shown, 0), 1))
                    }
                }
                .frame(height: 6)
                Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)  // item 9: the head's grey, as the mockup
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous).fill(palette.surfaceRaised))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(bills.spoken)
        .animation(MekaMotion.appear(reduced: reduced), value: bills.amount)
        .onAppear {
            let share = Double(bills.paidShare)
            if reduced { shown = share } else { withAnimation(MekaMotion.appear(reduced: false)) { shown = share } }
        }
        .onChange(of: bills.paidShare) { _, s in withAnimation(reduced ? nil : MekaMotion.appear(reduced: false)) { shown = Double(s) } }
    }
}

/// One bill: the month and day stacked, the name and what it is, the amount; a paid one greyed.
struct MoneyBillRowView: View {
    let row: MoneyBillRow
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            VStack(spacing: 0) {
                Text(row.month).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text(row.day).font(MekaType.metaStrong).foregroundStyle(row.paid ? palette.textTertiary : palette.textPrimary)
            }
            .frame(width: 36)
            VStack(alignment: .leading, spacing: 0) {
                Text(row.title).font(MekaType.taskTitle).foregroundStyle(row.paid ? palette.textSecondary : palette.textPrimary).lineLimit(1)
                Text(row.sub).font(MekaType.caption).foregroundStyle(subColor).lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(row.amount).font(MekaType.metaStrong).foregroundStyle(row.paid ? palette.textTertiary : palette.textPrimary)
        }
        .padding(.vertical, MekaSpace.xxs)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(row.spoken)
    }

    private var subColor: Color {
        if row.paid { return palette.success }
        if row.subLit { return palette.accent }
        return palette.textSecondary
    }
}

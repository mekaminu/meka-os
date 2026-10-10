@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Annual leave on the Mac (annual leave planner, slice 1), like the Fold's LeavePane: the days booked
/// against the allowance this leave year, a field for each stretch ("27–30 Oct", "Spain 18 Dec – 4 Jan"), the school
/// holidays coming up with how much of each is booked (Book 5 days), the stretches to come and taken with Remove, and
/// the allowance and the leave year's month. Only Strings and Ints cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary, the year line, the line under the field and the steppers'
/// values cross-fade (a refusal lit in the accent); sections and rows stagger in and arrive or leave on the expand
/// spring; a holiday's nudge is lit in the accent; Add and Book give a light haptic, − / + and Remove a tick.
/// Reduce Motion: cross-fades.
struct LeaveSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add or Book said, while the sheet is open.
    @State private var said: CoreModel.LeaveSaid?

    var body: some View {
        let v = model.leave
        let holidays = v?.holidays ?? []
        let booked = v?.booked ?? []
        let taken = v?.taken ?? []
        let allowance = v?.allowance ?? Int32(LeaveRules.shared.DEFAULT_ALLOWANCE)
        let month = v?.yearStartMonth ?? 1
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Annual leave").font(MekaType.upNextTitle).staggeredAppear(0)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(v?.summary ?? LeaveRules.shared.EMPTY_LINE).font(MekaType.body)
                    .foregroundStyle((v?.daysLeft ?? 0) < 0 ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                if let year = v?.yearLine, !year.isEmpty {
                    Text(year).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: year)
                }
            }
            .staggeredAppear(1)
            TextField(LeaveRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { if let r = await model.addLeave(typed) { said = r } }
                }
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(2)
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if !holidays.isEmpty {
                        label("School holidays").staggeredAppear(3)
                        ForEach(Array(holidays.enumerated()), id: \.element.id) { i, h in
                            LeaveHolidayRow(holiday: h, palette: palette) {
                                Task { if let r = await model.bookLeave(forHoliday: h.id) { said = r } }
                            }
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                            .staggeredAppear(4 + i)
                        }
                    }
                    group("Booked", booked, first: 4 + holidays.count)
                    group("Taken this leave year", taken, first: 4 + holidays.count + booked.count)
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: (booked + taken).map(\.id) + holidays.map(\.note))
            }
            .frame(maxHeight: 340)
            label("Allowance").padding(.top, MekaSpace.xs)
            stepper("Days a year", LeaveRules.shared.daysWord(n: allowance)) {
                model.setLeaveAllowance(days: max(Int32(LeaveRules.shared.MIN_ALLOWANCE), allowance - 1), yearStartMonth: month)
            } more: {
                model.setLeaveAllowance(days: min(Int32(LeaveRules.shared.MAX_ALLOWANCE), allowance + 1), yearStartMonth: month)
            }
            stepper("Leave year starts", "1 \(monthName(month))") {
                model.setLeaveAllowance(days: allowance, yearStartMonth: (month + 10) % 12 + 1)
            } more: {
                model.setLeaveAllowance(days: allowance, yearStartMonth: month % 12 + 1)
            }
            Text(LeaveRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    private func monthName(_ month: Int32) -> String {
        let names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        return names[max(0, min(11, Int(month) - 1))]
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.m)
    }

    private func stepper(_ title: String, _ value: String, less: @escaping () -> Void, more: @escaping () -> Void) -> some View {
        HStack(spacing: MekaSpace.xs) {
            Text(title).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Spacer()
            Button("−", action: less).buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                .accessibilityLabel("\(title): fewer")
            Text(value).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: value)
            Button("+", action: more).buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                .accessibilityLabel("\(title): more")
        }
    }

    @ViewBuilder
    private func group(_ title: String, _ rows: [LeaveRow], first: Int) -> some View {
        if !rows.isEmpty {
            label(title).staggeredAppear(first)
            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                LeaveRowView(row: row, palette: palette) { model.removeLeave(row.id) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(first + 1 + i)
            }
        }
    }
}

private struct LeaveHolidayRow: View {
    let holiday: LeaveHoliday
    let palette: MekaPalette
    let onBook: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(holiday.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(holiday.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(holiday.note).font(MekaType.caption)
                    .foregroundStyle(holiday.nudge ? palette.accent : palette.textTertiary)
                    .contentTransition(.opacity)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(holiday.spoken)
            Spacer()
            if let book = holiday.bookLabel {
                Button(book, action: onBook)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("\(book) for \(holiday.title)")
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

private struct LeaveRowView: View {
    let row: LeaveRow
    let palette: MekaPalette
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                if let note = row.note {
                    Text(note).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            Spacer()
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.title)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

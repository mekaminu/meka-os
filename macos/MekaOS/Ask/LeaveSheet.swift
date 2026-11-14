@preconcurrency import MekaKit
import SwiftUI

/// Ask → Annual leave on the Mac in the page shape (every page under Ask in one shape, slice 9; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's LeavePane: the title with its ⓘ, one gold + Add (A stretch of
/// leave, a field: "Spain 18 Dec – 4 Jan"), three numbers (days left, the next leave, school holidays to book), then
/// Booked, School holidays, Taken this leave year and Allowance. A row opens in place with ‹ Annual leave back: a
/// stretch's Remove, a holiday's note and the gold Book n days, or the allowance's − / + steppers. Only Strings, an
/// Int64 and Int32s cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's field and the ⓘ unfold on the expand spring; a
/// row opens and closes with a cross-fade and slide from the trailing edge; the summary, the year line, the answer line
/// and the steppers' values cross-fade (a refusal lit in the accent); a holiday's nudge is lit in the accent; Add and
/// Book give a light haptic, − / + and Remove a tick. Reduce Motion: cross-fades.
struct LeaveSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var text = ""
    /// What the last Add or Book said, while the sheet is open.
    @State private var said: CoreModel.LeaveSaid?
    /// The row that's open, if any (its key: "leave:…", "holiday:…" or "allowance").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?

    var body: some View {
        let rules = LeavePageRules.shared
        let v = model.leave ?? LeaveView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil; said = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                rowDetail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(card).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card, today: model.todayEpochDay), id: \.key) { r in
                            let key = r.key
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Text([r.line, r.whenText].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
                .frame(maxHeight: 400)
                .transition(.opacity)
            } else if let page = model.leavePage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summary).font(MekaType.caption)
                        .foregroundStyle(v.daysLeft < 0 ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if !v.yearLine.isEmpty {
                        Text(v.yearLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: v.yearLine)
                    }
                    if !adding, let said {
                        Text(said.line).font(MekaType.caption)
                            .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            let card = page.cardAt(index: Int32(i))
                            let cardKey = card.key
                            LifePageCardView(card: card, palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = cardKey }
                            }, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; said = nil }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(LeaveRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxHeight: 420)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }

    /// What an open row shows: a stretch as it is now, a school holiday, or the allowance.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: LeaveView) -> some View {
        let rules = LeavePageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let row = rules.leave(view: v, key: key) {
                let id = row.id
                Text(row.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(row.lineShown).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let note = row.note {
                    Text(note).font(MekaType.caption).foregroundStyle(palette.accent)
                }
                Button("Remove") {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                    model.removeLeave(id)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.title)")
            } else if let h = rules.holiday(view: v, key: key) {
                let id = h.id
                Text(h.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(h.lineShown).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(h.noteShown).font(MekaType.caption)
                    .foregroundStyle(h.nudge ? palette.accent : palette.textTertiary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: h.noteShown)
                if let book = h.bookLabel {
                    Button(book) {
                        let m = model
                        Task { if let r = await m.bookLeave(forHoliday: id) { said = r } }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("\(book) for \(h.title)")
                }
                if let said {
                    Text(said.line).font(MekaType.caption)
                        .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
            } else if key == rules.KEY_ALLOWANCE {
                let allowance = v.allowance
                let month = v.yearStartMonth
                Text(rules.ALLOWANCE).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                stepper(rules.DAYS_A_YEAR, LeaveRules.shared.daysWord(n: allowance)) {
                    model.setLeaveAllowance(days: max(Int32(LeaveRules.shared.MIN_ALLOWANCE), allowance - 1), yearStartMonth: month)
                } more: {
                    model.setLeaveAllowance(days: min(Int32(LeaveRules.shared.MAX_ALLOWANCE), allowance + 1), yearStartMonth: month)
                }
                stepper(rules.YEAR_STARTS, rules.yearStartLabel(month: month)) {
                    model.setLeaveAllowance(days: allowance, yearStartMonth: rules.monthBefore(month: month))
                } more: {
                    model.setLeaveAllowance(days: allowance, yearStartMonth: rules.monthAfter(month: month))
                }
                Text(v.allowanceLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            } else {
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    private func stepper(_ title: String, _ value: String, less: @escaping () -> Void, more: @escaping () -> Void) -> some View {
        HStack(spacing: MekaSpace.xs) {
            Text(title).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Spacer()
            Button("−", action: less).buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                .accessibilityLabel("\(title): fewer")
            // A19: centred in the widest value's width, so − and + line up on both steppers.
            Text(LeavePageRules.shared.STEPPER_WIDEST).font(MekaType.itemTitle).hidden()
                .accessibilityHidden(true)
                .overlay {
                    Text(value).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        .fixedSize()
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: value)
                }
            Button("+", action: more).buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                .accessibilityLabel("\(title): more")
        }
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            if page.addChoiceCount > 0 {
                Text(page.addChoiceAt(index: 0).label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            TextField(LeaveRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(LeaveRules.shared.ADD_DESCRIPTION)
                .onSubmit { submit() }
            if let said, said.refused {
                Text(said.line).font(MekaType.caption).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .transition(.opacity)
    }

    private func submit() {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let m = model
        Task {
            guard let r = await m.addLeave(typed) else { return }
            said = r
            if !r.refused { text = ""; adding = false }
        }
    }
}

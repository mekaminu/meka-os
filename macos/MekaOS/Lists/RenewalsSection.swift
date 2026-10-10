@preconcurrency import MekaKit
import SwiftUI

/// RENEWALS on the Mac (build plan M1): the renewals and bills radar, entered by hand and synced with the Fold.
/// Needs doing (overdue, cancel-by close, within lead time) is lit; then Coming up and Later. Clicking a row unfolds
/// its actions; settings are menus and a date field (rule 7). "Renewed"/"Paid" rolls a repeating one on (its date line
/// slides up and the row settles into place); a one-off leaves like a completion. Nothing is paid or cancelled for you.
struct RenewalsSection: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @Binding var open: String?

    var body: some View {
        let r = model.lists?.renewals
        if let line = r?.costLine {
            Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.bottom, MekaSpace.xs)
        }
        if (r?.count ?? 0) == 0 {
            EmptyLine(text: "MOT, insurance, the boiler service, subscriptions and bills. Add each with its date and MEKA shows it in good time, with a cancel-by reminder if you set one.", palette: palette)
        }
        if let home = model.homeUpkeep {
            ListRowView(title: HomeUpkeepRules.shared.LABEL, meta: home.summary, due: false, expanded: open == Self.homeKey, palette: palette,
                        toggle: { open = open == Self.homeKey ? nil : Self.homeKey }) {
                ForEach(home.rows, id: \.presetId) { row in
                    UpkeepRowView(row: row, palette: palette, open: $open)
                }
            }
        }
        section("Needs doing", r?.attention ?? [])
        section("Coming up", r?.upcoming ?? [])
        section("Later", r?.later ?? [])
    }

    static let homeKey = "home-upkeep"

    @ViewBuilder private func section(_ label: String, _ items: [RenewalItem]) -> some View {
        if !items.isEmpty {
            Text(label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.m)
            ForEach(items, id: \.id) { item in
                ListRowView(title: item.title, meta: item.meta, due: item.needsAttention, expanded: open == item.id, palette: palette,
                            toggle: { open = open == item.id ? nil : item.id }) {
                    RenewalDetails(item: item, palette: palette, open: $open)
                }
            }
        }
    }
}

/// One suggested home job: its title, the line (cross-fades to "On the radar · due Sun 1 Nov" once added) and what it
/// covers; Add (press style, light haptic) puts it on the radar, and a job whose date only Meka knows unfolds its row.
private struct UpkeepRowView: View {
    @Environment(CoreModel.self) private var model
    let row: UpkeepRow
    let palette: MekaPalette
    @Binding var open: String?

    var body: some View {
        HStack(alignment: .center, spacing: MekaSpace.m) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.itemMeta)
                    .foregroundStyle(row.tracked ? palette.textSecondary : palette.textTertiary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: true), value: row.line)
                Text(row.note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer(minLength: 0)
            if !row.tracked {
                let dateKnown = row.dateKnown
                Button("Add") {
                    model.addHomeUpkeep(row.presetId) { id in if !dateKnown { open = id } }
                }
            }
        }
    }
}

private struct RenewalDetails: View {
    @Environment(CoreModel.self) private var model
    let item: RenewalItem
    let palette: MekaPalette
    @Binding var open: String?
    @State private var cost = ""
    @State private var costProblem: String?

    var body: some View {
        if let subject = item.subject { Text(subject).font(MekaType.body).foregroundStyle(palette.textSecondary) }
        if let notes = item.notes { Text(notes).font(MekaType.body).foregroundStyle(palette.textSecondary) }
        HStack(spacing: MekaSpace.l) {
            Button(item.doneLabel) {
                if item.repeats == RenewalRules.shared.repeatAt(index: 0) { open = nil } // a one-off leaves the list
                model.renewalDone(item.id)
            }
            Button(item.stopLabel) { open = nil; model.stopRenewal(item.id) }
            Button("Delete", role: .destructive) { open = nil; model.deleteRenewal(item.id) }
        }
        .rowActions(palette)
        HStack(spacing: MekaSpace.m) {
            DueDateField(day: item.dueDay) { model.setRenewalDue(item.id, day: $0) }
            Menu("Repeats: \(item.repeatLabel)") {
                ForEach(RenewalChoices.repeats, id: \.self) { rp in
                    Button(RenewalRules.shared.repeatLabel(r: rp)) { model.setRenewalRepeat(item.id, rp) }
                }
            }
            .menuStyle(.button).fixedSize()
            Menu("Kind: \(RenewalRules.shared.kindLabel(k: item.kind))") {
                ForEach(RenewalChoices.kinds, id: \.self) { k in
                    Button(RenewalRules.shared.kindLabel(k: k)) { model.setRenewalKind(item.id, k) }
                }
            }
            .menuStyle(.button).fixedSize()
        }
        HStack(spacing: MekaSpace.m) {
            Menu("Show it…") {
                ForEach(RenewalRules.shared.LEAD_CHOICES, id: \.label) { c in
                    Button(c.label) { if let d = c.days?.intValue { model.setRenewalLead(item.id, days: d) } }
                }
            }
            .menuStyle(.button).fixedSize()
            Menu("Cancel by…") {
                ForEach(RenewalRules.shared.CANCEL_CHOICES, id: \.label) { c in
                    Button(c.label) { model.setRenewalCancelBy(item.id, daysBefore: c.days?.intValue) }
                }
            }
            .menuStyle(.button).fixedSize()
            TextField(costHint, text: $cost)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 220)
                .onSubmit {
                    costProblem = model.setRenewalCost(item.id, cost)
                    if costProblem == nil { cost = "" }
                }
        }
        if let costProblem { Text(costProblem).font(MekaType.caption).foregroundStyle(palette.critical) }
    }

    private var costHint: String {
        if let p = item.costPence { return "Cost (now \(RenewalRules.shared.formatPence(pence: p.int64Value)))" }
        return "Cost, e.g. 9.99"
    }
}

/// The kinds and repeats in the core's order, as Swift enums.
@MainActor
enum RenewalChoices {
    static let kinds: [ObligationKind] = (0..<Int(RenewalRules.shared.kindCount)).map { RenewalRules.shared.kindAt(index: Int32($0)) }
    static let repeats: [RenewalRepeat] = (0..<Int(RenewalRules.shared.repeatCount)).map { RenewalRules.shared.repeatAt(index: Int32($0)) }
}

/// A compact date field over epoch days, limited to ten years either side of today (the core's range).
private struct DueDateField: View {
    @Environment(CoreModel.self) private var model
    let day: Int64
    let set: (Int64) -> Void

    var body: some View {
        let today = model.todayEpochDay
        DatePicker(
            "Due",
            selection: Binding(get: { CoreModel.date(ofEpochDay: day) }, set: { set(CoreModel.epochDay(of: $0)) }),
            in: CoreModel.date(ofEpochDay: today - 3650)...CoreModel.date(ofEpochDay: today + 3650),
            displayedComponents: .date
        )
        .datePickerStyle(.field)
        .fixedSize()
    }
}

struct AddRenewalRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var title = ""
    @State private var kind: ObligationKind = .other
    @State private var due: Int64?
    @State private var repeats: RenewalRepeat = RenewalRules.shared.defaultRepeat(k: .other)
    @State private var cost = ""
    @State private var cancelBy: Int?
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            TextField("Renewal or bill…", text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
            if !title.isEmpty {
                HStack(spacing: MekaSpace.m) {
                    Picker("Kind", selection: Binding(get: { kind }, set: { kind = $0; repeats = RenewalRules.shared.defaultRepeat(k: $0) })) {
                        ForEach(RenewalChoices.kinds, id: \.self) { k in Text(RenewalRules.shared.kindLabel(k: k)).tag(k) }
                    }
                    .fixedSize()
                    DueDateField(day: dueDay) { due = $0 }
                    Picker("Repeats", selection: $repeats) {
                        ForEach(RenewalChoices.repeats, id: \.self) { rp in Text(RenewalRules.shared.repeatLabel(r: rp)).tag(rp) }
                    }
                    .fixedSize()
                }
                .transition(.opacity)
                HStack(spacing: MekaSpace.m) {
                    TextField("Cost (optional), e.g. 9.99", text: $cost).textFieldStyle(.roundedBorder).frame(maxWidth: 220).onSubmit(add)
                    Picker("Cancel by", selection: $cancelBy) {
                        ForEach(RenewalRules.shared.CANCEL_CHOICES, id: \.label) { c in Text(c.label).tag(c.days?.intValue) }
                    }
                    .fixedSize()
                    Button("Add", action: add).keyboardShortcut(.defaultAction)
                }
                .transition(.opacity)
                if let problem { Text(problem).font(MekaType.caption).foregroundStyle(palette.critical) }
            }
        }
    }

    private var dueDay: Int64 { due ?? model.todayEpochDay + Int64(RenewalRules.shared.DEFAULT_DUE_IN_DAYS) }

    private func add() {
        problem = model.addRenewal(title, kind: kind, dueDay: dueDay, repeats: repeats, cost: cost, cancelByDaysBefore: cancelBy)
        guard problem == nil else { return }
        title = ""; cost = ""; cancelBy = nil; due = nil; kind = .other
        repeats = RenewalRules.shared.defaultRepeat(k: .other)
    }
}

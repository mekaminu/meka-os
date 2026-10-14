@preconcurrency import MekaKit
import AppKit
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
            EmptyLine(text: "MOT, insurance, the boiler service, subscriptions and bills. Add each with its date and Navilife shows it in good time, with a cancel-by reminder if you set one.", palette: palette)
        }
        if let home = model.homeUpkeep {
            ListRowView(title: HomeUpkeepRules.shared.LABEL, meta: home.summary, due: false, expanded: open == Self.homeKey, palette: palette,
                        toggle: { open = open == Self.homeKey ? nil : Self.homeKey }) {
                ForEach(home.rows, id: \.presetId) { row in
                    UpkeepRowView(row: row, palette: palette, open: $open)
                }
            }
        }
        // Two cars: one row per car, each with its own dates; the last offers another car.
        if let cars = model.cars {
            ForEach(Array(cars.cars.enumerated()), id: \.element.carId) { index, car in
                let key = Self.carKey(car.carId)
                ListRowView(title: car.title, meta: car.summary, due: false, expanded: open == key, palette: palette,
                            toggle: { open = open == key ? nil : key }) {
                    CarDetails(car: car, last: index == cars.cars.count - 1, addLine: cars.addLine, palette: palette,
                               open: $open, removed: $removedCar)
                }
            }
            if let removed = removedCar {
                HStack {
                    Text(CarRules.shared.removedLine(title: removed.title)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    Spacer(minLength: 0)
                    Button("Undo") { model.undoRemoveCar(removed.id); removedCar = nil }
                }
                .transition(.opacity)
            }
        }
        if let money = model.moneyDates {
            ListRowView(title: MoneyDateRules.shared.LABEL, meta: money.summary, due: false, expanded: open == Self.moneyKey, palette: palette,
                        toggle: { open = open == Self.moneyKey ? nil : Self.moneyKey }) {
                ForEach(money.rows, id: \.presetId) { row in
                    MoneyDateRowView(row: row, palette: palette, open: $open)
                }
            }
        }
        section("Needs doing", r?.attention ?? [])
        section("Coming up", r?.upcoming ?? [])
        section("Later", r?.later ?? [])
    }

    @State private var removedCar: RemovedCar?

    static let homeKey = "home-upkeep"
    /// The first car keeps the key it always had.
    static func carKey(_ carId: String) -> String { carId == CarRules.shared.ENTITY_ID ? "car" : "car-\(carId)" }
    static let moneyKey = "money-dates"

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

/// UK money deadlines (V1): Self Assessment, the ISA allowance, the energy and broadband contracts and a pension
/// check-in, each with Add like the home jobs; a date only Meka knows unfolds its new row so it can be set. Dates only.
private struct MoneyDateRowView: View {
    @Environment(CoreModel.self) private var model
    let row: MoneyDateRow
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
                    model.addMoneyDate(row.presetId) { id in if !dateKnown { open = id } }
                }
            }
        }
    }
}

/// Car on autopilot (V1, slice 1): the registration (typed once; Return saves it) and the car's four dates, each with
/// Add like the home jobs; adding one unfolds its new row so the real date can be set. Reminders come four weeks and a
/// week before. Slice 2: the DVLA's line and MOT history, and "Use the DVLA's date" when the radar's date differs.
/// The car just removed, for its Undo line (two cars).
struct RemovedCar: Equatable {
    let id: String
    let title: String
}

/// Two cars (2026-10-14): each car's row also has its name, Add another car on the last one and Remove on a later one.
private struct CarDetails: View {
    @Environment(CoreModel.self) private var model
    let car: CarView
    let last: Bool
    let addLine: String?
    let palette: MekaPalette
    @Binding var open: String?
    @Binding var removed: RemovedCar?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var reg = ""
    @State private var name = ""
    @State private var problem: String?

    var body: some View {
        let carId = car.carId
        TextField(car.registration.map { "Registration (now \($0)), blank to clear" } ?? "Registration, e.g. AB12 CDE", text: $reg)
            .textFieldStyle(.roundedBorder)
            .frame(maxWidth: 260)
            .onSubmit {
                problem = model.setCarRegistration(carId, reg)
                if problem == nil { reg = "" }
            }
        TextField(car.name.map { "Name (now \($0)), blank to clear" } ?? "Name, e.g. the Golf (optional)", text: $name)
            .textFieldStyle(.roundedBorder)
            .frame(maxWidth: 260)
            .onSubmit {
                model.setCarName(carId, name)
                name = ""
            }
        if let problem { Text(problem).font(MekaType.caption).foregroundStyle(palette.critical) }
        // Slice 2: what the DVLA says (MEKA's server asks once a day).
        if let dvla = car.dvlaLine {
            Text(dvla).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: true), value: dvla)
        }
        if let history = car.historyLine { Text(history).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
        ForEach(car.rows, id: \.presetId) { row in
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
                    Button("Add") { model.addCarDate(carId, row.presetId) { id in open = id } }
                } else if row.dvlaOffer != nil {
                    // The radar's date differs from the DVLA's; never changed without this click.
                    Button("Use the DVLA's date") { model.useDvlaDate(carId, row.presetId) }
                }
            }
        }
        HStack(spacing: MekaSpace.m) {
            if last, addLine != nil {
                Button("Add another car") { model.addCar { id in open = RenewalsSection.carKey(id) } }
            }
            if car.removable {
                Button("Remove", role: .destructive) {
                    let title = car.title
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) {
                        removed = RemovedCar(id: carId, title: title)
                        open = nil
                    }
                    model.removeCar(carId)
                }
            }
        }
        if last, let addLine { Text(addLine).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
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
            // Bills due: opens the page Meka saved as where he pays it; MEKA never pays.
            if let link = item.payLink, let url = URL(string: link) {
                Button(BillRules.shared.payLabel(link: link)) { MekaHaptics.light(); NSWorkspace.shared.open(url) }
            }
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
        PayLinkField(item: item, palette: palette)
    }

    private var costHint: String {
        if let p = item.costPence { return "Cost (now \(RenewalRules.shared.formatPence(pence: p.int64Value)))" }
        return "Cost, e.g. 9.99"
    }
}

/// Bills due: where Meka pays it ("octopus.energy"), so the reminder three days before can offer Pay. Return saves it;
/// one that isn't a web address says so; Remove clears it. The line under the field cross-fades as it changes.
private struct PayLinkField: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let item: RenewalItem
    let palette: MekaPalette
    @State private var text = ""
    @State private var bad = false

    var body: some View {
        let line = BillRules.shared.linkLine(item: item, bad: bad)
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.m) {
                TextField(item.payLink.map { "Pay link (now \(BillRules.shared.site(link: $0)))" } ?? "Pay link, e.g. octopus.energy", text: $text)
                    .textFieldStyle(.roundedBorder).font(MekaType.body).frame(maxWidth: 320)
                    .onSubmit { save(text) }
                    .onChange(of: text) { bad = false }
                if item.payLink != nil {
                    Button("Remove") { save("") }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
            }
            Text(line).font(MekaType.caption).foregroundStyle(bad ? palette.critical : palette.textTertiary)
                .id(line).transition(.opacity)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
    }

    private func save(_ link: String) {
        let id = item.id
        let value = String(link.trimmingCharacters(in: .whitespaces).prefix(Int(SessionRules.shared.MAX_LINK)))
        guard !value.isEmpty || item.payLink != nil else { return }
        Task {
            let ok = await model.setRenewalPayLink(id, value)
            bad = !ok
            if ok { text = "" }
        }
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

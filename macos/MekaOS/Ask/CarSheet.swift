@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Car on the Mac (car and parking, slice 2; the page shape, 2026-11-12). Where the car is parked stays on
/// the Fold (never synced), so the Mac's page is the shape every page under Ask shares (docs/design/look/LifePage.dc.html):
/// the title with its ⓘ, one gold + Add, three numbers (the next date, the dates on the radar, the parking timer) and the
/// Dates card (a row or All dates opens Lists → Renewals); a running timer keeps its card with +15 min / +30 min / +1 h
/// and Stop timer (with Undo). + Add unfolds its choices: A parking timer ("2 h", "until 15:30", or a parking app's
/// confirmation pasted in; with two cars, chips pick which) or A date. Only Strings and an Int32 cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and the ⓘ unfold on the expand spring; a
/// timer's line cross-fades as the minutes go and its colour blends to brass; chips blend as chosen. Reduce Motion:
/// cross-fades.
struct CarSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var chosen: String?
    @State private var said: String?
    @State private var refused = false
    @State private var undoCar: String?
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?

    var body: some View {
        let rules = ParkingTimerRules.shared
        let page = CarPageRules.shared
        let timers = model.parkingTimers?.rows ?? []
        let cars = model.cars?.cars ?? []
        let forCar = chosen ?? cars.first?.carId ?? CarRules.shared.ENTITY_ID
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let view = model.carPage() {
                LifePageHeader(title: view.title, info: view.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); addChoice = nil }
                }
                .staggeredAppear(0)
                if adding {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(view.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                        HStack(spacing: MekaSpace.xs) {
                            ForEach(0..<Int(view.addChoiceCount), id: \.self) { i in
                                let c = view.addChoiceAt(index: Int32(i))
                                chip(c.label, lit: addChoice == c.key) {
                                    if c.key == page.ADD_DATE {
                                        dismiss()
                                        model.go(to: .lists, reduced: reduceMotion)
                                    } else {
                                        addChoice = c.key
                                    }
                                }
                                .help(c.hint)
                            }
                        }
                        if addChoice == page.ADD_TIMER {
                            if cars.count > 1 {
                                HStack(spacing: MekaSpace.xs) {
                                    ForEach(cars, id: \.carId) { c in
                                        chip(ParkingRules.shared.heading(title: c.title), lit: c.carId == forCar) { chosen = c.carId }
                                    }
                                }
                            }
                            TextField(rules.HINT, text: $text)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit {
                                    let typed = text
                                    guard !typed.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                                    text = ""
                                    undoCar = nil
                                    let notRead = rules.NOT_READ
                                    model.setParkingTimer(forCar, typed) { line in
                                        said = line ?? notRead
                                        refused = line == nil
                                        if line != nil { adding = false; addChoice = nil }
                                    }
                                }
                            // Slice 5: a RingGo/JustPark confirmation pasted into the field is read on the Mac too.
                            Text(ParkingConfirmationRules.shared.PASTE_HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(MekaSpace.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    .transition(.opacity)
                }
                LifeNumberTiles(page: view, palette: palette).staggeredAppear(1)
                if let said {
                    HStack {
                        Text(said).font(MekaType.caption).foregroundStyle(refused ? palette.accent : palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                        if let id = undoCar {
                            Button("Undo") {
                                model.undoEndParkingTimer(id)
                                undoCar = nil
                                self.said = nil
                            }
                            .buttonStyle(.link)
                        }
                    }
                }
                ForEach(0..<Int(view.cardCount), id: \.self) { i in
                    LifePageCardView(card: view.cardAt(index: Int32(i)), palette: palette, onMore: openDates, onRow: { _ in openDates() })
                        .staggeredAppear(2 + i)
                }
            }
            if !timers.isEmpty {
                label(rules.HEADING)
                ForEach(timers, id: \.carId) { row in
                    timerRow(row, showTitle: cars.count > 1)
                        .transition(.opacity)
                }
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: timers.map(\.carId))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: adding)
        .padding(MekaSpace.l)
        .frame(width: 560)
        .background(palette.surface)
    }

    /// A date's row or All dates: every car's dates in Lists → Renewals.
    private func openDates() {
        dismiss()
        model.go(to: .lists, reduced: reduceMotion)
    }

    private func timerRow(_ row: TimerRow, showTitle: Bool) -> some View {
        let rules = ParkingTimerRules.shared
        let carId = row.carId
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                if showTitle {
                    Text(ParkingRules.shared.heading(title: row.title)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                Text(row.line).font(MekaType.body)
                    .foregroundStyle(row.lit ? palette.accent : palette.textPrimary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
                if let note = row.note {
                    Text(note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            .accessibilityElement(children: .combine)
            HStack(spacing: MekaSpace.xs) {
                ForEach(rules.EXTEND_MIN.map { $0.int32Value }, id: \.self) { m in
                    chip(rules.extendLabel(min: m), lit: false) {
                        model.extendParkingTimer(carId, m) { line in
                            if let line { said = line; refused = false }
                        }
                    }
                }
                // Quality gate slice 22: "Stop timer" while it runs, "Clear" once ended (never "Ended" beside "2 h left").
                Button(rules.endAction(row: row)) {
                    model.endParkingTimer(carId) { line in
                        if let line { said = line; refused = false; undoCar = carId }
                    }
                }
                .buttonStyle(.link)
                .accessibilityLabel("\(rules.endAction(row: row)): \(row.title)")
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Car on the Mac (car and parking, slice 2). Where the car is parked stays on the Fold (never synced), so
/// the Mac shows the parking timer only, like the item says: each car's timer ("Paid until 15:30 · 1 h 20 left", brass
/// in its last ten minutes), +15 min / +30 min / +1 h and Ended (with Undo), and a field to start one ("2 h", "until
/// 15:30", or a parking app's confirmation pasted in: slice 5); with two cars, chips pick which. Each car's dates open Lists → Renewals. Only Strings and an Int32 cross to
/// the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; a timer's line cross-fades as the minutes go and its
/// colour blends to brass; rows arrive and leave on the expand spring; chips blend as chosen. Reduce Motion: cross-fades.
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

    var body: some View {
        let rules = ParkingTimerRules.shared
        let timers = model.parkingTimers?.rows ?? []
        let cars = model.cars?.cars ?? []
        let forCar = chosen ?? cars.first?.carId ?? CarRules.shared.ENTITY_ID
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(ParkingRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            label(rules.HEADING).staggeredAppear(1)
            if timers.isEmpty {
                Text(rules.EMPTY_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            ForEach(timers, id: \.carId) { row in
                timerRow(row, showTitle: cars.count > 1)
                    .transition(.opacity)
            }
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
                    }
                }
                .staggeredAppear(2)
            // Slice 5: a RingGo/JustPark confirmation pasted into the field is read on the Mac too.
            Text(ParkingConfirmationRules.shared.PASTE_HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
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
            label("DATES").staggeredAppear(3)
            ForEach(cars, id: \.carId) { c in
                Button {
                    dismiss()
                    model.go(to: .lists, reduced: reduceMotion)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(ParkingRules.shared.heading(title: c.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Text(c.summary.isEmpty ? ParkingRules.shared.DATES_LINE : c.summary)
                                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                        Spacer()
                        Text(ParkingRules.shared.DATES_ACTION).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                }
                .buttonStyle(MekaPressStyle())
            }
            .staggeredAppear(4)
            Text("Where you parked is kept on the phone only; the timer is shared with the Mac.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(5)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: timers.map(\.carId))
        .padding(MekaSpace.l)
        .frame(width: 560)
        .background(palette.surface)
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

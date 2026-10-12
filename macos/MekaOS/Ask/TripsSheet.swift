@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Trips on the Mac (travel mode, slice 1), like the Fold's TripsPane: one field for trips and their
/// bookings ("Barcelona 14–18 Nov", "Flight BA478 Sat 14 Nov 07:40 ref ABC123", "Hotel Arts 14–18 Nov"), a row per
/// trip with its dates and status. Click a row to open its day-by-day timeline: each booking with its time (or its
/// kind) and × to remove it, and Remove trip. Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line under the field cross-fade (a refused line lit in
/// the accent); rows stagger in and arrive or leave on the expand spring; an opened row grows on the expand spring;
/// Add gives a light haptic (a tick when refused), Remove and × a tick. Reduce Motion: cross-fades.
struct TripsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: CoreModel.TripSaid?
    /// The row that's open, if any.
    @State private var open: String?

    var body: some View {
        let v = model.trips
        let rows = v?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Trips").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? TripRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            TextField(TripRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { said = await model.addTrip(typed) }
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
                    if !rows.isEmpty {
                        Text("YOUR TRIPS")
                            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.m)
                            .staggeredAppear(3)
                    }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        TripRowView(row: row, palette: palette, isOpen: open == row.id) {
                            open = open == row.id ? nil : row.id
                        }
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        .staggeredAppear(4 + i)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
                .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            }
            .frame(maxHeight: 380)
            Text(TripRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
    }
}

private struct TripRowView: View {
    @Environment(CoreModel.self) private var model
    let row: TripRow
    let palette: MekaPalette
    let isOpen: Bool
    let onToggle: () -> Void

    var body: some View {
        let days = row.timeline.filter { !$0.lines.isEmpty }
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(row.status).font(MekaType.caption).foregroundStyle(row.statusLit ? palette.accent : palette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            if isOpen {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if days.isEmpty {
                        Text(TripRules.shared.NO_BOOKINGS).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    ForEach(days, id: \.epochDay) { day in
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(day.label.uppercased())
                                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                .foregroundStyle(palette.textTertiary)
                            ForEach(day.lines, id: \.key) { line in
                                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                                    HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                                        Text(line.lead).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                            .frame(width: 48, alignment: .leading)
                                        Text(line.text).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    }
                                    .accessibilityElement(children: .combine)
                                    .accessibilityLabel(line.spoken)
                                    Spacer()
                                    Button("×") { model.removeTripBooking(line.bookingId) }
                                        .buttonStyle(MekaPressStyle())
                                        .foregroundStyle(palette.textTertiary)
                                        .accessibilityLabel("Remove \(line.text)")
                                }
                            }
                        }
                    }
                    Button("Remove trip") { model.removeTrip(row.id) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityLabel("Remove \(row.name) and its bookings")
                }
                .padding(.top, MekaSpace.xs)
                .transition(.opacity)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

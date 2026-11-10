@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Appointments on the Mac (appointment nudges, slice 1), like the Fold's AppointmentsPane: the dentist,
/// the optician, a haircut… each with its usual interval, due from the last visit. One field does it all ("Dentist
/// every 6 months, last 12 Apr", "dentist booked Tue 20 Oct 9:30"); Went notes a visit today (with Undo), Remove takes
/// one off; Common ones adds the usual ones in one click. Needs you asks when one comes due. Only Strings cross.
///
/// Motion: the sheet scale-fades (system); the summary, the field's line and each row's status cross-fade (status lit
/// in the accent when due or not noted); rows stagger in and move on the expand spring as they come due; an add or Went
/// gives a light haptic, a refusal or Remove a tick. Reduce Motion: cross-fades.
struct AppointmentsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var said: CoreModel.AppointmentLine?

    var body: some View {
        let v = model.appointments ?? AppointmentView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Appointments").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            TextField(AppointmentRules.shared.HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task {
                        guard let r = await model.typeAppointment(typed) else { return }
                        said = r
                        if r.refused { text = typed } // keep the words to fix
                    }
                }
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
                    if !v.rows.isEmpty { label("Kept up with") }
                    ForEach(v.rows, id: \.id) { row in
                        AppointmentRowView(row: row, palette: palette, reduceMotion: reduceMotion,
                                           onWent: { model.appointmentWent(row.id) },
                                           onRemove: { model.removeAppointment(row.id) })
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                    if !v.suggestions.isEmpty {
                        label("Common ones")
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: MekaSpace.xs) {
                                ForEach(v.suggestions, id: \.id) { s in
                                    Button {
                                        Task { if let r = await model.addAppointmentPreset(s.id) { said = r } }
                                    } label: {
                                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                            Text("+ \(s.title)").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                                            Text(s.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                        }
                                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                                    }
                                    .buttonStyle(MekaPressStyle())
                                    .accessibilityLabel("Add \(s.title), \(s.line)")
                                }
                            }
                        }
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: v.rows.map(\.id) + v.suggestions.map(\.id))
            }
            .frame(maxHeight: 420)
            .staggeredAppear(3)
            Text(AppointmentRules.shared.NOTE_SHOWN).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
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

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
    }
}

private struct AppointmentRowView: View {
    let row: AppointmentRow
    let palette: MekaPalette
    let reduceMotion: Bool
    let onWent: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(2)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(2)
                Text(row.status).font(MekaType.caption)
                    .foregroundStyle(row.lit ? palette.accent : palette.textSecondary)
                    .lineLimit(2)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.status)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(AppointmentRules.shared.WENT_LABEL, action: onWent)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("Went to \(row.title) today")
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

/// Appointment nudges, slice 1: Needs you's card for one appointment that has come due, like the Fold's
/// AppointmentCardView: "Due Mon 2 Nov · every 6 months" · "Dentist" · the question · Booked… · Went · Not yet.
/// Booked… unfolds a field on the expand spring for the day and time; a date that can't be read stays on the card
/// with the reason under the field, lit in the accent. Reduce Motion: cross-fades. MEKA books nothing. In the approved
/// Needs you look (Mac slice 3) it is a compact NeedsYouDecideCardView: the title over its when and question on one
/// line, Booked… · Went · Not yet, then Call to book · Copy email in a quieter row.
struct AppointmentNudgeRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: AppointmentNudge
    let palette: MekaPalette
    @State private var asking = false
    @State private var text = ""

    var body: some View {
        // The approved look (TO DECIDE, the Fold's slice 4): one compact card; Booked… opens When? inside it, and
        // Call to book · Copy email sit in its quieter row.
        NeedsYouDecideCardView(card: NeedsYouDecideRules.shared.appointment(card: card), palette: palette,
                               onYes: { MekaHaptics.light(); asking.toggle() },
                               onNo: { model.appointmentNotYet(card.id) },
                               onChange: { model.appointmentWent(card.id) },
                               onMore: { i in
                                   if i == 0 { model.appointmentCallToBook(card.id) } else { model.appointmentCopyEmail(card.id) }
                               },
                               below: asking ? AnyView(whenField) : nil)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: asking)
    }

    private var whenField: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            TextField(card.whenHint, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.bookAppointment(card.id, when: text) }
            if let refusal = model.appointmentRefusal, refusal.id == card.id {
                Text(refusal.line).font(MekaType.caption).foregroundStyle(palette.accent)
            }
        }
        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
    }
}

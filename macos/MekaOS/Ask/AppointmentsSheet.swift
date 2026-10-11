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
            Text(AppointmentRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
                Text(row.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(1)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
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
/// with the reason under the field, lit in the accent. Reduce Motion: cross-fades. MEKA books nothing.
struct AppointmentNudgeRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: AppointmentNudge
    let palette: MekaPalette
    @State private var asking = false
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(card.line).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(card.question).font(MekaType.body).foregroundStyle(palette.textSecondary)
            Text(card.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if asking {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    TextField(card.whenHint, text: $text)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { model.bookAppointment(card.id, when: text) }
                    if let refusal = model.appointmentRefusal, refusal.id == card.id {
                        Text(refusal.line).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
                .padding(.top, MekaSpace.m)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: MekaSpace.xs) {
                pill(card.bookedLabel, filled: true) { MekaHaptics.light(); asking.toggle() }
                pill(card.wentLabel, filled: false) { model.appointmentWent(card.id) }
                pill(card.laterLabel, filled: false) { model.appointmentNotYet(card.id) }
            }
            .padding(.top, MekaSpace.m)
            // Slice 2b: Call to book (a task on Today) and Copy email (the pasteboard); MEKA calls and sends nothing.
            HStack(spacing: MekaSpace.xs) {
                pill(card.callLabel, filled: false) { model.appointmentCallToBook(card.id) }
                pill(card.emailLabel, filled: false) { model.appointmentCopyEmail(card.id) }
            }
            .padding(.top, MekaSpace.xs)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: asking)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(card.spoken)
        .mekaHoverLift()
    }

    private func pill(_ label: String, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(filled ? palette.onAccent : palette.accent)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(filled ? palette.accent : palette.surface, in: Capsule())
    }
}

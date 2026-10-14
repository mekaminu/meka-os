@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → School on the Mac (school rhythm, slice 1), like the Fold's SchoolPane: Rex's and Logan's school year,
/// typed once, one line each ("INSET 27 Oct", "Half term 26–30 Oct", "Rex PE Tue"), in three groups — days off, dates
/// and every week — each row with Remove. Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line under the field cross-fade (the "couldn't read"
/// line lit in the accent); rows stagger in and arrive or leave on the expand spring; Add gives a light haptic, Remove
/// a tick. Reduce Motion: cross-fades.
///
/// School dates filled in (slice 1): the council card under the summary (Fill in a council's holidays, or Remove all,
/// each followed by a line with Undo) and a second field for the school's INSET days pasted as dates or a letter.
///
/// Slice 2, the yearly look: the card's brass line for new dates from the council (Add them, then Undo) or, from June,
/// next school year's holidays not in yet (Council's page when the council's dates are in); it cross-fades.
struct SchoolSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: String?
    @State private var inset = ""
    @State private var insetSaid: String?
    /// The council card's last action for Undo: the council's id and whether it was Fill in.
    @State private var termsDone: (id: String, filled: Bool)?
    @State private var termsSaid: String?
    /// What Add them added, for Undo (exactly those go again).
    @State private var addedIds: [String]?

    var body: some View {
        let v = model.school
        let off = v?.off ?? []
        let dates = v?.dates ?? []
        let weekly = v?.weekly ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("School").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? SchoolRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            if let card = v?.terms {
                termsCard(card).staggeredAppear(2)
            }
            TextField(SchoolRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { said = await model.addSchool(typed) }
                }
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(2)
            if let said {
                Text(said).font(MekaType.caption)
                    .foregroundStyle(said == SchoolRules.shared.NOT_READ ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            TextField(SchoolTerms.shared.INSET_HINT, text: $inset)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let pasted = inset
                    inset = ""
                    Task { insetSaid = await model.addSchoolInset(pasted) }
                }
                .staggeredAppear(3)
            if let insetSaid {
                Text(insetSaid).font(MekaType.caption)
                    .foregroundStyle(insetSaid == SchoolTerms.shared.INSET_NONE ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: insetSaid)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    group("Days off", off, first: 3)
                    group("Dates", dates, first: 3 + off.count)
                    group("Every week", weekly, first: 3 + off.count + dates.count)
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: (off + dates + weekly).map(\.id))
            }
            .frame(maxHeight: 360)
            Text(SchoolRules.shared.SHARED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
    }

    /// The council card: councils to pick from with Fill in, or what's in with Remove all; then the last action's line
    /// with Undo. Only the council's id crosses to the core.
    @ViewBuilder
    private func termsCard(_ card: SchoolTermCard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(card.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: card.line)
            if let r = card.refresh {
                HStack(spacing: MekaSpace.xs) {
                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    if let act = r.action {
                        Button(act) {
                            if let id = r.councilId {
                                Task {
                                    if let a = await model.addNewSchoolTerms(id) {
                                        termsSaid = a.line
                                        addedIds = a.ids
                                        termsDone = nil
                                    }
                                }
                            } else if let u = r.url, let url = URL(string: u) {
                                openURL(url)
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("\(act). \(r.line)")
                    }
                }
                .transition(.opacity)
            }
            ForEach(card.choices, id: \.id) { c in
                HStack(spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(c.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    Spacer()
                    Button("Fill in") {
                        let id = c.id
                        Task {
                            termsSaid = await model.fillSchoolTerms(id)
                            termsDone = termsSaid == nil ? nil : (id, true)
                            addedIds = nil
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Fill in \(c.name)'s holidays")
                }
            }
            if let f = card.filled {
                Button("Remove all") {
                    let id = f.id
                    Task {
                        termsSaid = await model.removeSchoolTerms(id)
                        termsDone = termsSaid == nil ? nil : (id, false)
                        addedIds = nil
                    }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove all of \(f.name)'s holidays")
            } else if !card.choices.isEmpty {
                Text(SchoolTerms.shared.NOT_LISTED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if let termsSaid {
                HStack(spacing: MekaSpace.xs) {
                    Text(termsSaid).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let ids = addedIds {
                        Button("Undo") {
                            Task {
                                await model.undoNewSchoolTerms(ids)
                                addedIds = nil
                                self.termsSaid = nil
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                    } else if let done = termsDone {
                        Button("Undo") {
                            let id = done.id
                            let filled = done.filled
                            Task {
                                if filled { _ = await model.removeSchoolTerms(id) } else { _ = await model.fillSchoolTerms(id) }
                                termsDone = nil
                                self.termsSaid = nil
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: termsSaid)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: card.refresh?.line)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: card.filled?.id)
    }

    @ViewBuilder
    private func group(_ label: String, _ rows: [SchoolRow], first: Int) -> some View {
        if !rows.isEmpty {
            Text(label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.m)
                .staggeredAppear(first)
            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                SchoolRowView(row: row, palette: palette) { model.removeSchool(row.id) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(first + 1 + i)
            }
        }
    }
}

private struct SchoolRowView: View {
    let row: SchoolRow
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
                if let source = row.source {
                    Text(source).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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

/// School rhythm, slice 1: the week-ahead cover questions in Needs you, above the requests, like the Fold's
/// SchoolCoverCardView: "Rex and Logan are off Mon 27 Oct" · "INSET day · in 5 days" · the question · what working from
/// home changes · I'll work from home · Covered. A card folds away on the expand spring and the undo bar rises.
/// Reduce Motion: cross-fades. Nothing is sent to anyone. Date night's week-before card (slice 2) follows them, like the
/// Fold's DateNightCardView: "In 7 days · from 19:00" · "Date night · Fri 23 Oct" · Booked · Skip this one. Then one
/// card per appointment that has come due (AppointmentNudgeRow, appointment nudges slice 1).
struct SchoolCoversView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.schoolCovers.enumerated()), id: \.element.id) { i, card in
                SchoolCoverRow(card: card, palette: palette) { model.answerSchoolCover(card.id, home: $0) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
            if let card = model.dateNightNudge {
                DateNightNudgeRow(card: card, palette: palette) { model.answerDateNight(day: card.day, booked: $0) }
                    .id("date-night-\(card.day)")
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + model.schoolCovers.count)
            }
            // Appointments that have come due (appointment nudges, slice 1), after date night's card.
            ForEach(Array(model.appointmentNudges.enumerated()), id: \.element.id) { i, card in
                AppointmentNudgeRow(card: card, palette: palette)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + model.schoolCovers.count + (model.dateNightNudge == nil ? 0 : 1) + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.schoolCovers.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.dateNightNudge?.day)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.appointmentNudges.map(\.id))
    }
}

private struct SchoolCoverRow: View {
    let card: SchoolCover
    let palette: MekaPalette
    let answer: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(card.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(card.question).font(MekaType.body).foregroundStyle(palette.textSecondary)
            Text(card.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                pill(card.homeLabel, filled: true) { answer(true) }
                pill(card.coveredLabel, filled: false) { answer(false) }
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
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

private struct DateNightNudgeRow: View {
    let card: DateNightNudge
    let palette: MekaPalette
    let answer: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(card.line).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(card.question).font(MekaType.body).foregroundStyle(palette.textSecondary)
            Text(card.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                pill(card.bookedLabel, filled: true) { answer(true) }
                pill(card.skipLabel, filled: false) { answer(false) }
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
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

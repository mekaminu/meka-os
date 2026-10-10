@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → School on the Mac (school rhythm, slice 1), like the Fold's SchoolPane: Rex's and Logan's school year,
/// typed once, one line each ("INSET 27 Oct", "Half term 26–30 Oct", "Rex PE Tue"), in three groups — days off, dates
/// and every week — each row with Remove. Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line under the field cross-fade (the "couldn't read"
/// line lit in the accent); rows stagger in and arrive or leave on the expand spring; Add gives a light haptic, Remove
/// a tick. Reduce Motion: cross-fades.
struct SchoolSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: String?

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
/// Fold's DateNightCardView: "In 7 days · from 19:00" · "Date night · Fri 23 Oct" · Booked · Skip this one.
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
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.schoolCovers.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.dateNightNudge?.day)
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

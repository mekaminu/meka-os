@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → People on the Mac (People, slice 2), like the Fold's PeoplePane: one row per person in a promise either
/// way or a birthday in Gifts — the name, "You promised 2 · Waiting for 1", the next birthday and the last contact MEKA
/// has seen. Click a row to open it: what you promised (each with Kept) and what you're waiting for from them. Only
/// id Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary cross-fades; rows stagger in and arrive or leave on the expand
/// spring; an opened row grows on the expand spring; a lit row's line is in the accent; Kept gives a light haptic.
/// Reduce Motion: cross-fades.
struct PeopleSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The row that's open, if any.
    @State private var open: String?

    var body: some View {
        let v = model.people
        let rows = v?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("People").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? PeopleRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(rows.enumerated()), id: \.element.key) { i, row in
                        PersonRowView(row: row, palette: palette, isOpen: open == row.key) {
                            open = open == row.key ? nil : row.key
                        }
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        .staggeredAppear(2 + i)
                    }
                }
                .padding(.top, MekaSpace.xs)
                .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.key))
                .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            }
            .frame(maxHeight: 400)
            Text("Promises are made in Lists → Promises, birthdays in Gifts. Last contact comes from the messages MEKA has already seen; nothing new is read.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
}

private struct PersonRowView: View {
    @Environment(CoreModel.self) private var model
    let row: PersonRow
    let palette: MekaPalette
    let isOpen: Bool
    let onToggle: () -> Void

    var body: some View {
        let promised = row.promised
        let waiting = row.waiting
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    if let owed = row.owedLine {
                        Text(owed).font(MekaType.caption).foregroundStyle(row.lit ? palette.accent : palette.textSecondary)
                    }
                    if let birthday = row.birthday {
                        Text(birthday).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    if let last = row.lastContact {
                        Text(last).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            if isOpen && (!promised.isEmpty || !waiting.isEmpty) {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if !promised.isEmpty {
                        label("YOU PROMISED")
                        ForEach(promised, id: \.id) { p in
                            HStack {
                                owedText(p)
                                Spacer()
                                Button("Kept") { model.keptPromise(p.id) }
                                    .buttonStyle(MekaPressStyle())
                                    .font(MekaType.itemMeta)
                                    .foregroundStyle(palette.accent)
                                    .accessibilityLabel("Kept: \(p.title)")
                            }
                        }
                    }
                    if !waiting.isEmpty {
                        label("WAITING FOR")
                        ForEach(waiting, id: \.id) { w in owedText(w) }
                    }
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

    private func label(_ text: String) -> some View {
        Text(text)
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
    }

    private func owedText(_ item: PersonOwed) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(item.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
            Text(item.meta).font(MekaType.caption).foregroundStyle(item.due ? palette.accent : palette.textSecondary)
        }
    }
}

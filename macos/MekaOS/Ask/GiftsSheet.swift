@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Gifts on the Mac (gift planner, slice 1), like the Fold's GiftsPane: each birthday or anniversary
/// typed once ("Mum's birthday 3 Nov", "Wedding anniversary 20 Aug 2015", "… delivery 5 days"), a row per occasion,
/// soonest first, with its day, age and order-by day (lit within two weeks while the gift isn't sorted). Click a row to
/// open it: its gift ideas (each with ×), a field to note another, Gift sorted / Not sorted yet, and Remove. Only
/// Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line under the field cross-fade (a refused line lit in
/// the accent); rows stagger in and arrive or leave on the expand spring; an opened row grows on the expand spring;
/// Add and Gift sorted give a light haptic (a tick when refused), Remove and × a tick. Reduce Motion: cross-fades.
struct GiftsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: CoreModel.GiftSaid?
    /// The row that's open, if any.
    @State private var open: String?

    var body: some View {
        let v = model.gifts
        let rows = v?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Gifts").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? GiftRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            TextField(GiftRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { said = await model.addOccasion(typed) }
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
                        Text("BIRTHDAYS AND ANNIVERSARIES")
                            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.m)
                            .staggeredAppear(3)
                    }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        GiftRowView(row: row, palette: palette, isOpen: open == row.id) {
                            open = open == row.id ? nil : row.id
                        }
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        .staggeredAppear(4 + i)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
                .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            }
            .frame(maxHeight: 360)
            Text(GiftRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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

private struct GiftRowView: View {
    @Environment(CoreModel.self) private var model
    let row: GiftRow
    let palette: MekaPalette
    let isOpen: Bool
    let onToggle: () -> Void
    @State private var idea = ""

    var body: some View {
        let ideas = row.ideas
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(row.note).font(MekaType.caption).foregroundStyle(row.noteLit ? palette.accent : palette.textSecondary)
                    if !isOpen && !ideas.isEmpty {
                        Text("Ideas: " + ideas.map(\.text).joined(separator: ", "))
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            if isOpen {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("GIFT IDEAS")
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                    if ideas.isEmpty {
                        Text("None yet").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    ForEach(ideas, id: \.id) { i in
                        HStack {
                            Text(i.text).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Spacer()
                            Button("×") { model.removeGiftIdea(i.id) }
                                .buttonStyle(MekaPressStyle())
                                .foregroundStyle(palette.textTertiary)
                                .accessibilityLabel("Remove the idea \(i.text)")
                        }
                    }
                    TextField(GiftRules.shared.IDEA_HINT, text: $idea)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit {
                            let typed = idea
                            idea = ""
                            model.addGiftIdea(row.id, typed)
                        }
                    HStack(spacing: MekaSpace.m) {
                        Button(row.sorted ? "Not sorted yet" : "Gift sorted") { model.setGiftSorted(row.id, !row.sorted) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemMeta)
                            .foregroundStyle(palette.accent)
                        Button("Remove") { model.removeOccasion(row.id) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.caption)
                            .foregroundStyle(palette.textTertiary)
                            .accessibilityLabel("Remove \(row.title)")
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
}

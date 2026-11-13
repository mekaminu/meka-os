@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Gifts on the Mac in the page shape (every page under Ask in one shape, slice 3; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's GiftsPane: the title with its ⓘ, one gold + Add (A birthday ·
/// An anniversary, each a field: "Mum's birthday 3 Nov", "Wedding 20 Aug 2015", "… delivery 5 days"), three numbers
/// (the next occasion, what to sort soon, the ideas noted), then Coming up and Birthdays and anniversaries. A row opens
/// that occasion (its ideas, each with ×, a field for another, To a day…, Gift sorted / Not sorted yet and Remove) with
/// ‹ Gifts back; See all lists every occasion. Only Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and the ⓘ unfold on the expand spring;
/// an occasion opens and closes with a cross-fade and slide; the added line cross-fades (a refused line lit in the
/// accent); an add and Gift sorted give a light haptic (a tick when refused), Remove and × a tick. Reduce Motion:
/// cross-fades.
struct GiftsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: CoreModel.GiftSaid?
    /// The occasion that's open, if any (its id).
    @State private var open: String?
    @State private var showAll = false

    var body: some View {
        let rules = GiftsPageRules.shared
        let rows = model.gifts?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let id = open, let row = rows.first(where: { $0.id == id }) {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                GiftOccasionView(row: row, palette: palette) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = false } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(rules.ALL).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rows, id: \.id) { r in
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = r.id; showAll = false } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(r.spoken)
                        }
                    }
                }
                .frame(maxHeight: 400)
                .transition(.opacity)
            } else if let page = model.giftsPage() {
                LifePageHeader(title: page.title, info: "\(page.info)\n\n\(GiftRules.shared.NOTE)", palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); addChoice = nil; said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                Text(said?.line ?? model.gifts?.summary ?? GiftRules.shared.EMPTY_LINE)
                    .font(MekaType.caption).foregroundStyle(said?.refused == true ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            LifePageCardView(card: page.cardAt(index: Int32(i)), palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = true }
                            }, onRow: { r in
                                let id = rules.occasionId(rowKey: r.key)
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = id }
                            })
                            .staggeredAppear(3 + i)
                        }
                    }
                }
                .frame(maxHeight: 420)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = GiftsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    chip(c.label, lit: addChoice == c.key) { addChoice = c.key; said = nil }
                        .help(c.hint)
                }
            }
            if let choice = addChoice {
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(GiftRules.shared.ADD_DESCRIPTION)
                    .onSubmit { submit(choice) }
            }
        }
        .transition(.opacity)
    }

    private func submit(_ choice: String) {
        let typed = GiftsPageRules.shared.entry(choice: choice, text: text)
        guard !typed.isEmpty else { return }
        Task {
            guard let r = await model.addOccasion(typed) else { return }
            said = r
            if !r.refused { text = ""; adding = false; addChoice = nil }
        }
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

/// One occasion: its day and order-by note, its gift ideas (each with ×), a field to note another, To a day…, Gift
/// sorted / Not sorted yet, and Remove ([onRemoved] goes back to the page).
private struct GiftOccasionView: View {
    @Environment(CoreModel.self) private var model
    let row: GiftRow
    let palette: MekaPalette
    let onRemoved: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var idea = ""

    var body: some View {
        let ideas = row.ideas
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(row.note).font(MekaType.caption).foregroundStyle(row.noteLit ? palette.accent : palette.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            Text("GIFT IDEAS")
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xs)
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
            // Week board item (5) part 4: the gift as a task on a day of the Board, up to the day itself.
            if let date = row.day?.int64Value, !row.sorted {
                DateToDayView(kind: .gift, id: row.id, title: row.title, date: date, palette: palette)
            }
            HStack(spacing: MekaSpace.m) {
                Button(row.sorted ? "Not sorted yet" : "Gift sorted") { model.setGiftSorted(row.id, !row.sorted) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                Button("Remove") { model.removeOccasion(row.id); onRemoved() }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(row.title)")
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: ideas.map(\.id))
    }
}

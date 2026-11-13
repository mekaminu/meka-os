@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask → What Navilife knows on the Mac in the page shape (every page under Ask in one shape, slice 13; approved
/// 2026-11-11, docs/design/look/LifePage.dc.html), like the Fold's FactsPane: the title with its ⓘ, one gold + Add
/// (A fact · A fact kept from the AI, each a field: "Car reg: AB12 CDE"), three numbers (facts kept, how many Obi can
/// use, how many kept from the AI), then one card a group (Sizes, Health and care, Car, Home and utilities, Family and
/// school, Other; five rows each, See all for the rest). A fact opens in place with ‹ What Navilife knows back: the
/// fact whole, a field to change it, Copy (the fact alone on the pasteboard), Keep from AI (or Let Navilife use it)
/// and Remove. A line holding a password, PIN or card, bank or ID number is turned away: the line under the field says
/// why in the accent and the words stay to fix. Only Strings and a Bool cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and field and the ⓘ unfold on the
/// expand spring; a fact opens and closes with a cross-fade and slide from the trailing edge; the answer line and a
/// fact's "Kept from AI" cross-fade (a refused line lit in the accent); an add or a Copy gives a light haptic, a
/// refusal, Keep from AI and Remove a tick. Reduce Motion: cross-fades.
struct FactsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    @State private var changeText = ""
    @State private var said: CoreModel.FactSaid?
    /// The fact that's open, if any (its row key, "fact:…").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any: a group's key.
    @State private var showAll: String?

    var body: some View {
        let rules = FactsPageRules.shared
        let v = model.facts ?? FactsView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil; said = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                factDetail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(v.groups.first(where: { $0.group == card })?.label ?? rules.TITLE)
                    .font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card), id: \.key) { r in
                            let key = r.key
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil; changeText = "" } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
                .frame(maxHeight: 400)
                .transition(.opacity)
            } else if let page = model.factsPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if !adding, let said {
                        Text(said.line).font(MekaType.caption)
                            .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            let card = page.cardAt(index: Int32(i))
                            let cardKey = card.key
                            LifePageCardView(card: card, palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = cardKey }
                            }, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; said = nil; changeText = "" }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(FactRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
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

    /// An open fact as it is now: its group, the fact whole, a field to change it, Copy, Keep from AI and Remove.
    @ViewBuilder
    private func factDetail(_ key: String, _ v: FactsView) -> some View {
        let rules = FactsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let row = rules.fact(view: v, key: key) {
                let id = row.id
                let label = row.label
                let kept = row.keptFromAi
                Text(label).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(rules.groupLabel(row: row)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text(row.valueShown).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.value)
                    .accessibilityLabel(row.spoken)
                if let line = row.aiLine {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .contentTransition(.opacity)
                }
                TextField(rules.CHANGE_HINT, text: $changeText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        guard let typed = rules.changeText(row: row, typed: changeText) else { return }
                        let m = model
                        Task {
                            guard let r = await m.addFact(typed) else { return }
                            said = r
                            if !r.refused { changeText = "" }
                        }
                    }
                if let said {
                    Text(said.line).font(MekaType.caption)
                        .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
                HStack(spacing: MekaSpace.m) {
                    Button(FactRules.shared.COPY_BUTTON) { copy(row) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel(row.copySpoken)
                    Button(row.aiButton) { model.setFactAi(id, use: kept) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityLabel("\(row.aiButton): \(label)")
                    Button("Remove") {
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                        model.removeFact(id)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(label)")
                }
            } else {
                // Removed (here or on the Fold) while it was open.
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Copy: the fact alone goes on the pasteboard (the row's own String) and the line under it says what was copied.
    private func copy(_ row: FactRow) {
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(row.copyText, forType: .string)
        MekaHaptics.light()
        said = CoreModel.FactSaid(line: FactRules.shared.copiedLine(row: row), refused: false)
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = FactsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    let key = c.key
                    chip(c.label, lit: addChoice == key) { addChoice = key; said = nil }
                        .help(c.hint)
                }
            }
            if let choice = addChoice {
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(rules.spokenHint(choice: choice))
                    .onSubmit { submit(choice) }
                if let said, said.refused {
                    Text(said.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .transition(.opacity)
    }

    private func submit(_ choice: String) {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let kept = FactsPageRules.shared.keepsFromAi(choice: choice)
        let m = model
        Task {
            guard let r = await m.addFact(typed, keptFromAi: kept) else { return }
            said = r
            // A refused line keeps its words to fix.
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

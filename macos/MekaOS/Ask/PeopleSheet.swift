@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → People on the Mac in the page shape (every page under Ask in one shape, slice 2; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's PeoplePane: the title with its ⓘ, one gold + Add (A birthday ·
/// Something I promised · Something I'm waiting for, each a field), three numbers (the next birthday, what's due or to
/// chase, how many people), then Coming up and Everyone. A row opens that person (what he promised, each with Kept, and
/// what he's waiting for) with ‹ People back; Everyone's See all lists every person. Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and the ⓘ unfold on the expand spring;
/// a person opens and closes with a cross-fade and slide; the added line cross-fades; Kept gives a light haptic.
/// Reduce Motion: cross-fades.
struct PeopleSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    @State private var said: String?
    @State private var refused = false
    /// The person that's open, if any (their People key).
    @State private var person: String?
    @State private var showAll = false

    var body: some View {
        let rules = PeoplePageRules.shared
        let rows = model.people?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = person, let row = rows.first(where: { $0.key == key }) {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { person = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                PersonDetailView(row: row, palette: palette)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = false } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(rules.EVERYONE).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rows, id: \.key) { r in
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { person = r.key; showAll = false } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    if let line = r.owedLine ?? r.lastContact ?? r.birthday {
                                        Text(line).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
                                    }
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
            } else if let page = model.peoplePage() {
                LifePageHeader(title: page.title, info: "\(page.info)\n\n\(rules.PRIVACY)", palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); addChoice = nil; refused = false }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                Text(said ?? model.people?.summary ?? PeopleRules.shared.EMPTY_LINE)
                    .font(MekaType.caption).foregroundStyle(refused ? palette.accent : palette.textSecondary)
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
                                let key = rules.personKey(rowKey: r.key)
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { person = key }
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
        .animation(MekaMotion.expand(reduced: reduceMotion), value: person)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = PeoplePageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    chip(c.label, lit: addChoice == c.key) { addChoice = c.key; refused = false }
                        .help(c.hint)
                }
            }
            if let choice = addChoice {
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submit(choice) }
            }
        }
        .transition(.opacity)
    }

    private func submit(_ choice: String) {
        let rules = PeoplePageRules.shared
        let typed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !typed.isEmpty else { return }
        if choice == rules.ADD_BIRTHDAY {
            Task {
                guard let r = await model.addOccasion(typed) else { return }
                said = r.line
                refused = r.refused
                if !r.refused { text = ""; adding = false; addChoice = nil }
            }
            return
        }
        guard let owed = rules.readOwed(text: typed) else {
            said = rules.NOT_READ
            refused = true
            MekaHaptics.tick()
            return
        }
        let what = owed.what
        let who = owed.who
        let line = rules.addedLine(choice: choice, o: owed)
        if choice == rules.ADD_PROMISE {
            model.addPromise(what, to: who, byInDays: nil)
        } else {
            model.addWaiting(what, who: who, chaseInDays: Int(ListRules.shared.DEFAULT_CHASE_DAYS))
        }
        MekaHaptics.light()
        said = line
        refused = false
        text = ""
        adding = false
        addChoice = nil
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

/// One person: which list has them, what's owed, the birthday and the last contact, then what he promised (each with
/// Kept) and what he's waiting for.
private struct PersonDetailView: View {
    @Environment(CoreModel.self) private var model
    let row: PersonRow
    let palette: MekaPalette

    var body: some View {
        let promised = row.promised
        let waiting = row.waiting
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.name).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                if let listed = row.listed {
                    Text(listed).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
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
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
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
            if promised.isEmpty && waiting.isEmpty {
                Text(PeoplePageRules.shared.NOTHING_OWED).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
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

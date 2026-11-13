@preconcurrency import MekaKit
import SwiftUI

/// Ask → Dinners on the Mac in the page shape (every page under Ask in one shape, slice 8; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's MealsPane: the title with its ⓘ, one gold + Add (A favourite
/// dinner, a field: "Chilli: mince, beans, rice"), three numbers (dinners planned, ingredients to add to shopping,
/// favourites), then This week (a row a day, tonight's in brass), Shopping (while a dinner is planned) and Favourites.
/// A row opens in place with ‹ Dinners back: a day's favourites to pick (or None), Shopping's gold button with Undo,
/// or a favourite's ingredients, the week's days to plan it on and Remove. Only Strings and an Int64 cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's field and the ⓘ unfold on the expand spring; a
/// row opens and closes with a cross-fade and slide from the trailing edge; a day's dinner and the shopping line
/// cross-fade; a pick gives a tick haptic, Add to shopping a light one. Reduce Motion: cross-fades.
struct MealsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: String?
    /// The row that's open, if any (its key: "day:…", "shop" or "meal:…").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?

    var body: some View {
        let rules = MealsPageRules.shared
        let v = model.meals ?? MealPlanView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                rowDetail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(card).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card), id: \.key) { r in
                            let key = r.key
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Text([r.line, r.whenText].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
            } else if let page = model.mealsPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    let summary = rules.summary(view: v)
                    Text(summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: summary)
                    if !adding, let line = said ?? model.mealsShoppedLine {
                        Text(line).font(MekaType.caption)
                            .foregroundStyle(line == MealRules.shared.NOT_READ ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
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
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; said = nil }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(MealRules.shared.SHARED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
        .onDisappear { model.forgetMealsShopped() }
    }

    /// What an open row shows: a day as it is now, Shopping, or one favourite while it's still on the list.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: MealPlanView) -> some View {
        let rules = MealsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let day = rules.day(view: v, key: key) {
                let d = day.day
                Text(day.label).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(day.title ?? MealRules.shared.NOT_PLANNED).font(MekaType.body)
                    .foregroundStyle(day.title == nil ? palette.textTertiary : palette.textPrimary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: day.title)
                if day.title != nil {
                    Text(day.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                if v.favourites.isEmpty {
                    Text(rules.PICK_FIRST).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                } else {
                    choice(MealRules.shared.NONE_CHOICE, lit: day.mealId == nil) { pick(d, nil) }
                    ForEach(v.favourites, id: \.id) { f in
                        let id = f.id
                        choice(f.title, lit: day.mealId == id) { pick(d, id) }
                    }
                }
            } else if key == rules.KEY_SHOP {
                Text(rules.SHOPPING).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                if let label = v.shoppingLabel {
                    Button(label) { model.mealsToShopping() }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(palette.accent, in: Capsule())
                }
                let line = model.mealsShoppedLine ?? v.shoppingLine
                Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
                if model.mealsShoppedCanUndo {
                    Button("Undo") { model.undoMealsShopping() }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .transition(.opacity)
                }
            } else if let meal = rules.meal(view: v, key: key) {
                let id = meal.id
                Text(meal.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(meal.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(rules.PLAN_FOR).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                ForEach(v.week, id: \.day) { d in
                    let day = d.day
                    let on = rules.isPlanned(view: v, mealId: id, day: day)
                    choice(d.label, lit: on) { model.planMeal(day: day, mealId: on ? nil : id) }
                }
                Button("Remove") {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                    model.removeMeal(id)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(meal.title)")
            } else {
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mealsShoppedCanUndo)
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// One pick in a row's detail: lit (the accent, a check) when it's the one chosen.
    private func choice(_ title: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).font(MekaType.body).foregroundStyle(lit ? palette.accent : palette.textPrimary)
                Spacer()
                if lit { Image(systemName: "checkmark").font(MekaType.caption).foregroundStyle(palette.accent) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }

    /// A day's pick: planned, then back to the page.
    private func pick(_ day: Int64, _ mealId: String?) {
        model.planMeal(day: day, mealId: mealId)
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            if page.addChoiceCount > 0 {
                Text(page.addChoiceAt(index: 0).label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            TextField(MealRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { submit() }
            if let said, said == MealRules.shared.NOT_READ {
                Text(said).font(MekaType.caption).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .transition(.opacity)
    }

    private func submit() {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let m = model
        Task {
            guard let r = await m.addMeal(typed) else { return }
            said = r
            if r != MealRules.shared.NOT_READ { text = ""; adding = false }
        }
    }
}

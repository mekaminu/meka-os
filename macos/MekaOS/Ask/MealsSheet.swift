@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Dinners on the Mac (meal plan → shopping, slice 1), like the Fold's MealsPane: the week's dinners a
/// day at a time (each day's menu picks one of the favourites or None), "Add 9 ingredients to shopping" putting the
/// week's ingredients on the shared list once each (with Undo while the sheet is open), and the favourites typed once
/// ("Chilli: mince, beans, rice"), each with Remove. Only Strings and an Int64 cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary, a day's dinner and the shopping line cross-fade; rows stagger
/// in; favourites arrive and leave on the expand spring; picking gives a tick haptic, Add to shopping a light one.
/// Reduce Motion: cross-fades.
struct MealsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: String?

    var body: some View {
        let v = model.meals
        let week = v?.week ?? []
        let favourites = v?.favourites ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Dinners").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? MealRules.shared.NO_FAVOURITES).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if !week.isEmpty {
                        label("This week").staggeredAppear(2)
                        ForEach(Array(week.enumerated()), id: \.element.day) { i, row in
                            MealDayRowView(row: row, favourites: favourites, palette: palette) { model.planMeal(day: row.day, mealId: $0) }
                                .staggeredAppear(3 + i)
                        }
                        shopping(v).padding(.top, MekaSpace.xs).staggeredAppear(10)
                    }
                    label("Favourites").padding(.top, MekaSpace.xs).staggeredAppear(11)
                    TextField(MealRules.shared.ADD_HINT, text: $text)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit {
                            let typed = text
                            text = ""
                            Task { said = await model.addMeal(typed) }
                        }
                        .staggeredAppear(12)
                    if let said {
                        Text(said).font(MekaType.caption)
                            .foregroundStyle(said == MealRules.shared.NOT_READ ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                    ForEach(Array(favourites.enumerated()), id: \.element.id) { i, row in
                        MealRowView(row: row, palette: palette) { model.removeMeal(row.id) }
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                            .staggeredAppear(13 + i)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: favourites.map(\.id))
            }
            .frame(maxHeight: 480)
            Text(MealRules.shared.SHARED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
        .onDisappear { model.forgetMealsShopped() }
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
    }

    @ViewBuilder
    private func shopping(_ v: MealPlanView?) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let label = v?.shoppingLabel {
                Button(label) { model.mealsToShopping() }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
            }
            let line = model.mealsShoppedLine ?? v?.shoppingLine ?? MealRules.shared.SHOPPING_HINT
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
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mealsShoppedCanUndo)
    }
}

private struct MealDayRowView: View {
    let row: MealDayRow
    let favourites: [MealRow]
    let palette: MekaPalette
    let choose: (String?) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            Text(row.label).font(MekaType.caption)
                .foregroundStyle(row.label == "Tonight" ? palette.accent : palette.textSecondary)
                .frame(width: 96, alignment: .leading)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title ?? MealRules.shared.NOT_PLANNED).font(MekaType.body)
                    .foregroundStyle(row.title == nil ? palette.textTertiary : palette.textPrimary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.title)
                if row.title != nil {
                    Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            Spacer()
            Menu(row.title == nil ? "Pick" : "Change") {
                Button(MealRules.shared.NONE_CHOICE) { choose(nil) }
                if !favourites.isEmpty { Divider() }
                ForEach(favourites, id: \.id) { f in
                    Button(f.title) { choose(f.id) }
                }
            }
            .menuStyle(.button).fixedSize()
            .font(MekaType.caption)
            .disabled(favourites.isEmpty)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(row.spoken)
        .mekaHoverLift()
    }
}

private struct MealRowView: View {
    let row: MealRow
    let palette: MekaPalette
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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

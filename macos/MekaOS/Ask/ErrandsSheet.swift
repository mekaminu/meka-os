@preconcurrency import MekaKit
import SwiftUI

/// Ask → Errands on the Mac in the page shape (every page under Ask in one shape, slice 7; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's ErrandsPane: the title with its ⓘ, one gold + Add (Things to do
/// at a place, a field: "Tesco: milk, batteries"), three numbers (errands waiting, places, done this week), then Waiting
/// (a row a place, what's there and how many) and Done lately. A row opens in place with ‹ Errands back: a place's
/// errands with Done and Remove, a done one with Back and Remove. The reminder near a place is the Fold's. Only
/// Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's field and the ⓘ unfold on the expand spring; a
/// row opens and closes with a cross-fade and slide from the trailing edge; errands move on the expand spring as one is
/// ticked off; the answer line cross-fades (a refused line lit in the accent); an add or Done gives a light haptic, a
/// refusal, Back or Remove a tick. Reduce Motion: cross-fades.
struct ErrandsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var text = ""
    @State private var said: CoreModel.ErrandSaid?
    /// The row that's open, if any (its key: "place:…" or "done:…").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?

    var body: some View {
        let rules = ErrandsPageRules.shared
        let v = model.errands ?? ErrandView.companion.EMPTY
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
            } else if let page = model.errandsPage() {
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
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(ErrandRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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

    /// What an open row shows: a place's errands as they are now, or one errand wherever it has moved to.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: ErrandView) -> some View {
        let rules = ErrandsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let place = rules.place(view: v, key: key) {
                Text(place.place).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                ForEach(place.rows, id: \.id) { row in
                    let id = row.id
                    ErrandRowView(row: row, palette: palette, primary: "Done",
                                  onPrimary: { model.errandDone(id, done: true) },
                                  onRemove: { model.removeErrand(id) })
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: place.rows.map(\.id))
            } else if let errand = rules.errand(view: v, key: key) {
                let id = errand.id
                let isDone = errand.done
                Text(errand.shown).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                ErrandRowView(row: errand, palette: palette, primary: isDone ? "Back" : "Done",
                              onPrimary: { model.errandDone(id, done: !isDone) },
                              onRemove: {
                                  withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                                  model.removeErrand(id)
                              })
            } else {
                // Everything there was ticked off or removed while it was open.
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            if page.addChoiceCount > 0 {
                Text(page.addChoiceAt(index: 0).label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            TextField(ErrandRules.shared.HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { submit() }
            if let said, said.refused {
                Text(said.line).font(MekaType.caption).foregroundStyle(palette.accent)
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
            guard let r = await m.addErrand(typed) else { return }
            said = r
            if !r.refused { text = ""; adding = false }
        }
    }
}

private struct ErrandRowView: View {
    let row: ErrandRow
    let palette: MekaPalette
    let primary: String
    let onPrimary: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.shown).font(MekaType.body)
                    .foregroundStyle(row.done ? palette.textSecondary : palette.textPrimary)
                    .lineLimit(2)
                if let meta = row.meta {
                    Text(meta).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(primary, action: onPrimary)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("\(primary): \(row.shown)")
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.shown)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

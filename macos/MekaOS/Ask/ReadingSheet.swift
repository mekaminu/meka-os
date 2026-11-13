@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Reading on the Mac in the page shape (every page under Ask in one shape, slice 6; approved
/// 2026-11-11, docs/design/look/LifePage.dc.html), like the Fold's ReadingPane: the title with its ⓘ, one gold + Add
/// (A book, podcast or course · A reading goal, each a field), three numbers (on the go, up next, the year's goal or
/// what finished lately), then On the go (how far on the right), Up next, Finished lately and Reading goal. A row opens
/// in place with ‹ Reading back: a book's how far and bar, the how-far field while on the go, Start / Finished / Put
/// down / Again / Pick up (it stays open as the book moves, with Undo) and Remove; the goal's books a year and Clear.
/// Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and the ⓘ unfold on the expand spring;
/// a row opens and closes with a cross-fade and slide from the trailing edge; the answer line cross-fades (a refused
/// line lit in the accent); a bar grows to its new length on the expand spring; the goal's pace line turns brass while
/// on pace. Reduce Motion: cross-fades, the bar jumps.
struct ReadingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    @State private var progressText = ""
    @State private var goalText = ""
    @State private var said: CoreModel.ReadingSaidLine?
    /// The row that's open, if any (its key: "item:…" or "set:goal").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?

    var body: some View {
        let rules = ReadingPageRules.shared
        let v = model.reading ?? ReadingView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil; said = nil } }
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
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil; said = nil } } label: {
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
            } else if let page = model.readingPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); addChoice = nil; said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if let month = v.month {
                        Text(month).font(MekaType.caption).foregroundStyle(palette.accent).transition(.opacity)
                    }
                    if !adding, let said { saidLine(said) }
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
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { said = nil; open = key }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(ReadingRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.month)
    }

    /// What an open row shows: a book, podcast or course wherever it has moved to, or the reading goal.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: ReadingView) -> some View {
        let rules = ReadingPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let item = rules.item(view: v, key: key) {
                let id = item.id
                Text(item.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(item.meta).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(item.progress).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: item.progress)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(item.spoken)
                if let f = item.fraction?.doubleValue {
                    ProgressBar(fraction: f, palette: palette)
                        .animation(MekaMotion.expand(reduced: reduceMotion), value: f)
                }
                if rules.takesProgress(row: item) {
                    TextField(ReadingRules.shared.PROGRESS_HINT, text: $progressText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit {
                            let t = progressText
                            progressText = ""
                            act { m in await m.readingProgress(id, t) }
                        }
                    Text(rules.PROGRESS_NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: MekaSpace.xs) {
                    ForEach(rules.acts(row: item), id: \.key) { a in
                        let finish = a.key == rules.ACT_FINISH
                        let down = a.key == rules.ACT_DOWN
                        Button(a.label) {
                            act { m in
                                if finish { return await m.finishReading(id) }
                                if down { return await m.putDownReading(id) }
                                return await m.startReading(id)
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("\(a.label): \(item.title)")
                    }
                    Button("Remove") {
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                        act { m in await m.removeReading(id) }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(item.title)")
                }
                if let said { saidLine(said) }
            } else if key == rules.KEY_GOAL {
                Text(rules.GOAL).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                if let g = v.goal {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        HStack {
                            Text(g.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Spacer()
                            Text(g.count).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                        }
                        Text(g.line).font(MekaType.caption)
                            .foregroundStyle(g.lit ? palette.accent : palette.textSecondary)
                            .contentTransition(.opacity)
                        ProgressBar(fraction: Double(g.fraction), palette: palette)
                            .animation(MekaMotion.expand(reduced: reduceMotion), value: g.fraction)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(g.spoken)
                    .transition(.opacity)
                }
                TextField(ReadingGoalRules.shared.HINT, text: $goalText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        let t = goalText
                        goalText = ""
                        act { m in await m.setReadingGoal(t) }
                    }
                Text(ReadingGoalRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                if v.goalTarget > 0 {
                    Button("Clear the goal") { act { m in await m.setReadingGoal("none") } }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                }
                if let said {
                    Text(said.line).font(MekaType.caption)
                        .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                // Removed while it was open.
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = ReadingPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    let key = c.key
                    chip(c.label, lit: addChoice == key) { said = nil; addChoice = key }
                        .help(c.hint)
                }
            }
            if let choice = addChoice {
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
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
        let goal = choice == ReadingPageRules.shared.ADD_GOAL
        let m = model
        Task {
            let answer: CoreModel.ReadingSaidLine?
            if goal { answer = await m.setReadingGoal(typed) } else { answer = await m.addReading(typed) }
            guard let r = answer else { return }
            said = r
            if !r.refused { text = ""; adding = false; addChoice = nil }
        }
    }

    private func saidLine(_ s: CoreModel.ReadingSaidLine) -> some View {
        HStack(spacing: MekaSpace.m) {
            Text(s.line).font(MekaType.caption)
                .foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let token = s.undo {
                Button("Undo") {
                    let m = model
                    Task {
                        let ok = await m.undoReading(token)
                        said = CoreModel.ReadingSaidLine(line: ok ? "Taken back" : "Too late to undo · change it by hand", refused: !ok, undo: nil)
                    }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
            }
        }
        .contentTransition(.opacity)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: s)
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

    /// Runs one reading command on the model (only Strings cross to the core) and shows what it said.
    private func act(_ body: @escaping (CoreModel) async -> CoreModel.ReadingSaidLine?) {
        let m = model
        Task {
            if let r = await body(m) { said = r }
        }
    }
}

private struct ProgressBar: View {
    let fraction: Double
    let palette: MekaPalette

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(palette.surfaceRaised)
                Capsule().fill(palette.accent).frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 4)
        .padding(.vertical, MekaSpace.xxs)
        .accessibilityHidden(true)
    }
}

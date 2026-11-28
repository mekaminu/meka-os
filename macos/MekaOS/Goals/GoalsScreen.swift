@preconcurrency import MekaKit
import SwiftUI

/// GOALS on the Mac (build plan M1): fasting (`FastingSection`), habits with this week's pace and streaks, and goals with progress, synced with
/// the Fold. A habit's circle pops with a spring when ticked and its streak number rolls; goal bars fill on appear.
/// Clicking a row unfolds its settings (menus, as rule 7 allows). Nothing is ticked for you. Reduce Motion: cross-fades.
struct GoalsScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    @State private var open: String?

    static let timings: [HabitTiming] = [.morning, .afternoon, .evening, .anytime]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text("Goals")
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .staggeredAppear(0)
                Text(model.goals?.paceLine ?? "Add a habit and Navilife keeps count, and makes room in your plan when one falls behind.")
                    .font(MekaType.itemMeta)
                    .foregroundStyle((model.goals?.behind ?? 0) > 0 ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(1)
                // One-tap course import: "Add <course> · … lessons?" after a course link was opened (asks first).
                if model.studyImportText != nil, let offer = model.studyImport {
                    StudyImportCard(offer: offer, palette: palette)
                        .padding(.bottom, MekaSpace.m)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }

                SectionLabel("Fasting", palette).staggeredAppear(2)
                FastingSection(palette: palette).padding(.bottom, MekaSpace.l).staggeredAppear(2)
                // The Habits screen (approved 2026-11-11): the day strip, lists, Do and Avoid, done → missed → clear.
                HStack(alignment: .firstTextBaseline) {
                    SectionLabel("Habits", palette)
                    Spacer()
                    Button("Open Habits ›") { MekaHaptics.light(); model.showHabits = true }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .accessibilityHint("Mark each habit done or missed, and earlier days")
                }
                .staggeredAppear(2)
                ForEach(model.goals?.habits ?? [], id: \.id) { h in
                    HabitRowView(habit: h, goals: model.goals?.goals ?? [], expanded: open == h.id, palette: palette) { toggle(h.id) }
                }
                AddHabitRow(palette: palette).padding(.top, MekaSpace.m)
                // A hand-made gym/workout/training habit: book it in place, keeping its ticks (Fold review 13:45, item 2).
                if let offer = model.goals?.bookOffer {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Button(SessionRules.shared.bookOfferLabel(title: offer.title)) {
                            model.letMekaBook(offer.id)
                            open = offer.id
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                        Text(SessionRules.shared.BOOK_OFFER_CAPTION)
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    .padding(.top, MekaSpace.m)
                    .transition(.opacity)
                }
                // The Gym: one click adds it with its sessions booked (offered until a booked or gym-named habit exists).
                if model.goals?.offersAddGym ?? true {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Button("Add Gym") { model.addGym() }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                        Text("Three times a week, evenings, an hour: Navilife books the sessions around your calendar and work, and rebooks a missed one.")
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    .padding(.top, MekaSpace.m)
                    .transition(.opacity)
                }

                // Money manager (15): the money challenges as streaks beside the habits, no amounts; a click opens Money (Touch ID).
                if let streaks = model.goals?.money {
                    MoneyStreaksSection(streaks: streaks, palette: palette) { model.showMoney = true }
                        .padding(.top, MekaSpace.xl)
                        .transition(.opacity)
                }

                // Reading and learning (slice 4): the reading goal with its pace; a click opens Reading.
                if let reading = model.goals?.reading {
                    ReadingGoalSection(goal: reading, palette: palette) { model.showReading = true }
                        .padding(.top, MekaSpace.xl)
                        .transition(.opacity)
                }

                SectionLabel("Goals", palette).padding(.top, MekaSpace.xl).staggeredAppear(3)
                // Goals, properly, item 6: last month looked back on, for the first week of a month.
                if let month = model.goals?.month {
                    Text(month.line)
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                        .padding(.bottom, MekaSpace.xs)
                        .accessibilityLabel("Last month, \(month.line)")
                        .staggeredAppear(3)
                }
                let goals = model.goals?.goals ?? []
                if goals.isEmpty {
                    Text("Say what you're working towards. Link habits and tasks to a goal and its progress counts itself.")
                        .font(MekaType.body).foregroundStyle(palette.textSecondary).padding(.vertical, MekaSpace.xs)
                }
                ForEach(goals, id: \.id) { g in
                    GoalRowView(goal: g, all: goals, expanded: open == g.id, palette: palette) { toggle(g.id) }
                        // A goal under a bigger one sits indented beneath it (year → month → week).
                        .padding(.leading, MekaSpace.l * CGFloat(min(Int(g.depth), 3)))
                }
                if let finished = model.goals?.finishedGoals, finished > 0 {
                    Text("\(finished) finished").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                AddGoalRow(palette: palette).padding(.top, MekaSpace.m)
                // Study plan, slice 1: courses with their modules and lessons, under the goals.
                StudySection(palette: palette).padding(.top, MekaSpace.xl)
            }
            .frame(maxWidth: 720, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutterWide)
            .padding(.vertical, MekaSpace.xl)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: rowIDs)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: model.studyImport == nil)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.background)
        .onAppear { takeOpenItem() }
        .onChange(of: model.openItem) { takeOpenItem() }
    }

    /// A search result opened here (a goal or a habit): unfold its row.
    private func takeOpenItem() {
        guard let item = model.openItem, SearchNav.destination(item.target) == .goals else { return }
        open = item.id
        model.openItem = nil
    }

    private var rowIDs: [String] {
        guard let g = model.goals else { return [] }
        return g.habits.map(\.id) + g.goals.map(\.id)
    }

    private func toggle(_ id: String) { open = open == id ? nil : id }
}

/// A habit: tick circle, title, pace, this week's dots and a rolling streak. Clicking the text unfolds its settings.
private struct HabitRowView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let habit: HabitItem
    let goals: [GoalItem]
    let expanded: Bool
    let palette: MekaPalette
    let toggle: () -> Void
    private var R: GoalHabitChoiceRules { GoalHabitChoiceRules.shared }

    /// "How often · 5 a week" with a menu of its choices, each "3 a week · 3 days Monday to Sunday…".
    private func choiceMenu(_ row: NotifyChoiceRow, _ sheet: NotifyChoiceSheet, pick: @escaping (String) -> Void) -> some View {
        Menu("\(row.title) · \(row.value)") {
            ForEach(sheet.choices, id: \.name) { c in
                Button("\(c.lit ? "✓ " : "")\(c.label) · \(c.line)") { pick(c.name) }
            }
        }
        .menuStyle(.button).fixedSize()
        .help(sheet.note)
        .accessibilityLabel(row.spoken)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .top, spacing: MekaSpace.m) {
                Button { model.setHabitDone(habit.id, !habit.doneToday) } label: {
                    TickRingView(done: habit.doneToday, palette: palette)
                        .frame(width: 22, height: 22)
                        .contentShape(Circle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel((habit.doneToday ? "Untick " : "Tick ") + habit.title + " for today")

                Button(action: toggle) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(habit.title).font(MekaType.itemTitle)
                            .foregroundStyle(habit.doneToday ? palette.textSecondary : palette.textPrimary)
                        Text(habit.meta).font(MekaType.itemMeta)
                            .foregroundStyle(habit.pace == .behind ? palette.accent : palette.textSecondary)
                        // A booked habit's week: "Booked Today 17:45 · Thu 17:45".
                        if let line = habit.sessionLine {
                            Text(line).font(MekaType.caption).foregroundStyle(palette.accent).contentTransition(.opacity)
                        }
                        HStack(spacing: MekaSpace.xs) {
                            if habit.weekly {
                                WeekSlotsView(filled: Int(habit.slotsFilled), total: Int(habit.weekTarget),
                                              week: habit.week.map(\.boolValue), palette: palette, reduceMotion: reduceMotion)
                            } else {
                                HStack(spacing: 3) { // rhythm: ok (the week's dots are one mark)
                                    ForEach(Array(habit.week.enumerated()), id: \.offset) { _, on in
                                        Circle().fill(on.boolValue ? palette.accent : palette.surfaceRaised).frame(width: 6, height: 6)
                                    }
                                }
                                .accessibilityLabel("\(habit.week.filter { $0.boolValue }.count) days ticked this week")
                            }
                            if habit.streak >= 2 {
                                HStack(spacing: 0) {
                                    Text("\(habit.streak)")
                                        .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(habit.streak)))
                                    Text("-\(habit.streakUnit) streak")
                                }
                                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .animation(MekaMotion.replan(reduced: reduceMotion), value: habit.streak)
                            }
                        }
                        .padding(.top, MekaSpace.xxs)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityHint(expanded ? "Hides settings" : "Shows settings")
            }
            if expanded {
                // Walk-through week 5, A17: each setting a "How often · 5 a week" menu of the same choices as the
                // Fold's sheet, each saying what it does.
                HStack(spacing: MekaSpace.l) {
                    choiceMenu(R.oftenRow(h: habit), R.oftenSheet(h: habit)) { name in
                        if let n = R.oftenNamed(h: habit, name: name) { model.setHabitTarget(habit.id, n.int32Value) }
                    }
                    choiceMenu(R.whenRow(h: habit), R.whenSheet(h: habit)) { name in
                        if let t = R.whenNamed(h: habit, name: name) { model.setHabitTiming(habit.id, t) }
                    }
                    choiceMenu(R.longRow(h: habit), R.longSheet(h: habit)) { name in
                        if let m = R.longNamed(h: habit, name: name) { model.setHabitMinutes(habit.id, m.int32Value) }
                    }
                    Button("Delete", role: .destructive) { model.deleteHabit(habit.id) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.critical)
                }
                .padding(.leading, 22 + MekaSpace.m)
                .transition(.opacity.combined(with: .move(edge: .top)))
                HStack(spacing: MekaSpace.l) {
                    choiceMenu(R.bookRow(h: habit), R.bookSheet(h: habit)) { name in
                        if let on = R.bookNamed(h: habit, name: name) { model.setHabitBooked(habit.id, on.boolValue) }
                    }
                    if habit.booked {
                        choiceMenu(R.rotationRow(h: habit), R.rotationSheet(h: habit)) { name in
                            if let i = R.rotationNamed(h: habit, name: name) { model.setHabitRotation(habit.id, i.intValue) }
                        }
                    }
                    if !goals.isEmpty {
                        choiceMenu(R.towardsRow(h: habit, goals: goals), R.towardsSheet(h: habit, goals: goals)) { name in
                            if let pick = R.towardsNamed(h: habit, goals: goals, name: name) { model.setHabitGoal(habit.id, pick.goalId) }
                        }
                    }
                }
                .padding(.leading, 22 + MekaSpace.m)
                .transition(.opacity.combined(with: .move(edge: .top)))
                if habit.booked {
                    AppLinkField(habit: habit, palette: palette)
                        .padding(.leading, 22 + MekaSpace.m)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
        .padding(MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(expanded ? palette.surface : .clear))
        .transition(.opacity)
    }
}

/// Gym: the workout app's link ("hevy.com"), so Today's card can open it. Return saves it; a link that isn't a web
/// address says so; Remove clears it. The line under the field cross-fades as it changes.
private struct AppLinkField: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let habit: HabitItem
    let palette: MekaPalette
    @State private var text = ""
    @State private var bad = false

    private var line: String {
        if bad { return "That isn't a web address · try hevy.com" }
        if let name = habit.appName { return "Today's card opens \(name)" }
        return "Today's card can open the app you log workouts in"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.m) {
                TextField("Workout app link · hevy.com (optional)", text: $text)
                    .textFieldStyle(.roundedBorder).font(MekaType.body).frame(maxWidth: 320)
                    .onSubmit { save(text) }
                    .onChange(of: text) { bad = false }
                if habit.appLink != nil {
                    Button("Remove") { save("") }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
            }
            Text(line).font(MekaType.caption).foregroundStyle(bad ? palette.critical : palette.textTertiary)
                .id(line).transition(.opacity)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
        .onAppear { text = habit.appLink ?? "" }
        .onChange(of: habit.appLink) { text = habit.appLink ?? "" }
        // Typing is never lost (TextAutosave): a changed link saves when the settings close, as if Return had been
        // pressed (one that isn't a web address is refused there as on Return, and the old one stays).
        .onDisappear {
            if let link = TextAutosave.shared.pendingAdd(typed: text), link != (habit.appLink ?? "") { save(link) }
        }
    }

    private func save(_ link: String) {
        let id = habit.id
        let value = String(link.prefix(Int(SessionRules.shared.MAX_LINK)))
        guard !value.trimmingCharacters(in: .whitespaces).isEmpty || habit.appLink != nil else { return }
        Task { bad = !(await model.setHabitAppLink(id, value)) }
    }
}

/// A goal setting as "Horizon · This month" with a menu of its choices, each "This quarter · Ends Thu 31 Dec"
/// (walk-through week 5, A17: the same choices as the Fold's sheet).
private struct GoalChoiceMenu: View {
    let row: NotifyChoiceRow
    let sheet: NotifyChoiceSheet
    let pick: (String) -> Void

    var body: some View {
        Menu("\(row.title) · \(row.value)") {
            ForEach(sheet.choices, id: \.name) { c in
                Button("\(c.lit ? "✓ " : "")\(c.label) · \(c.line)") { pick(c.name) }
            }
        }
        .menuStyle(.button).fixedSize()
        .help(sheet.note)
        .accessibilityLabel(row.spoken)
    }
}

/// A goal: title, target, a bar that fills on appear, and how progress is counted.
private struct GoalRowView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var ringScheme
    let goal: GoalItem
    let all: [GoalItem]
    let expanded: Bool
    let palette: MekaPalette
    let toggle: () -> Void
    @State private var shown: Double = 0

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button(action: toggle) {
                HStack(alignment: .top, spacing: MekaSpace.m) {
                    // The progress ring sweeps round on appear and glides to a new value (Goals, properly, slice 2).
                    ZStack {
                        // Walk-through 2026-11-15, A21: the empty ring's track in the bars' ink, seen in Light.
                        Circle().stroke(palette.barTrack(dark: ringScheme == .dark), lineWidth: 4)
                        Circle().trim(from: 0, to: shown)
                            .stroke(palette.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Text("\(goal.progressPct)").font(MekaType.caption).monospacedDigit().foregroundStyle(palette.textSecondary)
                            .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(goal.progressPct)))
                    }
                    .frame(width: 36, height: 36)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(goal.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        if let target = goal.target {
                            Text(target).font(MekaType.body).foregroundStyle(palette.textSecondary)
                        }
                        Text(goal.meta).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        // A numeric goal with a due date: where it stands against the pace (slice 3).
                        if let pace = goal.metric?.paceLine {
                            Text(pace).font(MekaType.caption)
                                .foregroundStyle(pace.hasPrefix("Behind") ? palette.accent : palette.textSecondary)
                        }
                        // The next step, always shown: the first open linked task, else the next milestone, or a prompt.
                        Text(goal.nextLine)
                            .font(MekaType.caption)
                            .foregroundStyle(goal.nextStep != nil ? palette.accent : palette.textTertiary)
                            .contentTransition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(goal.title), \(goal.progressPct) percent, \(goal.meta)")
            .accessibilityHint(expanded ? "Hides actions" : "Shows actions")
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    HStack(spacing: MekaSpace.l) {
                        if !goal.counted {
                            Button("−10%") { model.stepGoal(goal.id, from: goal.progressPct, by: -10) }
                            Button("+10%") { model.stepGoal(goal.id, from: goal.progressPct, by: 10) }
                        }
                        // Walk-through week 5, A17: "Horizon · This month", a menu of the Fold's sheet, each saying when it ends.
                        GoalChoiceMenu(row: GoalSettingChoiceRules.shared.horizonRow(g: goal),
                                       sheet: GoalSettingChoiceRules.shared.horizonSheet(g: goal, today: model.todayEpochDay)) { name in
                            if let h = GoalSettingChoiceRules.shared.horizonNamed(g: goal, name: name),
                               let i = GoalRules.shared.HORIZONS.firstIndex(of: h) { model.setGoalHorizon(goal.id, index: i) }
                        }
                        Button("Finished") { model.finishGoal(goal.id) }
                        Button("Delete", role: .destructive) { model.deleteGoal(goal.id) }.foregroundStyle(palette.critical)
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                    if goal.counted {
                        Text("Progress counts itself from the habits, tasks and goals linked to this goal.")
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    GoalDetailsView(goal: goal, all: all, palette: palette)
                    GoalNumberView(goal: goal, palette: palette)
                    MilestonesView(goal: goal, palette: palette)
                    BreakItDownView(goal: goal, palette: palette)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(expanded ? palette.surface : .clear))
        .transition(.opacity)
        // Progress bars fill on appear (Reduce Motion: shown at once).
        .onAppear {
            if reduceMotion { shown = fraction } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = fraction } }
        }
        .onChange(of: goal.progressPct) { withAnimation(MekaMotion.replan(reduced: reduceMotion)) { shown = fraction } }
    }

    private var fraction: Double { min(1, max(0, Double(goal.progressPct) / 100)) }
}

/// Which of a goal's words is open in its sheet (walk-through week 5, A17).
private enum GoalWords: String, Identifiable {
    case target, why, step
    var id: String { rawValue }
}

/// Goals, properly, slice 1: done-by date, the goal it sits under, why it matters, Add a step and Link a task.
private struct GoalDetailsView: View {
    @Environment(CoreModel.self) private var model
    let goal: GoalItem
    let all: [GoalItem]
    let palette: MekaPalette
    @State private var editing: GoalWords?
    @State private var query = ""
    @State private var found: [MekaTask] = []

    var body: some View {
        let R = GoalSettingChoiceRules.shared
        let today = model.todayEpochDay
        let parents = GoalRules.shared.parentChoices(goals: all, goalId: goal.id)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // Walk-through week 5, A17: "Done by · Mon 30 Nov" and "Under · Get fit", menus of the Fold's sheets.
            HStack(spacing: MekaSpace.l) {
                GoalChoiceMenu(row: R.dueRow(g: goal, today: today), sheet: R.dueSheet(g: goal, today: today)) { name in
                    if let pick = R.dueNamed(g: goal, today: today, name: name) { model.setGoalDue(goal.id, day: pick.day?.int64Value) }
                }
                if R.showsUnder(g: goal, parents: parents) {
                    GoalChoiceMenu(row: R.underRow(g: goal), sheet: R.underSheet(g: goal, parents: parents)) { name in
                        if let pick = R.underNamed(g: goal, parents: parents, name: name) { model.setGoalParent(goal.id, pick.goalId) }
                    }
                }
            }
            .font(MekaType.itemTitle).foregroundStyle(palette.accent)
            // Walk-through week 5, A17: What done looks like and Why it matters are rows opening a sheet with the words
            // kept (Close · Save, Save disabled until they change; blank clears), not open boxes on the page.
            GoalAddRowButton(row: GoalTextRules.shared.targetRow(g: goal), palette: palette) { editing = .target }
            GoalAddRowButton(row: GoalTextRules.shared.whyRow(g: goal), palette: palette) { editing = .why }
            // Walk-through week 5, A17: "+ Add a step" is a row opening its sheet (the step becomes a task linked to the
            // goal), not an open box.
            GoalAddRowButton(row: GoalStepRules.shared.stepRow, palette: palette) { editing = .step }
            TextField("Link a task…", text: $query).textFieldStyle(.roundedBorder)
            ForEach(found, id: \.id) { t in
                Button("+ \(t.title)") { model.setTaskGoal(t.id, goal.id); query = "" }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                    .transition(.opacity)
            }
        }
        .sheet(item: $editing) { kind in
            let T = GoalTextRules.shared
            let id = goal.id
            switch kind {
            case .target:
                LineAddSheet(sheet: T.targetSheet, palette: palette, initial: goal.target ?? "",
                             canAdd: { [saved = goal.target] in T.canSave(typed: $0, saved: saved, max: T.MAX_TARGET) },
                             close: { editing = nil }) { typed in model.setGoalTarget(id, typed) }
            case .why:
                LineAddSheet(sheet: T.whySheet, palette: palette, initial: goal.why ?? "",
                             canAdd: { [saved = goal.why] in T.canSave(typed: $0, saved: saved, max: T.MAX_WHY) },
                             close: { editing = nil }) { typed in model.setGoalWhy(id, typed) }
            case .step:
                let S = GoalStepRules.shared
                LineAddSheet(sheet: S.stepSheet, palette: palette,
                             canAdd: { S.canAddStep(typed: $0) },
                             close: { editing = nil }) { typed in
                    if let t = S.cleaned(typed: typed, max: S.MAX_STEP) { model.addGoalStep(id, t) }
                }
            }
        }
        .task(id: "\(query)|\(goal.tasksTotal)") {
            let q = query
            found = q.trimmingCharacters(in: .whitespaces).isEmpty ? [] : await model.goalLinkCandidates(goal.id, q)
        }
    }
}

/// Goals, properly, slice 2: the goal's milestones with tick rings (a light haptic; they count towards the ring), Remove
/// with "Removed … · Undo" fading in under the list, and "Add a milestone…".
private struct MilestonesView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let goal: GoalItem
    let palette: MekaPalette
    @State private var adding = false
    @State private var removed: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(goal.milestones.isEmpty ? "Milestones" : "Milestones · \(goal.milestonesDone) of \(goal.milestones.count)")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            ForEach(goal.milestones, id: \.id) { m in
                HStack(spacing: MekaSpace.m) {
                    Button { model.setGoalMilestoneDone(m.id, !m.done) } label: {
                        TickRingView(done: m.done, palette: palette).frame(width: 18, height: 18).contentShape(Circle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel((m.done ? "Untick " : "Tick ") + m.title)
                    Text(m.title).font(MekaType.body).foregroundStyle(m.done ? palette.textSecondary : palette.textPrimary)
                    Spacer()
                    Button("Remove") { remove(m.id) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
                .transition(.opacity)
            }
            if let r = removed {
                HStack(spacing: MekaSpace.m) {
                    Text("Removed “\(r)”").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Button("Undo") { model.addGoalMilestone(goal.id, r); removed = nil }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
                .transition(.opacity)
            }
            // Walk-through week 5, A17: "+ Add a milestone" is a row opening its sheet, not an open box; Add stays
            // disabled for a name the goal already has.
            if GoalStepRules.shared.showsMilestoneRow(g: goal) {
                GoalAddRowButton(row: GoalStepRules.shared.milestoneRow(g: goal), palette: palette) { adding = true }
            }
        }
        .sheet(isPresented: $adding) {
            let S = GoalStepRules.shared
            let id = goal.id
            LineAddSheet(sheet: S.milestoneSheet(g: goal), palette: palette,
                         canAdd: { [g = goal] in S.canAddMilestone(typed: $0, g: g) },
                         close: { adding = false }) { typed in
                if let t = S.cleaned(typed: typed, max: S.MAX_MILESTONE) { model.addGoalMilestone(id, t); removed = nil }
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: removed)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: goal.milestones.map(\.id))
    }

    private func remove(_ id: String) {
        Task { removed = await model.removeGoalMilestone(id) }
    }
}

/// Goals, properly, slice 4: **Break it down** (approval only), the Mac twin of the Fold's `BreakItDown`. "✦ Break it
/// down" asks MEKA once; "Thinking it through…" fades in, then the proposed milestones and first steps, each with an
/// empty tick ring to tick (light haptic). Nothing is added until Keep; "Added … · Undo" fades in where they were. Not
/// now fades them away. Reduce Motion: cross-fades only (they're all fades).
private struct BreakItDownView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let goal: GoalItem
    let palette: MekaPalette

    private enum Stage: Equatable {
        case idle, reading
        case offered(milestones: [String], steps: [String], line: String)
        case said(String, undo: Bool)
    }
    @State private var stage: Stage = .idle
    @State private var ticked: Set<String> = []
    @State private var kept: BreakdownKept?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            switch stage {
            case .idle:
                Button("✦ " + GoalBreakdownRules.shared.ACTION) { ask() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                Text(GoalBreakdownRules.shared.HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            case .reading:
                Text(GoalBreakdownRules.shared.READING_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            case .offered(let milestones, let steps, let line):
                Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                if !milestones.isEmpty {
                    Text(GoalBreakdownRules.shared.MILESTONES_LABEL).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    ForEach(milestones, id: \.self) { m in proposal(m, key: "m:" + m) }
                }
                if !steps.isEmpty {
                    Text(GoalBreakdownRules.shared.STEPS_LABEL).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    ForEach(steps, id: \.self) { t in proposal(t, key: "s:" + t) }
                }
                HStack(spacing: MekaSpace.l) {
                    Button(GoalBreakdownRules.shared.keepLabel(ticked: Int32(ticked.count))) {
                        keep(milestones: milestones.filter { ticked.contains("m:" + $0) }, steps: steps.filter { ticked.contains("s:" + $0) })
                    }
                    .disabled(ticked.isEmpty)
                    Button(GoalBreakdownRules.shared.NOT_NOW) { MekaHaptics.tick(); stage = .idle }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
            case .said(let line, let undo):
                HStack(spacing: MekaSpace.m) {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Button(undo ? "Undo" : "OK") {
                        if undo, let k = kept { kept = nil; model.undoBreakdown(k) }
                        stage = .idle
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
            }
        }
        .transition(.opacity)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: stage)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: ticked)
    }

    private func proposal(_ text: String, key: String) -> some View {
        let on = ticked.contains(key)
        return Button {
            MekaHaptics.light()
            if on { ticked.remove(key) } else { ticked.insert(key) }
        } label: {
            HStack(spacing: MekaSpace.m) {
                TickRingView(done: on, palette: palette).frame(width: 18, height: 18)
                Text(text).font(MekaType.body).foregroundStyle(palette.textPrimary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel((on ? "Don't keep " : "Keep ") + text)
    }

    private func ask() {
        MekaHaptics.light()
        stage = .reading
        ticked = []
        let gid = goal.id
        Task {
            switch await model.breakDownGoal(gid) {
            case .offered(let m, let s, let line): stage = .offered(milestones: m, steps: s, line: line)
            case .said(let line): stage = .said(line, undo: false)
            }
        }
    }

    private func keep(milestones: [String], steps: [String]) {
        let gid = goal.id
        Task {
            if let k = await model.keepBreakdown(gid, milestones: milestones, steps: steps) {
                kept = k.isEmpty ? nil : k
                stage = .said(k.line, undo: !k.isEmpty)
            } else {
                stage = .idle
            }
        }
    }
}

/// Goals, properly, slice 3: a numeric goal. The number to reach ("£2,000", "12 books"), then the chart (it draws on
/// from the left; Reduce Motion: at once), "Add to it" (+200, -1, =1200) with Undo, the latest updates with Remove, and
/// a habit whose ticks add 1. Walk-through week 5, A17: Number to reach and Add to it are rows opening sheets
/// (GoalNumberRules), not open boxes; a line that isn't a number keeps its sheet open and says so under it.
private struct GoalNumberView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let goal: GoalItem
    let palette: MekaPalette
    @State private var editing: GoalNumberSheetKind?
    @State private var updateLine: String?
    @State private var undoID: String?

    var body: some View {
        let metric = goal.metric
        let N = GoalNumberRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            GoalAddRowButton(row: N.numberRow(g: goal), palette: palette) { editing = .number }
            if let m = metric {
                // Money manager (6): a £ goal that follows a Money account says so (linked in Money, behind Touch ID).
                if let l = m.moneyLine {
                    Text(l).font(MekaType.caption).foregroundStyle(palette.accent).fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity)
                }
                MetricChartView(metric: m, today: model.todayEpochDay, palette: palette)
                    .frame(height: 48)
                GoalAddRowButton(row: N.addRow(m: m), palette: palette) { editing = .add }
                if let line = updateLine {
                    HStack(spacing: MekaSpace.m) {
                        Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        if let id = undoID {
                            Button("Undo") { model.removeGoalUpdate(id); updateLine = nil; undoID = nil }
                                .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                        }
                    }
                    .transition(.opacity)
                }
                ForEach(m.recent, id: \.id) { e in
                    HStack(spacing: MekaSpace.m) {
                        Text(e.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Button("Remove") { model.removeGoalUpdate(e.id) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                    }
                    .transition(.opacity)
                }
                let habits = model.goals?.habits ?? []
                if !habits.isEmpty {
                    // Walk-through week 5, A17: "Counts a habit · Swim", a menu of the Fold's sheet.
                    GoalChoiceMenu(row: GoalSettingChoiceRules.shared.habitRow(g: goal, habits: habits),
                                   sheet: GoalSettingChoiceRules.shared.habitSheet(g: goal, habits: habits)) { name in
                        if let pick = GoalSettingChoiceRules.shared.habitNamed(g: goal, habits: habits, name: name) {
                            model.setGoalNumberHabit(goal.id, pick.habitId)
                        }
                    }
                    .font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
                Button("Stop counting a number") { stopCounting() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
            }
        }
        .sheet(item: $editing) { kind in
            let gid = goal.id
            switch kind {
            case .number:
                CheckedLineSheet(
                    sheet: N.numberSheet(g: goal), palette: palette, initial: N.numberInitial(g: goal),
                    canSave: { [goal] in N.canSaveNumber(typed: $0, g: goal) },
                    close: { editing = nil }
                ) { typed in
                    await model.setGoalNumber(gid, typed) ? nil : N.NOT_A_NUMBER
                }
            case .add:
                if let m = metric {
                    CheckedLineSheet(
                        sheet: N.addSheet(m: m), palette: palette, initial: "",
                        canSave: { N.canAdd(typed: $0) },
                        close: { editing = nil }
                    ) { typed in
                        switch await model.addGoalUpdate(gid, typed) {
                        case .added(let id): updateLine = N.addedLine(typed: typed, changed: true); undoID = id; return nil
                        case .noChange: updateLine = N.addedLine(typed: typed, changed: false); undoID = nil; return nil
                        case .notANumber: return N.NOT_AN_UPDATE
                        }
                    }
                }
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: updateLine)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: metric?.recent.map(\.id) ?? [])
    }

    private func stopCounting() {
        let gid = goal.id
        Task { _ = await model.setGoalNumber(gid, "") }
    }
}

/// Which of a number goal's sheets is open (walk-through week 5, A17).
private enum GoalNumberSheetKind: String, Identifiable {
    case number, add
    var id: String { rawValue }
}

/// A sheet whose line is checked before it goes, the Mac twin of the Fold's `CheckedLineSheetView` (walk-through week 5,
/// A17): it starts with [initial]; Save/Add is disabled until [canSave] and while the line is checked; [submit] returns
/// what was wrong (the sheet stays open with the words, the problem under the field in the critical colour) or nil (it
/// closes). Typing clears the problem. Only Strings cross to the core.
private struct CheckedLineSheet: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let sheet: GoalAddSheet
    let palette: MekaPalette
    let initial: String
    let canSave: (String) -> Bool
    let close: () -> Void
    let submit: (String) async -> String?
    @State private var line = ""
    @State private var busy = false
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sheet.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            TextField(sheet.hint, text: $line).textFieldStyle(.roundedBorder).onSubmit(save)
                .accessibilityLabel(sheet.spokenHint)
                .onChange(of: line) { _, _ in problem = nil }
            if let problem {
                Text(LoneWordGlue.shared.kept(line: problem)).font(MekaType.caption).foregroundStyle(palette.critical)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            Text(LoneWordGlue.shared.kept(line: sheet.note)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(GoalAddRules.shared.CLOSE, action: close).keyboardShortcut(.cancelAction)
                Button(sheet.addLabel, action: save).keyboardShortcut(.defaultAction)
                    .disabled(busy || !canSave(line))
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: problem)
        .onAppear { if line.isEmpty { line = initial } }
    }

    private func save() {
        let typed = line.trimmingCharacters(in: .whitespaces)
        guard !busy, canSave(line) else { return }
        busy = true
        Task {
            let said = await submit(typed)
            busy = false
            if let said { problem = said; MekaHaptics.tick() } else { MekaHaptics.light(); close() }
        }
    }
}

/// The number's chart, the Mac twin of the Fold's `MetricChart`: its line in the accent, the pace line and the target
/// faint and dashed; the line draws on from the left on appear (Reduce Motion: at once).
private struct MetricChartView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let metric: GoalMetric
    let today: Int64
    let palette: MekaPalette
    @State private var reveal: CGFloat = 0

    var body: some View {
        let plot = GoalMetricRules.shared.plot(m: metric, today: today)
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let at: (PlotPoint) -> CGPoint = { p in CGPoint(x: CGFloat(p.x) * w, y: h - CGFloat(p.y) * h) }
            ZStack {
                Path { p in
                    let y = h - CGFloat(plot.targetY) * h
                    p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: w, y: y))
                }
                .stroke(palette.surfaceRaised, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                if plot.pace.count == 2 {
                    Path { p in p.move(to: at(plot.pace[0])); p.addLine(to: at(plot.pace[1])) }
                        .stroke(palette.textTertiary, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                }
                if let first = plot.line.first, let last = plot.line.last {
                    Path { p in
                        p.move(to: at(first))
                        for pt in plot.line.dropFirst() { p.addLine(to: at(pt)) }
                    }
                    .trim(from: 0, to: reveal)
                    .stroke(palette.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    Circle().fill(palette.accent).frame(width: 6, height: 6).position(at(last)).opacity(reveal >= 1 ? 1 : 0)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Chart: \(metric.line)" + (metric.paceLine.map { ", \($0)" } ?? ""))
        .onAppear {
            if reduceMotion { reveal = 1 } else { withAnimation(MekaMotion.replan(reduced: false)) { reveal = 1 } }
        }
    }
}

/// Walk-through week 5, A17: "+ New habit" / "+ New goal" is one row opening a sheet, not an open box on the page.
struct GoalAddRowButton: View {
    let row: GoalAddRow
    let palette: MekaPalette
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.label).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(GoalAddRules.shared.rowSpoken(row: row))
    }
}

private struct AddHabitRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var open = false

    var body: some View {
        GoalAddRowButton(row: GoalAddRules.shared.habitRow, palette: palette) { open = true }
            .sheet(isPresented: $open) { HabitAddSheet(palette: palette) { open = false } }
    }
}

private struct HabitAddSheet: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    let close: () -> Void
    @State private var title = ""
    @State private var perWeek = Int(GoalAddRules.shared.DEFAULT_PER_WEEK)
    @State private var timing: HabitTiming = GoalAddRules.shared.DEFAULT_TIMING

    var body: some View {
        let sheet = GoalAddRules.shared.habitSheet
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sheet.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            TextField(sheet.hint, text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
                .accessibilityLabel(sheet.spokenHint)
            Picker(GoalAddRules.shared.HOW_OFTEN, selection: $perWeek) {
                ForEach(GoalRules.shared.TARGET_CHOICES, id: \.perWeek) { c in Text(c.label).tag(Int(c.perWeek)) }
            }
            .fixedSize()
            Picker(GoalAddRules.shared.WHEN, selection: $timing) {
                ForEach(GoalsScreen.timings, id: \.self) { t in Text(GoalRules.shared.timingLabel(t: t)).tag(t) }
            }
            .fixedSize()
            Text(sheet.note).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(GoalAddRules.shared.CLOSE, action: close).keyboardShortcut(.cancelAction)
                Button(sheet.addLabel, action: add).keyboardShortcut(.defaultAction)
                    .disabled(!GoalAddRules.shared.canAdd(title: title))
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
    }

    private func add() {
        guard let t = GoalAddRules.shared.cleaned(title: title) else { return }
        model.addHabit(t, perWeek: perWeek, timing: timing)
        close()
    }
}

private struct AddGoalRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var open = false

    var body: some View {
        GoalAddRowButton(row: GoalAddRules.shared.goalRow, palette: palette) { open = true }
            .sheet(isPresented: $open) { GoalAddSheetView(palette: palette) { open = false } }
    }
}

private struct GoalAddSheetView: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    let close: () -> Void
    @State private var title = ""
    @State private var target = ""
    @State private var horizon = Int(GoalRules.shared.HORIZONS.firstIndex(of: GoalAddRules.shared.DEFAULT_HORIZON) ?? 1)

    var body: some View {
        let sheet = GoalAddRules.shared.goalSheet
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sheet.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            TextField(sheet.hint, text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
                .accessibilityLabel(sheet.spokenHint)
            TextField(GoalAddRules.shared.DONE_LOOKS_LIKE, text: $target).textFieldStyle(.roundedBorder).onSubmit(add)
            Picker(GoalAddRules.shared.HORIZON, selection: $horizon) {
                ForEach(0..<GoalRules.shared.HORIZONS.count, id: \.self) { i in
                    Text(GoalRules.shared.horizonLabel(h: GoalRules.shared.horizonAt(index: Int32(i)))).tag(i)
                }
            }
            .fixedSize()
            Text(sheet.note).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(GoalAddRules.shared.CLOSE, action: close).keyboardShortcut(.cancelAction)
                Button(sheet.addLabel, action: add).keyboardShortcut(.defaultAction)
                    .disabled(!GoalAddRules.shared.canAdd(title: title))
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
    }

    private func add() {
        guard let t = GoalAddRules.shared.cleaned(title: title) else { return }
        model.addGoal(t, target: GoalAddRules.shared.cleanedTarget(target: target), horizonIndex: horizon)
        close()
    }
}

/// Goal row in the task detail: which goal this task counts towards. Shown once a goal exists.
struct GoalMenu: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette

    var body: some View {
        let goals = model.goals?.goals ?? []
        if !goals.isEmpty || task.goalId != nil {
            Menu {
                Button("No goal") { model.setTaskGoal(task.id, nil) }
                ForEach(goals, id: \.id) { g in
                    Button {
                        model.setTaskGoal(task.id, g.id)
                    } label: {
                        if g.id == task.goalId { Label(g.title, systemImage: "checkmark") } else { Text(g.title) }
                    }
                }
            } label: {
                HStack {
                    Text("Goal").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Text(goals.first { $0.id == task.goalId }?.title ?? "No goal").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .padding(.top, MekaSpace.m)
        }
    }
}

/// An N-a-week habit's week (Fold review 2026-10-09 13:45, item 1), the Mac twin of the Fold's `WeekSlots`: one ring
/// per go this week, filled as they're done, then the week's days as faint ticks only where it was done, so a day off
/// never reads as a miss.
struct WeekSlotsView: View {
    let filled: Int
    let total: Int
    let week: [Bool]
    let palette: MekaPalette
    let reduceMotion: Bool

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            HStack(spacing: 3) { // rhythm: ok (the slot rings are one mark)
                ForEach(0..<max(total, 0), id: \.self) { i in
                    let on = i < filled
                    Circle().fill(on ? palette.accent : Color.clear)
                        .overlay(Circle().strokeBorder(on ? palette.accent : palette.textTertiary, lineWidth: 1.5))
                        .frame(width: 9, height: 9)
                }
            }
            HStack(spacing: 2) { // rhythm: ok (the day ticks are one mark)
                ForEach(Array(week.enumerated()), id: \.offset) { _, on in
                    Circle().fill(on ? palette.textTertiary : Color.clear).frame(width: 4, height: 4)
                }
            }
        }
        .animation(MekaMotion.complete(reduced: reduceMotion), value: filled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(filled) of \(total) this week")
    }
}

/// Goals' MONEY CHALLENGES (Money manager 15): each challenge he set in Money with its streak, brass while a run is going.
/// No amounts, so it needs no Touch ID; clicking a row opens Money, which asks for it.
private struct MoneyStreaksSection: View {
    let streaks: MoneyStreaksView
    let palette: MekaPalette
    let open: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(streaks.head, palette)
            ForEach(streaks.rows, id: \.key) { row in
                MoneyStreakRow(row: row, palette: palette, open: open)
            }
            if let note = streaks.note {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Text(streaks.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
    }
}

private struct MoneyStreakRow: View {
    let row: MoneyStreak
    let palette: MekaPalette
    let open: () -> Void

    var body: some View {
        let lineColor: Color = row.lit ? palette.accent : palette.textSecondary
        Button(action: open) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.itemMeta).foregroundStyle(lineColor).contentTransition(.opacity)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(row.spoken)
    }
}

/// Goals' READING GOAL (reading and learning, slice 4): the year's goal, how many so far and the pace, brass on pace.
private struct ReadingGoalSection: View {
    let goal: ReadingGoalView
    let palette: MekaPalette
    let open: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(ReadingGoalRules.shared.HEAD, palette)
            Button(action: open) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack {
                        Text(goal.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(goal.count).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                    }
                    Text(goal.line).font(MekaType.itemMeta)
                        .foregroundStyle(goal.lit ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(goal.spoken)
            Text(ReadingGoalRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
    }
}

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
                Text(model.goals?.paceLine ?? "Add a habit and MEKA keeps count, and makes room in your plan when one falls behind.")
                    .font(MekaType.itemMeta)
                    .foregroundStyle((model.goals?.behind ?? 0) > 0 ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(1)

                SectionLabel("Fasting", palette).staggeredAppear(2)
                FastingSection(palette: palette).padding(.bottom, MekaSpace.l).staggeredAppear(2)
                SectionLabel("Habits", palette).staggeredAppear(2)
                ForEach(model.goals?.habits ?? [], id: \.id) { h in
                    HabitRowView(habit: h, goals: model.goals?.goals ?? [], expanded: open == h.id, palette: palette) { toggle(h.id) }
                }
                AddHabitRow(palette: palette).padding(.top, MekaSpace.s)
                // The Gym: one click adds it with its sessions booked (offered until a booked habit exists).
                if !(model.goals?.habits ?? []).contains(where: { $0.booked }) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Button("Add Gym") { model.addGym() }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                        Text("Three times a week, evenings, an hour: MEKA books the sessions around your calendar and work, and rebooks a missed one.")
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    .padding(.top, MekaSpace.s)
                    .transition(.opacity)
                }

                SectionLabel("Goals", palette).padding(.top, MekaSpace.xl).staggeredAppear(3)
                let goals = model.goals?.goals ?? []
                if goals.isEmpty {
                    Text("Say what you're working towards. Link habits and tasks to a goal and its progress counts itself.")
                        .font(MekaType.body).foregroundStyle(palette.textSecondary).padding(.vertical, MekaSpace.s)
                }
                ForEach(goals, id: \.id) { g in
                    GoalRowView(goal: g, expanded: open == g.id, palette: palette) { toggle(g.id) }
                }
                if let finished = model.goals?.finishedGoals, finished > 0 {
                    Text("\(finished) finished").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                AddGoalRow(palette: palette).padding(.top, MekaSpace.s)
            }
            .frame(maxWidth: 720, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutter)
            .padding(.vertical, MekaSpace.xl)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: rowIDs)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
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

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
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
                        HStack(spacing: MekaSpace.s) {
                            HStack(spacing: 3) {
                                ForEach(Array(habit.week.enumerated()), id: \.offset) { _, on in
                                    Circle().fill(on.boolValue ? palette.accent : palette.surfaceRaised).frame(width: 6, height: 6)
                                }
                            }
                            .accessibilityLabel("\(habit.week.filter { $0.boolValue }.count) days ticked this week")
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
                HStack(spacing: MekaSpace.l) {
                    Menu(GoalRules.shared.targetLabel(perWeek: habit.targetPerWeek)) {
                        ForEach(GoalRules.shared.TARGET_CHOICES, id: \.perWeek) { c in
                            Button(c.label) { model.setHabitTarget(habit.id, c.perWeek) }
                        }
                    }
                    .menuStyle(.button).fixedSize()
                    Menu(GoalRules.shared.timingLabel(t: habit.timing)) {
                        ForEach(GoalsScreen.timings, id: \.self) { t in
                            Button(GoalRules.shared.timingLabel(t: t)) { model.setHabitTiming(habit.id, t) }
                        }
                    }
                    .menuStyle(.button).fixedSize()
                    Menu("\(habit.minutes) min") {
                        ForEach(GoalRules.shared.MINUTE_CHOICES, id: \.intValue) { m in
                            Button("\(m.intValue) min") { model.setHabitMinutes(habit.id, m.int32Value) }
                        }
                    }
                    .menuStyle(.button).fixedSize()
                    if !goals.isEmpty {
                        Menu(goals.first { $0.id == habit.goalId }?.title ?? "No goal") {
                            Button("No goal") { model.setHabitGoal(habit.id, nil) }
                            ForEach(goals, id: \.id) { g in Button(g.title) { model.setHabitGoal(habit.id, g.id) } }
                        }
                        .menuStyle(.button).fixedSize()
                    }
                    Button("Delete", role: .destructive) { model.deleteHabit(habit.id) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.critical)
                }
                .padding(.leading, 22 + MekaSpace.m)
                .transition(.opacity.combined(with: .move(edge: .top)))
                HStack(spacing: MekaSpace.l) {
                    Toggle("Book my sessions", isOn: Binding(get: { habit.booked }, set: { model.setHabitBooked(habit.id, $0) }))
                        .toggleStyle(.switch).font(MekaType.body).fixedSize()
                    if habit.booked {
                        Menu(habit.rotationLabel) {
                            ForEach(Array(SessionRules.shared.ROTATIONS.enumerated()), id: \.offset) { i, r in
                                Button(SessionRules.shared.rotationLabel(r: r)) { model.setHabitRotation(habit.id, i) }
                            }
                        }
                        .menuStyle(.button).fixedSize()
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
        .padding(MekaSpace.s)
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
        VStack(alignment: .leading, spacing: 2) {
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
    }

    private func save(_ link: String) {
        let id = habit.id
        let value = String(link.prefix(Int(SessionRules.shared.MAX_LINK)))
        guard !value.trimmingCharacters(in: .whitespaces).isEmpty || habit.appLink != nil else { return }
        Task { bad = !(await model.setHabitAppLink(id, value)) }
    }
}

/// A goal: title, target, a bar that fills on appear, and how progress is counted.
private struct GoalRowView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let goal: GoalItem
    let expanded: Bool
    let palette: MekaPalette
    let toggle: () -> Void
    @State private var shown: Double = 0

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Button(action: toggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack {
                        Text(goal.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text("\(goal.progressPct)%").font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textSecondary)
                    }
                    if let target = goal.target {
                        Text(target).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.surfaceRaised)
                            Capsule().fill(palette.accent).frame(width: geo.size.width * shown)
                        }
                    }
                    .frame(height: 6)
                    .padding(.vertical, MekaSpace.xxs)
                    Text(goal.meta).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(goal.title), \(goal.progressPct) percent, \(goal.meta)")
            .accessibilityHint(expanded ? "Hides actions" : "Shows actions")
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.s) {
                    HStack(spacing: MekaSpace.l) {
                        if !goal.counted {
                            Button("−10%") { model.stepGoal(goal.id, from: goal.progressPct, by: -10) }
                            Button("+10%") { model.stepGoal(goal.id, from: goal.progressPct, by: 10) }
                        }
                        Menu(GoalRules.shared.horizonLabel(h: goal.horizon)) {
                            ForEach(0..<3, id: \.self) { i in
                                Button(GoalRules.shared.horizonLabel(h: GoalRules.shared.horizonAt(index: Int32(i)))) { model.setGoalHorizon(goal.id, index: i) }
                            }
                        }
                        .menuStyle(.button).fixedSize()
                        Button("Finished") { model.finishGoal(goal.id) }
                        Button("Delete", role: .destructive) { model.deleteGoal(goal.id) }.foregroundStyle(palette.critical)
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                    if goal.counted {
                        Text("Progress counts itself from the habits and tasks linked to this goal.")
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.s)
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

private struct AddHabitRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var title = ""
    @State private var perWeek = 7
    @State private var timing: HabitTiming = .anytime

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            TextField("New habit…", text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
            if !title.isEmpty {
                HStack(spacing: MekaSpace.m) {
                    Picker("How often", selection: $perWeek) {
                        ForEach(GoalRules.shared.TARGET_CHOICES, id: \.perWeek) { c in Text(c.label).tag(Int(c.perWeek)) }
                    }
                    .fixedSize()
                    Picker("When", selection: $timing) {
                        ForEach(GoalsScreen.timings, id: \.self) { t in Text(GoalRules.shared.timingLabel(t: t)).tag(t) }
                    }
                    .fixedSize()
                    Button("Add", action: add).keyboardShortcut(.defaultAction)
                }
                .transition(.opacity)
            }
        }
    }

    private func add() {
        model.addHabit(title, perWeek: perWeek, timing: timing)
        title = ""; perWeek = 7; timing = .anytime
    }
}

private struct AddGoalRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var title = ""
    @State private var target = ""
    @State private var horizon = 1

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            TextField("New goal…", text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
            if !title.isEmpty {
                HStack(spacing: MekaSpace.m) {
                    TextField("What does done look like? (optional)", text: $target).textFieldStyle(.roundedBorder).onSubmit(add)
                    Picker("Horizon", selection: $horizon) {
                        ForEach(0..<3, id: \.self) { i in
                            Text(GoalRules.shared.horizonLabel(h: GoalRules.shared.horizonAt(index: Int32(i)))).tag(i)
                        }
                    }
                    .fixedSize()
                    Button("Add", action: add).keyboardShortcut(.defaultAction)
                }
                .transition(.opacity)
            }
        }
    }

    private func add() {
        model.addGoal(title, target: target, horizonIndex: horizon)
        title = ""; target = ""; horizon = 1
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

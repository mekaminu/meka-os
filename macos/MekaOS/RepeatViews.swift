@preconcurrency import MekaKit
import SwiftUI

/// Repeat row in the task detail: the rule, opening a menu of presets for the task's day (simpler than the Fold's
/// inline picker, as rule 7 allows).
struct RepeatMenu: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette
    @State private var choices: [RepeatChoice] = []

    var body: some View {
        Menu {
            ForEach(choices, id: \.label) { c in
                Button {
                    model.setRepeat(task.id, rule: c.rule)
                } label: {
                    if c.selected { Label(c.label, systemImage: "checkmark") } else { Text(c.label) }
                }
            }
        } label: {
            HStack {
                Text("Repeat").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(task.repeatLabel ?? "Doesn't repeat").font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .task(id: refreshKey) { choices = await model.repeatChoices(task.id) }
    }

    /// Re-read the presets whenever the rule or the task's day changes.
    private var refreshKey: String {
        "\(task.id)|\(task.recurrenceRule ?? "")|\(task.occurrenceDay?.int64Value ?? -1)|\(task.scheduledAtMs?.int64Value ?? -1)|\(task.dueAtMs?.int64Value ?? -1)"
    }
}

/// Checklist (Checklists in tasks, slice 1; "Routine steps" on a repeating task, whose occurrences bring them back
/// unticked). Still to tick first, then "Ticked" with Untick all; typing or pasting several lines (or commas) adds each
/// as an item; all ticked offers to complete the task. Motion (catalogue "Checklist"): a tick draws its check and pops
/// with a light haptic, then the row moves under Ticked on the replan spring; Remove gives a tick haptic, the row
/// leaves and the undo bar rises. Slice 2: drag a row onto another in its group to reorder (the target row tints while
/// it's over it; tick haptic on drop, the rows glide on the replan spring), or Move up / Move down from its menu; "Shop
/// mode" opens the list big in a sheet ([ShopModeSheet]). Reduce Motion: colour changes and cross-fades only.
struct StepsList: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var newStep = ""
    @State private var shopping = false

    private func addPending(to id: String) {
        if let step = TextAutosave.shared.pendingAdd(typed: newStep) { model.addStep(id, step) }
        newStep = ""
    }

    var body: some View {
        let view = model.checklistView(task)
        let toTick = view?.toTick ?? []
        let ticked = view?.ticked ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                SectionLabel(view?.label ?? "Checklist", palette)
                Spacer()
                if let line = view?.line {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                }
                if ShopModeRules.shared.offer(itemCount: Int32(toTick.count + ticked.count)) {
                    Button(ShopModeRules.shared.OPEN) { MekaHaptics.light(); shopping = true }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                }
            }
            .padding(.top, MekaSpace.m)
            ForEach(Array(toTick.enumerated()), id: \.element.id) { i, step in
                StepRow(step: step, taskID: task.id, index: i, groupIDs: toTick.map(\.id), palette: palette).transition(.opacity)
            }
            if !ticked.isEmpty {
                HStack {
                    Text(ChecklistRules.shared.TICKED_LABEL).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    Button(ChecklistRules.shared.UNTICK_ALL) { model.untickAllSteps(task.id) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                }
                .padding(.top, MekaSpace.xs)
                .transition(.opacity)
                ForEach(Array(ticked.enumerated()), id: \.element.id) { i, step in
                    StepRow(step: step, taskID: task.id, index: i, groupIDs: ticked.map(\.id), palette: palette).transition(.opacity)
                }
            }
            if view?.offerComplete == true {
                HStack {
                    Text(ChecklistRules.shared.COMPLETE_OFFER).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Button("Complete") { MekaHaptics.light(); model.complete(task.id) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.m)
                        .padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(palette.accent))
                }
                .padding(MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            TextField(ChecklistRules.shared.ADD_HINT, text: $newStep, axis: .vertical)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .lineLimit(1...6)
                .onSubmit { model.addStep(task.id, newStep); newStep = "" }
                // A paste with several lines (or a new line typed) adds them all at once, each line an item.
                .onChange(of: newStep) { _, typed in
                    if typed.contains("\n") { model.addStep(task.id, typed); newStep = "" }
                }
                // An item typed but not yet added is added when the detail closes or shows another task, as if
                // Return had been pressed (TextAutosave: typing is never lost).
                .onChange(of: task.id) { oldId, _ in addPending(to: oldId) }
                .onDisappear { addPending(to: task.id) }
        }
        .sheet(isPresented: $shopping) { ShopModeSheet(source: .checklist(task.id), palette: palette) }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: toTick.map(\.id) + ["|"] + ticked.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: view?.offerComplete == true)
    }
}

private struct StepRow: View {
    @Environment(CoreModel.self) private var model
    let step: ChecklistItem
    let taskID: String
    /// This row's place in its group (still to tick, or Ticked), and the group's ids, for reordering.
    let index: Int
    let groupIDs: [String]
    let palette: MekaPalette
    @State private var dropTarget = false

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Button {
                model.setStepDone(step.id, !step.checked)
            } label: {
                HStack(spacing: MekaSpace.xs) {
                    TickRingView(done: step.checked, palette: palette)
                        .frame(width: 18, height: 18)
                    Text(step.text)
                        .font(MekaType.body)
                        .foregroundStyle(step.checked ? palette.textTertiary : palette.textPrimary)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 28)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel((step.checked ? "Untick " : "Tick ") + step.text)
            Button("Remove") { model.removeStep(step.id, step.text) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
        }
        .padding(.horizontal, MekaSpace.xxs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(dropTarget ? palette.surfaceRaised : .clear))
        // Drag to reorder (slice 2): drop a row onto another in the same group; it takes that row's place.
        .draggable(step.id)
        .dropDestination(for: String.self) { ids, _ in
            guard let dragged = ids.first, dragged != step.id, groupIDs.contains(dragged) else { return false }
            model.moveStep(taskID, dragged, toGroupIndex: index)
            return true
        } isTargeted: { dropTarget = $0 }
        .contextMenu {
            if groupIDs.count > 1 {
                Button("Move up") { model.moveStep(taskID, step.id, toGroupIndex: index - 1) }.disabled(index == 0)
                Button("Move down") { model.moveStep(taskID, step.id, toGroupIndex: index + 1) }.disabled(index >= groupIDs.count - 1)
            }
            Button("Remove") { model.removeStep(step.id, step.text) }
        }
    }
}

/// Shop mode on the Mac (Checklists in tasks, slice 2): a checklist or the shared Shopping list big in a sheet, still
/// to get first, the display kept awake while it's open (it reads the Mac's own synced copy, so it works offline).
/// Motion (catalogue "Shop mode"): the sheet's own rise; a click draws the check (light haptic) and the row glides
/// under Ticked/Got on the replan spring; a done row goes back up with a tick haptic. Reduce Motion: cross-fades.
struct ShopModeSheet: View {
    enum Source: Equatable { case checklist(String), shopping }

    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let source: Source
    let palette: MekaPalette
    @State private var awake: NSObjectProtocol?

    private var view: ShopModeView? {
        switch source {
        case .checklist(let id): model.allTasks.first(where: { $0.id == id }).flatMap { model.shopMode($0) }
        case .shopping: model.lists.flatMap { model.shopMode(shopping: $0.shopping) }
        }
    }

    private func tick(_ row: ShopRow) {
        switch source {
        case .checklist: model.setStepDone(row.id, !row.done)
        case .shopping: if row.done { model.putBackShopping(row.id) } else { model.gotShopping(row.id) }
        }
    }

    var body: some View {
        let v = view
        let toGet = v?.toGet ?? []
        let done = v?.done ?? []
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v?.title ?? "").font(MekaType.greeting).foregroundStyle(palette.textPrimary)
                    Text(v?.line ?? ShopModeRules.shared.EMPTY).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                }
                Spacer()
                Button(ShopModeRules.shared.CLOSE) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.metaStrong)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.l)
                    .padding(.vertical, MekaSpace.xs)
                    .background(Capsule().fill(palette.accent))
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(toGet, id: \.id) { r in ShopModeRowView(row: r, palette: palette) { tick(r) }.transition(.opacity) }
                    if !done.isEmpty {
                        Text((v?.doneLabel ?? "").uppercased())
                            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.l)
                        ForEach(done, id: \.id) { r in ShopModeRowView(row: r, palette: palette) { tick(r) }.transition(.opacity) }
                    }
                }
                .animation(MekaMotion.replan(reduced: reduceMotion), value: toGet.map(\.id) + ["|"] + done.map(\.id))
            }
            Text(ShopModeRules.shared.OFFLINE_NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .padding(MekaSpace.l)
        .frame(minWidth: 480, idealWidth: 560, minHeight: 600, idealHeight: 760)
        .background(palette.background)
        .onAppear {
            awake = ProcessInfo.processInfo.beginActivity(options: .idleDisplaySleepDisabled, reason: "Shop mode")
        }
        .onDisappear {
            if let awake { ProcessInfo.processInfo.endActivity(awake) }
            awake = nil
        }
    }
}

private struct ShopModeRowView: View {
    let row: ShopRow
    let palette: MekaPalette
    let onTap: () -> Void

    var body: some View {
        Button {
            if row.done { MekaHaptics.tick() } else { MekaHaptics.light() }
            onTap()
        } label: {
            HStack(spacing: MekaSpace.m) {
                TickRingView(done: row.done, palette: palette).frame(width: 28, height: 28)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.text).font(MekaType.nowTitle)
                        .foregroundStyle(row.done ? palette.textTertiary : palette.textPrimary)
                    if let meta = row.meta {
                        Text(meta).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel((row.done ? "Put back " : "Got ") + row.text)
    }
}

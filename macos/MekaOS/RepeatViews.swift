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
/// leaves and the undo bar rises. Reduce Motion: colour changes and cross-fades only.
struct StepsList: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var newStep = ""

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
            }
            .padding(.top, MekaSpace.m)
            ForEach(toTick, id: \.id) { step in
                StepRow(step: step, palette: palette).transition(.opacity)
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
                ForEach(ticked, id: \.id) { step in
                    StepRow(step: step, palette: palette).transition(.opacity)
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
        .animation(MekaMotion.replan(reduced: reduceMotion), value: toTick.map(\.id) + ["|"] + ticked.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: view?.offerComplete == true)
    }
}

private struct StepRow: View {
    @Environment(CoreModel.self) private var model
    let step: ChecklistItem
    let palette: MekaPalette

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
    }
}
